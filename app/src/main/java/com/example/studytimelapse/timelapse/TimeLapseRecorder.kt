package com.example.studytimelapse.timelapse

import android.graphics.Bitmap
import android.util.Log
import com.example.studytimelapse.detection.MotionDetector
import com.example.studytimelapse.detection.MotionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Orchestrates the timelapse recording pipeline:
 *
 * ```
 *   camera frame
 *       │
 *       ▼
 *  [FrameSampler] ──no──▶ drop
 *       │ yes
 *       ▼
 *  [MotionDetector?] ──▶ MotionResult callback (future: move map character)
 *       │
 *       ▼
 *  [VideoEncoder] ──▶ MP4 file
 * ```
 *
 * All encoding work runs on a single-threaded coroutine dispatcher so that
 * the [VideoEncoder] (which is not thread-safe) is always called from the
 * same thread, while camera analysis callbacks are unblocked immediately.
 *
 * @param outputFile         Destination MP4 file.
 * @param frameWidth         Encoder output width.
 * @param frameHeight        Encoder output height.
 * @param captureIntervalMs  How often (ms) to pull a frame from the camera.
 * @param outputFps          Frame-rate of the output MP4 (controls playback speed).
 * @param motionDetector     Optional [MotionDetector]; pass null to skip analysis.
 * @param onMotionDetected   Callback with the latest [MotionResult] (called on the
 *                           encoding thread; post to main thread if updating UI).
 * @param onError            Callback when a fatal error occurs.
 */
class TimeLapseRecorder(
    private val outputFile: File,
    private val frameWidth: Int = 720,
    private val frameHeight: Int = 1280,
    captureIntervalMs: Long = 500L,
    outputFps: Int = 30,
    private val motionDetector: MotionDetector? = null,
    private val onMotionDetected: ((MotionResult) -> Unit)? = null,
    private val onError: ((Exception) -> Unit)? = null,
) {

    private val tag = "TimeLapseRecorder"

    private val sampler = FrameSampler(captureIntervalMs)
    private val encoder = VideoEncoder(outputFile, frameWidth, frameHeight, fps = outputFps)

    /** Dedicated single-thread scope for all encoding work. */
    private val encoderScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )

    private var recording = false
    private var frameCount = 0L

    // Presentation timestamp counter: incremented by one frame period per captured frame.
    private val frameDurationUs: Long = 1_000_000L / outputFps

    // For motion detection delta
    private var previousBitmap: Bitmap? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun start() {
        check(!recording) { "Already recording" }
        sampler.reset()
        frameCount = 0L
        previousBitmap = null
        encoder.start()
        recording = true
        Log.d(tag, "Recording started → ${outputFile.absolutePath}")
    }

    /**
     * Called for every camera frame (from the analysis executor thread).
     * Returns immediately; actual work is dispatched to [encoderScope].
     *
     * @param bitmap      Latest camera frame (caller retains ownership but must
     *                    not recycle until this method returns).
     * @param currentTimeMs Wall-clock time in milliseconds (System.currentTimeMillis).
     */
    fun onFrame(bitmap: Bitmap, currentTimeMs: Long) {
        if (!recording) return
        if (!sampler.shouldCapture(currentTimeMs)) return

        // Copy the bitmap so the caller can recycle the original safely.
        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        encoderScope.launch {
            try {
                processFrame(copy)
            } catch (e: Exception) {
                Log.e(tag, "Frame processing error", e)
                onError?.invoke(e)
            } finally {
                copy.recycle()
            }
        }
    }

    /**
     * Stop recording, flush the encoder, and write the final MP4.
     * Safe to call from any thread.
     */
    fun stop() {
        if (!recording) return
        recording = false

        encoderScope.launch {
            try {
                encoder.finish()
                Log.d(tag, "Recording stopped. Frames=$frameCount, file=${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "Error finishing encoder", e)
                onError?.invoke(e)
            } finally {
                previousBitmap?.recycle()
                previousBitmap = null
            }
        }
    }

    /** Release resources regardless of recording state. */
    fun release() {
        stop()
        encoderScope.cancel()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun processFrame(bitmap: Bitmap) {
        // Optional motion analysis
        motionDetector?.let { detector ->
            val result = detector.analyze(previousBitmap, bitmap)
            onMotionDetected?.invoke(result)
        }

        // Update previous frame reference for next diff
        previousBitmap?.recycle()
        previousBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        // Encode
        val presentationTimeUs = frameCount * frameDurationUs
        encoder.encodeFrame(bitmap, presentationTimeUs)
        frameCount++
    }
}
