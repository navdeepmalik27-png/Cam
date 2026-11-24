package com.opticore.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class OpticoreActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Opticore"

        /** We ask the camera for the JPEG size closest to this many pixels (~15 MP). */
        private const val TARGET_PIXELS = 15_000_000L
        private const val JPEG_QUALITY = 95
    }

    private var busy = false
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var lenses: LensSelector.Lenses

    private var wantTele = false
    private var mode = EnhanceMode.ORIGINAL

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var shutter: Button
    private lateinit var lensControls: RadioGroup
    private lateinit var modeControls: RadioGroup
    private fun setBusy(value: Boolean) {
        busy = value; shutter.isEnabled = !value && imageCapture != null
        for (group in listOf(lensControls, modeControls)) for(i in 0 until group.childCount) group.getChildAt(i).isEnabled = !value
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else status.text = "Camera permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        lenses = runCatching { LensSelector.find(this) }.getOrElse {
            LensSelector.Lenses(LensSelector.Lens("Main",null,1f,null),LensSelector.Lens("Tele",null,3f,null),"Lens discovery failed")
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        previewView = PreviewView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(32, 96, 32, 0)
            textSize = 13f
            text = "Opticore"
        }
        shutter = Button(this).apply {
            text = "Shoot"
            setOnClickListener { capture() }
        }
        val lensGroup = radioRow(listOf("Main", "Tele"), 0) { if (!busy) { wantTele = it == 1; bindLens() } }
        val modeGroup = radioRow(EnhanceMode.entries.map { it.label }, EnhanceMode.entries.indexOf(mode)) {
            if (!busy) mode = EnhanceMode.entries[it]
        }

        lensControls = lensGroup; modeControls = modeGroup; shutter.isEnabled = false
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 96)
            addView(lensGroup)
            addView(modeGroup)
            addView(shutter)
        }

        setContentView(FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    private fun radioRow(labels: List<String>, checked: Int, onChange: (Int) -> Unit): RadioGroup {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
        }
        labels.forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                tag = i
                setTextColor(Color.WHITE)
            })
        }
        group.check(group.getChildAt(checked).id)
        group.setOnCheckedChangeListener { g, id -> onChange(g.findViewById<View>(id).tag as Int) }
        return group
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun lensInfo(): String {
        val l = if (wantTele) lenses.tele else lenses.main
        return "${l.label} ${l.equivFocalMm?.let { "%.0fmm".format(it) } ?: ""}"
    }

    // ---------------------------------------------------------------- Camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed || isFinishing) return@addListener
            runCatching { cameraProvider = future.get(); bindLens() }.onFailure { setStatus("Camera unavailable: ${it.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Picks the supported JPEG size closest to ~15 MP (e.g. 4608x3456 if the camera offers it). */
    private val closestTo15Mp = ResolutionFilter { sizes, _ ->
        sizes.sortedBy { abs(it.width.toLong() * it.height - TARGET_PIXELS) }
    }

    private fun buildImageCapture() = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(JPEG_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .setResolutionFilter(closestTo15Mp)
                .build(),
        )
        .build()

    /** Preview is capped at ~1440x1080 so it doesn't eat RAM next to the 15 MP capture stream. */
    private fun buildPreview() = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                )
                .build(),
        )
        .build()
        .also { it.surfaceProvider = previewView.surfaceProvider }

    private fun bindLens() {
        if (busy) return
        val provider = cameraProvider ?: return
        imageCapture = null; shutter.isEnabled = false
        val lens = if (wantTele) lenses.tele else lenses.main

        fun attempt(selector: CameraSelector, zoom: Float?): Boolean = try {
            val capture = buildImageCapture()
            provider.unbindAll()
            val cam = provider.bindToLifecycle(this, selector, buildPreview(), capture)
            if (zoom != null) {
                val zs = cam.cameraInfo.zoomState.value
                val z = zoom.coerceIn(zs?.minZoomRatio ?: 1f, zs?.maxZoomRatio ?: zoom)
                cam.cameraControl.setZoomRatio(z)
            }
            imageCapture = capture
            shutter.isEnabled = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "bind failed for $selector (zoom=$zoom)", e)
            false
        }

        if (attempt(CameraSelector.DEFAULT_BACK_CAMERA, lens.zoomRatio)) {
            val how = if (wantTele) "logical camera @ %.1fx zoom (HAL lens selection)".format(lens.zoomRatio)
            else "logical camera @ 1x"
            setStatus("${lens.label}: $how")
        } else {
            setStatus("Could not open the ${lens.label} lens, see logcat")
        }
    }

    // ---------------------------------------------------------------- Capture + enhance

    private fun capture() {
        val ic = imageCapture ?: return
        if (busy) return
        setBusy(true)
        setStatus("Capturing…")

        // JPEG goes straight to disk: no Bitmap, no byte[] of the full image in RAM.
        val file = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCaptured(file)
                override fun onError(exception: ImageCaptureException) {
                    setStatus("Capture failed: ${exception.message}")
                    file.delete(); setBusy(false)
                }
            },
        )
    }

    private fun onCaptured(file: File) {
        val tele = wantTele
        val m = mode
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) { pipeline(file, tele, m) }
            } catch (t: Throwable) {
                Log.e(TAG, "Pipeline failed", t)
                setStatus("Failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                file.delete()
                setBusy(false)
            }
        }
    }

    private fun pipeline(file: File, tele: Boolean, m: EnhanceMode) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val megapixels = bounds.outWidth.toLong() * bounds.outHeight / 1e6

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val base = "Opticore_${stamp}_${if (tele) "tele" else "main"}"

        // 1) Keep the untouched original immediately.
        check(MediaSaver.saveJpeg(this, file, base) != null) { "Original save failed" }
        if (m == EnhanceMode.ORIGINAL) { setStatus("Saved native ${bounds.outWidth}x${bounds.outHeight}"); return }
        setStatus("Original saved; loading optional AI…")
        OpticoreEnhancer(applicationContext).use { enh ->

        // 2) Enhance from the file, within the RAM budget. On OOM retry once with Clean 1x and half the budget.
        val outScale = if (m.fullRes && enh.scale % 2 == 0) 2 else 1
        var budget = MemoryBudget.maxOutputPixels(this)
        val progress: (Float) -> Unit = { p -> setStatus("Enhancing ${"%.1f".format(megapixels)} MP… ${(p * 100).toInt()}%") }

        val result = try {
            enh.enhanceFile(file, m.crop, outScale, budget, progress)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "OOM at budget $budget px, retrying at native crop scale and half the budget")
            budget /= 2
            enh.enhanceFile(file, m.crop, 1, budget, progress)
        }

        // 3) Encode, restore EXIF, publish.
        val w = result.width
        val h = result.height
        val outFile = File(cacheDir, "$base-sr.jpg")
        try {
            BufferedOutputStream(FileOutputStream(outFile), 256 * 1024).use {
                check(result.compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "JPEG encode failed" }
            }
            result.recycle()
            MediaSaver.copyExif(file, outFile, w, h)
            check(MediaSaver.saveJpeg(this, outFile, "${base}_SR") != null) { "Enhanced save failed" }
        } finally {
            if (!result.isRecycled) result.recycle()
            outFile.delete()
        }
        setStatus("Saved ${bounds.outWidth}x${bounds.outHeight} original + ${w}x$h enhanced (${m.label})")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }
}
