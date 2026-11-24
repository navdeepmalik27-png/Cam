package com.opticore.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What to do with the photo. [crop] = centered fraction per side, [fullRes] = keep an enhanced output. */
enum class EnhanceMode(val label: String, val crop: Float, val fullRes: Boolean) {
    /** Whole frame, output same size as the capture (SR then 3x3 box-down). Subtle: denoise/sharpen. */
    ORIGINAL("Original", 1f, false),
    CLEAN_1X("Clean 1x", 1.0f, false),

    /** Center 50% per side, cropped and upscaled 2x. */
    ZOOM_2X("Zoom 2x", 0.5f, true),

    /** Center 33% per side, cropped and upscaled 2x. */
    ZOOM_3X("Zoom 3x", 1f / 3f, true),
}

/**
 * QuickSRNetLarge (Qualcomm AI Hub, .tflite) on GPU/CPU via LiteRT, built to stay inside a RAM budget.
 *
 * How it keeps RAM low:
 *  - The 15 MP photo is never decoded as a whole. [BitmapRegionDecoder] reads a few tile-rows at a time
 *    (~8 MB for a 4608-px-wide strip) straight from the JPEG file on disk.
 *  - The output bitmap is the only big allocation, and its size is capped by [maxOutputPixels]
 *    (see [MemoryBudget]). If the requested crop would not fit, the crop is shrunk, not the app killed.
 *  - Scratch arrays (tile input, pixel block) are allocated once and reused.
 *
 * Model assumptions (verify with your exported file): input 1x128x128x3 NHWC RGB float 0..1,
 * output 1x(128*s)x(128*s)x3 float ~0..1 (s = 4 for the bundled checkpoint; detected at runtime).
 *
 * Create ONE instance and reuse it. Creating it is slow (model compile) -> do it off the main thread.
 */
class OpticoreEnhancer(
    context: Context,
    modelAsset: String = "QuickSRNetLarge.tflite",
) : AutoCloseable {

    companion object {
        private const val TAG = "OpticoreEnhancer"
        const val TILE = 128
        private const val OVERLAP = 16                    // input px dropped at interior tile edges
        private const val STRIDE = TILE - 2 * OVERLAP    // 96
        private const val CH = 3
        private const val ROWS_PER_BLOCK = 2             // tile-rows decoded per region read (fewer JPEG re-scans)
    }

    private val model: CompiledModel
    private val inBufs: List<TensorBuffer>
    private val outBufs: List<TensorBuffer>

    /** Upscale factor of the loaded model (4 for the bundled model). */
    val scale: Int

    // Reused scratch arrays
    private val inPixels = IntArray(TILE * TILE)
    private val inFloats = FloatArray(TILE * TILE * CH)
    private val outPixels: IntArray

    init {
        // Use matching Maven runtime; vendor HAL libraries are not loaded here.
        model = runCatching { CompiledModel.create(context.applicationContext.assets, modelAsset, CompiledModel.Options(Accelerator.GPU)) }
            .getOrElse { CompiledModel.create(context.applicationContext.assets, modelAsset, CompiledModel.Options(Accelerator.CPU)) }
        inBufs = model.createInputBuffers()
        outBufs = model.createOutputBuffers()

        // Warm-up run + detect the scale factor from the output size.
        inBufs[0].writeFloat(FloatArray(TILE * TILE * CH))
        model.run(inBufs, outBufs)
        val outLen = outBufs[0].readFloat().size
        val s = sqrt(outLen / (TILE * TILE * CH).toDouble()).roundToInt()
        require(s >= 1 && TILE * TILE * CH * s * s == outLen) {
            "Unexpected output tensor size $outLen (expected 128*128*3*scale^2)"
        }
        scale = s
        outPixels = IntArray(TILE * s * TILE * s)
        Log.i(TAG, "Model ready, scale=x$scale")
    }

    /**
     * Enhances the JPEG at [src] and returns the result bitmap (caller must recycle / save it).
     *
     * @param zoomCrop centered crop per side: 1.0 = full frame, 0.5 = center half, 0.33 = center third
     * @param outScale 1 = output has the crop's pixel size (SR then box-downsample),
     *                 [scale] = keep the model's full upscale. Must divide [scale].
     * @param maxOutputPixels hard cap for the output bitmap; the crop shrinks (around the center) if needed
     * @param onProgress 0..1, called from the calling thread after each decoded block of tile-rows
     */
    @Synchronized
    fun enhanceFile(
        src: File,
        zoomCrop: Float,
        outScale: Int,
        maxOutputPixels: Long,
        onProgress: (Float) -> Unit = {},
    ): Bitmap {
        require(outScale >= 1 && scale % outScale == 0) { "outScale must divide the model scale ($scale)" }

        val decoder = BitmapRegionDecoder.newInstance(src.absolutePath) ?: throw IOException("JPEG region decoder unavailable")
        try {
            val fullW = decoder.width
            val fullH = decoder.height
            require(fullW >= TILE && fullH >= TILE) { "Image too small" }
            require(maxOutputPixels >= TILE.toLong()*TILE*outScale*outScale) { "Not enough memory for AI" }

            // 1) Region of interest (centered crop), shrunk if the output would not fit the budget.
            var rw = (fullW * zoomCrop).roundToInt().coerceIn(TILE, fullW)
            var rh = (fullH * zoomCrop).roundToInt().coerceIn(TILE, fullH)
            val maxRoiPixels = maxOutputPixels / (outScale.toLong() * outScale)
            if (rw.toLong() * rh > maxRoiPixels) {
                val f = sqrt(maxRoiPixels.toDouble() / (rw.toLong() * rh))
                rw = (rw * f).toInt().coerceAtLeast(TILE)
                rh = (rh * f).toInt().coerceAtLeast(TILE)
                Log.w(TAG, "Crop shrunk to ${rw}x$rh to fit the RAM budget")
            }
            val rx = (fullW - rw) / 2
            val ry = (fullH - rh) / 2

            require(rw.toLong()*rh*outScale*outScale <= maxOutputPixels) { "Output exceeds memory budget" }
            // 2) The only big allocation.
            val out = Bitmap.createBitmap(rw * outScale, rh * outScale, Bitmap.Config.ARGB_8888)

            // 3) Walk tile-rows in blocks; decode each block from disk, process, release.
            val xs = origins(rw)
            val ys = origins(rh)
            val blocks = ys.chunked(ROWS_PER_BLOCK)
            val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

            val t0 = SystemClock.elapsedRealtime()
            var tiles = 0
            try {
                for ((bi, block) in blocks.withIndex()) {
                    val top = block.first()
                    val bottom = block.last() + TILE
                    val rect = Rect(rx, ry + top, rx + rw, ry + bottom)
                    val strip = decoder.decodeRegion(rect, decodeOpts)
                        ?: throw IOException("decodeRegion returned null for $rect")
                    try {
                        for (y0 in block) {
                            for (x0 in xs) {
                                strip.getPixels(inPixels, 0, TILE, x0, y0 - top, TILE, TILE)
                                var k = 0
                                for (p in inPixels) {
                                    inFloats[k++] = ((p shr 16) and 0xFF) / 255f
                                    inFloats[k++] = ((p shr 8) and 0xFF) / 255f
                                    inFloats[k++] = (p and 0xFF) / 255f
                                }
                                inBufs[0].writeFloat(inFloats)
                                model.run(inBufs, outBufs)
                                val o = outBufs[0].readFloat() // allocates ~3 MB per tile; short-lived garbage

                                // Part of the tile we keep (tile-local input px). Interior edges lose OVERLAP px.
                                val l = if (x0 == 0) 0 else OVERLAP
                                val r = if (x0 + TILE >= rw) TILE else TILE - OVERLAP
                                val t = if (y0 == 0) 0 else OVERLAP
                                val b = if (y0 + TILE >= rh) TILE else TILE - OVERLAP
                                writeRegion(out, o, x0, y0, l, r, t, b, outScale)
                                tiles++
                            }
                        }
                    } finally {
                        strip.recycle()
                    }
                    onProgress((bi + 1f) / blocks.size)
                }
            } catch (t: Throwable) {
                out.recycle()
                throw t
            }

            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(
                TAG,
                "src ${fullW}x$fullH, roi ${rw}x$rh -> ${out.width}x${out.height} " +
                    "(${"%.1f".format(out.width.toLong() * out.height / 1e6)} MP): " +
                    "$tiles tiles, $ms ms (${"%.2f".format(ms / tiles.coerceAtLeast(1).toFloat())} ms/tile)",
            )
            return out
        } finally {
            decoder.recycle()
        }
    }

    /** Tile start coordinates along one axis; the last tile is clamped so it ends exactly at the border. */
    private fun origins(len: Int): List<Int> {
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
        return list.distinct()
    }

    /**
     * Writes the kept part of one tile into [out].
     * outScale == scale -> copy the SR pixels; outScale == 1 -> average each (scale x scale) block.
     */
    private fun writeRegion(
        out: Bitmap, o: FloatArray,
        x0: Int, y0: Int, l: Int, r: Int, t: Int, b: Int,
        outScale: Int,
    ) {
        val srW = TILE * scale
        val ratio = scale / outScale
        val ow = (r - l) * outScale
        val oh = (b - t) * outScale
        val inv = 1f / (ratio * ratio)
        var i = 0
        for (oy in 0 until oh) {
            val sy0 = t * scale + oy * ratio
            for (ox in 0 until ow) {
                val sx0 = l * scale + ox * ratio
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
                outPixels[i++] = (0xFF shl 24) or (c8(rs * inv) shl 16) or (c8(gs * inv) shl 8) or c8(bs * inv)
            }
        }
        out.setPixels(outPixels, 0, ow, (x0 + l) * outScale, (y0 + t) * outScale, ow, oh)
    }

    private fun c8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    @Synchronized
    override fun close() {
        inBufs.forEach { it.close() }
        outBufs.forEach { it.close() }
        model.close()
    }
}
