package org.x9s26u.rcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QuickSRNetLarge (Qualcomm AI Hub, float .tflite) running through LiteRT's CompiledModel API.
 *
 * Facts about the file bundled in your APK (read from the flatbuffer, not assumed):
 *   input  1x128x128x3  float32 RGB 0..1      output 1x512x512x3 float32 0..1  -> it is the 4x model (SETUP.md said 3x)
 *   13 stacked 3x3 convolutions -> receptive-field radius 13 px; the network clips every layer to [0,1] itself.
 *
 * What this class does differently from the old OpticoreEnhancer.kt:
 *   - tile overlap 16 (>= 13), so seams are bit-exact against whole-image inference
 *   - the ROI is reflect-padded at the *bitmap* borders (the network zero-pads, which darkens the outer 13 px)
 *   - works on an in-memory Bitmap, because the Java pipeline already holds one
 *   - tries GPU first, CPU second (NPU is opt-in, see below) and rejects an accelerator whose output fails a self-test
 *   - never post-sharpens. SR output must NOT go through applyPostScaleEdgeCrispPassInPlace.
 *
 * NPU note: the APK ships libQnnHtp / libQnnHtpV79Stub / libQnnSystem but is missing libQnnHtpV79Skel.so,
 * libQnnHtpPrepare.so and libLiteRtCompilerPlugin_Qualcomm.so, and libLiteRtDispatch_Qualcomm.so is a byte-for-byte
 * copy of libQnnSystem.so. Keep tryNpu=false until those are the real files.
 *
 * Create ONE instance on a background thread (model compile is slow) and reuse it.
 */
class QuickSrNet private constructor(
    private val model: CompiledModel,
    private val inBufs: List<TensorBuffer>,
    private val outBufs: List<TensorBuffer>,
    /** Upscale factor of the loaded model (4 for the bundled file). */
    val scale: Int,
    /** "GPU", "CPU" or "NPU": what actually passed the self-test. */
    val accelerator: String,
) : AutoCloseable {

    companion object {
        private const val TAG = "QuickSrNet"
        const val TILE = 128
        const val OVERLAP = 16                       // must be >= 13 (receptive-field radius)
        private const val STRIDE = TILE - 2 * OVERLAP // 96 input px kept per tile
        private const val CH = 3

        /** Java: QuickSrNet net = QuickSrNet.create(getApplicationContext()); */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            modelAsset: String = "QuickSRNetLarge.tflite",
            tryNpu: Boolean = false,
        ): QuickSrNet {
            val attempts = ArrayList<Pair<String, Array<Accelerator>>>()
            if (tryNpu) attempts += "NPU" to arrayOf(Accelerator.NPU, Accelerator.GPU)
            attempts += "GPU" to arrayOf(Accelerator.GPU)
            attempts += "CPU" to arrayOf(Accelerator.CPU)

            var last: Throwable? = null
            for ((name, accs) in attempts) {
                var m: CompiledModel? = null
                try {
                    m = CompiledModel.create(
                        context.applicationContext.assets,
                        modelAsset,
                        CompiledModel.Options(*accs),
                    )
                    val inB = m.createInputBuffers()
                    val outB = m.createOutputBuffers()

                    // warm-up run + scale detection from the output tensor size
                    inB[0].writeFloat(FloatArray(TILE * TILE * CH))
                    m.run(inB, outB)
                    val len = outB[0].readFloat().size
                    val s = sqrt(len / (TILE * TILE * CH).toDouble()).roundToInt()
                    require(s >= 1 && TILE * TILE * CH * s * s == len) { "unexpected output size $len" }

                    val net = QuickSrNet(m, inB, outB, s, name)
                    net.selfTest()
                    Log.i(TAG, "ready on $name, x$s")
                    return net
                } catch (t: Throwable) {
                    last = t
                    Log.w(TAG, "$name not usable: ${t.message}")
                    runCatching { m?.close() }
                }
            }
            throw IllegalStateException("QuickSRNet could not start on any accelerator", last)
        }
    }

    private val inFloats = FloatArray(TILE * TILE * CH)
    private val tilePx = IntArray(TILE * TILE)
    private val rowPx = IntArray(TILE)
    private val outPx = IntArray((STRIDE * scale) * (STRIDE * scale))

    /**
     * Upscales [roi] of [src] (null = the whole bitmap).
     *
     * @param outScale 1..[scale]; must divide [scale]. The network always produces [scale]x; for outScale < scale
     *   every (scale/outScale)^2 block is averaged (a cheap anti-aliased resample). Example: outScale = 2 gives an
     *   exact 2x result from the 4x model.
     * @return a new ARGB_8888 bitmap of (roi.width * outScale) x (roi.height * outScale). Caller recycles it.
     */
    @JvmOverloads
    @Synchronized
    fun upscale(src: Bitmap, roi: Rect? = null, outScale: Int = scale): Bitmap {
        require(outScale >= 1 && scale % outScale == 0) { "outScale must divide the model scale ($scale)" }
        val r = roi ?: Rect(0, 0, src.width, src.height)
        require(r.left >= 0 && r.top >= 0 && r.right <= src.width && r.bottom <= src.height) { "roi outside bitmap" }
        val rw = r.width()
        val rh = r.height()
        require(rw >= 8 && rh >= 8 && src.width >= 8 && src.height >= 8) { "image too small" }

        val out = Bitmap.createBitmap(rw * outScale, rh * outScale, Bitmap.Config.ARGB_8888)
        val xs = origins(rw + 2 * OVERLAP)
        val ys = origins(rh + 2 * OVERLAP)
        val t0 = SystemClock.elapsedRealtime()
        var tiles = 0
        try {
            for (py in ys) {
                for (px in xs) {
                    // tile origin in *bitmap* coordinates; the ROI is padded by OVERLAP on every side
                    val tx = r.left - OVERLAP + px
                    val ty = r.top - OVERLAP + py
                    readTile(src, tx, ty)
                    inBufs[0].writeFloat(inFloats)
                    model.run(inBufs, outBufs)
                    val o = outBufs[0].readFloat() // ~3 MB of short-lived garbage per tile (LiteRT API)

                    // part of the tile that lands inside the ROI, in tile-local input pixels
                    val l = maxOf(OVERLAP, r.left - tx)
                    val rr = minOf(TILE - OVERLAP, r.right - tx)
                    val t = maxOf(OVERLAP, r.top - ty)
                    val b = minOf(TILE - OVERLAP, r.bottom - ty)
                    writeBlock(out, o, (tx + l - r.left) * outScale, (ty + t - r.top) * outScale, l, rr, t, b, outScale)
                    tiles++
                }
            }
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "${rw}x$rh -> ${out.width}x${out.height} on $accelerator: $tiles tiles, $ms ms (${ms / tiles.coerceAtLeast(1)} ms/tile)")
        return out
    }

    // ---------------------------------------------------------------- internals

    /** Tile start coordinates along one axis of length [len]; the last tile is clamped to end at [len]. */
    private fun origins(len: Int): IntArray {
        val list = ArrayList<Int>()
        var p = 0
        while (true) {
            if (p + TILE >= len) {
                list.add(len - TILE)
                break
            }
            list.add(p)
            p += STRIDE
        }
        return list.distinct().toIntArray()
    }

    /** Reflect (no edge repeat) index into [0, n). */
    private fun mirror(i0: Int, n: Int): Int {
        var i = i0
        while (i < 0 || i >= n) {
            if (i < 0) i = -i
            if (i >= n) i = 2 * (n - 1) - i
        }
        return i
    }

    /** Fills [inFloats] (NHWC, RGB, 0..1) with the TILE x TILE window whose top-left is (tx, ty) in [src]. */
    private fun readTile(src: Bitmap, tx: Int, ty: Int) {
        val w = src.width
        val h = src.height
        if (tx >= 0 && ty >= 0 && tx + TILE <= w && ty + TILE <= h) {
            src.getPixels(tilePx, 0, TILE, tx, ty, TILE, TILE)
        } else {
            var xmin = Int.MAX_VALUE
            var xmax = Int.MIN_VALUE
            for (i in 0 until TILE) {
                val sx = mirror(tx + i, w)
                if (sx < xmin) xmin = sx
                if (sx > xmax) xmax = sx
            }
            val span = xmax - xmin + 1
            check(span <= TILE)
            for (j in 0 until TILE) {
                src.getPixels(rowPx, 0, span, xmin, mirror(ty + j, h), span, 1)
                for (i in 0 until TILE) tilePx[j * TILE + i] = rowPx[mirror(tx + i, w) - xmin]
            }
        }
        var k = 0
        for (p in tilePx) {
            inFloats[k++] = ((p shr 16) and 0xFF) / 255f
            inFloats[k++] = ((p shr 8) and 0xFF) / 255f
            inFloats[k++] = (p and 0xFF) / 255f
        }
    }

    /** Writes tile-local input window [l,rr) x [t,b) of the SR tensor [o] into [out] at (ox, oy). */
    private fun writeBlock(out: Bitmap, o: FloatArray, ox: Int, oy: Int, l: Int, rr: Int, t: Int, b: Int, outScale: Int) {
        val srW = TILE * scale
        val ratio = scale / outScale
        val inv = 1f / (ratio * ratio)
        val bw = (rr - l) * outScale
        val bh = (b - t) * outScale
        var i = 0
        for (y in 0 until bh) {
            val sy0 = t * scale + y * ratio
            for (x in 0 until bw) {
                val sx0 = l * scale + x * ratio
                var rs = 0f
                var gs = 0f
                var bs = 0f
                for (dy in 0 until ratio) {
                    var idx = ((sy0 + dy) * srW + sx0) * CH
                    for (dx in 0 until ratio) {
                        rs += o[idx]; gs += o[idx + 1]; bs += o[idx + 2]
                        idx += CH
                    }
                }
                outPx[i++] = (0xFF shl 24) or (c8(rs * inv) shl 16) or (c8(gs * inv) shl 8) or c8(bs * inv)
            }
        }
        out.setPixels(outPx, 0, bw, ox, oy, bw, bh)
    }

    private fun c8(v: Float): Int {
        if (v.isNaN()) return 0
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    /**
     * Feeds a smooth, low-contrast pattern. A correct 4x SR network returns (almost) the same pattern
     * (reference run: mean error 0.0003, max 0.004). Wrong input range, broken fp16, bad NPU quantisation, NaNs ...
     * all give mean errors >= 0.25, so this rejects a bad accelerator before it ever touches a photo.
     */
    private fun selfTest() {
        var k = 0
        val twoPi = (2.0 * PI).toFloat()
        for (y in 0 until TILE) {
            for (x in 0 until TILE) {
                val u = x / 127f
                val v = y / 127f
                inFloats[k++] = 0.25f + 0.5f * u
                inFloats[k++] = 0.25f + 0.5f * v
                inFloats[k++] = 0.5f + 0.2f * sin(twoPi * u) * cos(twoPi * v)
            }
        }
        inBufs[0].writeFloat(inFloats)
        model.run(inBufs, outBufs)
        val o = outBufs[0].readFloat()
        val srW = TILE * scale
        var sum = 0.0
        var max = 0f
        var n = 0
        for (oy in OVERLAP * scale until (TILE - OVERLAP) * scale) {
            for (ox in OVERLAP * scale until (TILE - OVERLAP) * scale) {
                for (c in 0 until CH) {
                    val v = o[(oy * srW + ox) * CH + c]
                    check(!v.isNaN() && !v.isInfinite()) { "self-test: non-finite output" }
                    val ref = inFloats[((oy / scale) * TILE + ox / scale) * CH + c]
                    val d = abs(v - ref)
                    sum += d
                    if (d > max) max = d
                    n++
                }
            }
        }
        val mean = sum / n
        check(mean < 0.01 && max < 0.05f) { "self-test failed on $accelerator: mean=$mean max=$max" }
    }

    @Synchronized
    override fun close() {
        inBufs.forEach { it.close() }
        outBufs.forEach { it.close() }
        model.close()
    }
}
