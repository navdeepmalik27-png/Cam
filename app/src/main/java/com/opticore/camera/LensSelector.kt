package com.opticore.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Works out which back lens is "main" and which is "tele" on a multi-camera phone (OnePlus 13).
 *
 * Idea: compute each physical camera's 35mm-equivalent focal length from Camera2 characteristics.
 *   ultrawide ~ 14-16 mm, main ~ 23-26 mm, tele (3x periscope) ~ 70-75 mm.
 *
 * Some OEMs hide physical camera characteristics from third-party apps. In that case [Lens.physicalId]
 * is null and the tele lens falls back to "logical back camera at ~3x zoom", where the camera HAL
 * decides which sensor to use.
 */
object LensSelector {
    private const val TAG = "LensSelector"
    private const val FULL_FRAME_DIAGONAL_MM = 43.27f
    private const val DEFAULT_TELE_RATIO = 3.0f   // OnePlus 13 periscope is 3x

    data class Lens(
        val label: String,
        /** Physical camera id to route the stream to, or null = use the default back (logical) camera. */
        val physicalId: String?,
        /** Zoom ratio to apply on the logical camera when no physical routing is used / possible. */
        val zoomRatio: Float,
        val equivFocalMm: Float?,
    )

    data class Lenses(val main: Lens, val tele: Lens, val debug: String)

    fun find(context: Context): Lenses {
        val log = StringBuilder()
        val cm = context.getSystemService(CameraManager::class.java)

        val backIds = cm.cameraIdList.filter {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val logicalId = backIds.firstOrNull {
            cm.getCameraCharacteristics(it).physicalCameraIds.isNotEmpty()
        } ?: backIds.firstOrNull()

        log.appendLine("back ids=$backIds logical=$logicalId")

        val physical = mutableListOf<Pair<String, Float>>()  // id -> equivalent focal length (mm)
        if (logicalId != null) {
            for (pid in cm.getCameraCharacteristics(logicalId).physicalCameraIds) {
                val eq = runCatching { equivFocalMm(cm.getCameraCharacteristics(pid)) }.getOrNull()
                log.appendLine("physical $pid -> ${eq?.let { "%.1f mm".format(it) } ?: "characteristics hidden"}")
                if (eq != null) physical += pid to eq
            }
        }

        // Main: closest to 24 mm within a sane window. Tele: longest lens, at least ~2x the main.
        val mainCand = physical.filter { it.second in 18f..40f }.minByOrNull { abs(it.second - 24f) }
        val teleCand = physical.maxByOrNull { it.second }
            ?.takeIf { cand -> cand.second >= 50f || (mainCand != null && cand.second >= mainCand.second * 2f) }

        val teleRatio = if (mainCand != null && teleCand != null) teleCand.second / mainCand.second
        else DEFAULT_TELE_RATIO

        val main = Lens("Main", physicalId = null, zoomRatio = 1.0f, equivFocalMm = mainCand?.second)
        val tele = Lens("Tele", physicalId = teleCand?.first, zoomRatio = teleRatio, equivFocalMm = teleCand?.second)

        log.appendLine("main=$main")
        log.appendLine("tele=$tele")
        Log.i(TAG, log.toString())
        return Lenses(main, tele, log.toString())
    }

    private fun equivFocalMm(c: CameraCharacteristics): Float? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = hypot(size.width, size.height)
        if (diag <= 0f) return null
        return f * FULL_FRAME_DIAGONAL_MM / diag
    }
}
