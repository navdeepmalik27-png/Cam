package com.opticore.camera

import android.app.ActivityManager
import android.content.Context

/**
 * One place that decides how much RAM the enhancement output may use.
 *
 * Default: 1 GB for the whole app. Everything that is NOT the output bitmap is reserved first,
 * then only part of what is left is given to the output bitmap (headroom for the JPEG encoder,
 * GC slack and native fragmentation). The result is also capped by what Android reports as free
 * right now, so the app backs off when other apps are using memory.
 *
 * The reserves are estimates, not measurements. Check your real numbers with
 * Android Studio Profiler (or `adb shell dumpsys meminfo com.opticore.camera`) and adjust.
 */
object MemoryBudget {
    private const val MB = 1024L * 1024L

    const val APP_BUDGET_BYTES = 1024L * MB

    private const val RESERVE_CAMERA_PREVIEW = 200L * MB   // CameraX, preview surfaces, JPEG ImageReader buffers
    private const val RESERVE_RUNTIME_UI = 200L * MB       // ART heap, views, native libs
    private const val RESERVE_MODEL_TILES = 128L * MB      // LiteRT/NPU buffers, tile float arrays, decoded strip
    private const val OUTPUT_FRACTION = 0.65               // share of the remainder the output bitmap may take
    private const val FREE_RAM_FRACTION = 0.6              // never plan on more than 60% of currently free RAM
    private const val MIN_OUTPUT_BYTES = 32L * MB

    fun maxOutputBytes(context: Context, appBudgetBytes: Long = APP_BUDGET_BYTES): Long {
        val fixed = RESERVE_CAMERA_PREVIEW + RESERVE_RUNTIME_UI + RESERVE_MODEL_TILES
        val byBudget = ((appBudgetBytes - fixed) * OUTPUT_FRACTION).toLong()

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val byFreeRam = (info.availMem * FREE_RAM_FRACTION).toLong()

        return minOf(byBudget, byFreeRam, 96L * MB).coerceAtLeast(0L)
    }

    /** Max pixels of the output bitmap (ARGB_8888 = 4 bytes per pixel). */
    fun maxOutputPixels(context: Context, appBudgetBytes: Long = APP_BUDGET_BYTES): Long =
        maxOutputBytes(context, appBudgetBytes) / 4
}
