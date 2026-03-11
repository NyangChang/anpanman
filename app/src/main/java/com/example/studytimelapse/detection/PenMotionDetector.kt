package com.example.studytimelapse.detection

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Stub implementation of [MotionDetector] using simple pixel-diff heuristics.
 *
 * Replace the body of [analyze] with a proper pen-tip tracking algorithm
 * (colour segmentation, contour detection, or an ML model) when the
 * pen-movement feature is developed.
 *
 * The current implementation computes the mean absolute difference of
 * down-sampled greyscale pixels, which is fast enough for real-time use
 * and serves as a placeholder that already wires up the full data flow.
 */
class PenMotionDetector : MotionDetector {

    // Down-sample resolution to keep the diff fast on the main analysis thread.
    private val sampleWidth = 64
    private val sampleHeight = 48

    override fun analyze(previousFrame: Bitmap?, currentFrame: Bitmap): MotionResult {
        if (previousFrame == null) return MotionResult(motionScore = 0f)

        val prev = Bitmap.createScaledBitmap(previousFrame, sampleWidth, sampleHeight, false)
        val curr = Bitmap.createScaledBitmap(currentFrame, sampleWidth, sampleHeight, false)

        var sumSqDiff = 0.0
        val totalPixels = sampleWidth * sampleHeight

        for (y in 0 until sampleHeight) {
            for (x in 0 until sampleWidth) {
                val p = prev.getPixel(x, y)
                val c = curr.getPixel(x, y)

                val dr = ((p shr 16 and 0xFF) - (c shr 16 and 0xFF)).toDouble()
                val dg = ((p shr 8 and 0xFF) - (c shr 8 and 0xFF)).toDouble()
                val db = ((p and 0xFF) - (c and 0xFF)).toDouble()

                sumSqDiff += (dr * dr + dg * dg + db * db) / 3.0
            }
        }

        // Normalise: max possible squared diff per channel is 255^2 = 65025
        val normScore = (sqrt(sumSqDiff / totalPixels) / 255.0).toFloat().coerceIn(0f, 1f)

        prev.recycle()
        curr.recycle()

        return MotionResult(
            motionScore = normScore,
            metadata = mapOf("algorithm" to "pixel_diff_rms"),
        )
    }
}
