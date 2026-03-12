package com.example.studytimelapse.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * Converts an [ImageProxy] to a [Bitmap], applying the reported rotation so the
 * result is display-upright.
 *
 * Delegates to the CameraX built-in [ImageProxy.toBitmap] which correctly handles
 * every YUV sub-format (I420, NV12, NV21) including non-trivial row / pixel strides.
 */
fun ImageProxy.toBitmapCompat(): Bitmap {
    val bitmap = toBitmap()
    val rotation = imageInfo.rotationDegrees
    return if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it != bitmap) bitmap.recycle() }
    } else {
        bitmap
    }
}
