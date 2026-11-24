package com.opticore.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File

object MediaSaver {

    /** Copies [file] into DCIM/Opticore through MediaStore (streamed, no bitmap in RAM). */
    fun saveJpeg(context: Context, file: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Opticore")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: error("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    private val COPY_TAGS = arrayOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_ORIENTATION,   // the enhanced file is NOT rotated, so it needs the same flag
    )

    /**
     * The enhanced JPEG comes from Bitmap.compress and has no EXIF. Copy the useful tags over
     * (most importantly the orientation) and fix the pixel dimensions. Streams on disk, no big RAM use.
     */
    fun copyExif(src: File, dst: File, newWidth: Int, newHeight: Int) {
        val s = ExifInterface(src.absolutePath)
        val d = ExifInterface(dst.absolutePath)
        for (tag in COPY_TAGS) s.getAttribute(tag)?.let { d.setAttribute(tag, it) }
        d.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, newWidth.toString())
        d.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, newHeight.toString())
        d.setAttribute(ExifInterface.TAG_SOFTWARE, "Opticore + QuickSRNetLarge")
        d.saveAttributes()
    }
}
