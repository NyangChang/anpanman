package com.example.studytimelapse.detection

import android.graphics.Bitmap

/**
 * Contract for frame-based motion / pen detection.
 *
 * Implement this interface to plug in different analysis strategies
 * (frame-diff, optical flow, ML-based pen detection, etc.) without
 * touching the recording or timelapse pipeline.
 *
 * Future use: the [MotionResult.motionScore] drives character movement on Google Maps.
 */
interface MotionDetector {

    /**
     * Analyse the transition from [previousFrame] to [currentFrame].
     *
     * @param previousFrame The frame immediately before [currentFrame], or null on the
     *                      very first call.
     * @param currentFrame  The latest captured frame.
     * @return              A [MotionResult] summarising detected motion.
     */
    fun analyze(previousFrame: Bitmap?, currentFrame: Bitmap): MotionResult
}
