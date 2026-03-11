package com.example.studytimelapse.data.model

import android.graphics.Bitmap

/**
 * A single captured frame from the camera.
 *
 * @param bitmap     The image data.
 * @param timestampUs Presentation timestamp in microseconds (monotonic).
 */
data class Frame(
    val bitmap: Bitmap,
    val timestampUs: Long,
)
