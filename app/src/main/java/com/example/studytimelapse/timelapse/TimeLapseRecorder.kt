package com.example.studytimelapse.timelapse

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import com.example.studytimelapse.detection.MotionDetector
import com.example.studytimelapse.detection.MotionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

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
 *       │
 *       ▼  (if targetOutputDurationSec > 0)
 *  [remux with scaled timestamps] ──▶ fixed-duration MP4
 * ```
 *
 * All encoding work runs on a single-threaded coroutine dispatcher so that
 * the [VideoEncoder] (which is not thread-safe) is always called from the
 * same thread, while camera analysis callbacks are unblocked immediately.
 *
 * @param outputFile              Destination MP4 file.
 * @param frameWidth              Encoder output width.
 * @param frameHeight             Encoder output height.
 * @param captureIntervalMs       How often (ms) to pull a frame from the camera.
 * @param outputFps               Frame-rate of the output MP4 (controls playback speed).
 * @param targetOutputDurationSec Desired output video length in seconds.  After encoding
 *                                finishes the file is remuxed with scaled timestamps so
 *                                the total playback duration equals this value regardless
 *                                of how long the recording was.  Pass 0 to disable.
 * @param motionDetector          Optional [MotionDetector]; pass null to skip analysis.
 * @param onMotionDetected        Callback with the latest [MotionResult] (called on the
 *                                encoding thread; post to main thread if updating UI).
 * @param onFinished              Called on the encoding thread when the MP4 has been fully
 *                                written.  Arguments are the output [File] and total frame count.
 * @param onError                 Callback when a fatal error occurs.
 */
class TimeLapseRecorder(
    private val outputFile: File,
    private val frameWidth: Int = 720,
    private val frameHeight: Int = 1280,
    captureIntervalMs: Long = 500L,
    private val outputFps: Int = 30,
    private val targetOutputDurationSec: Int = 60,
    private val motionDetector: MotionDetector? = null,
    private val onMotionDetected: ((MotionResult) -> Unit)? = null,
    private val onFinished: ((File, Long) -> Unit)? = null,
    private val onError: ((Exception) -> Unit)? = null,
) {

    private val tag = "TimeLapseRecorder"

    private val sampler = FrameSampler(captureIntervalMs)
    private val encoder = VideoEncoder(outputFile, frameWidth, frameHeight, fps = outputFps)

    /** Single-threaded scope for all encoding work (VideoEncoder is not thread-safe). */
    private val encoderScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )

    private var recording = false

    // Written only on the encoderScope thread; read inside the same scope after finish().
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

                // frameCount is its final value here: all processFrame coroutines
                // ran before this one (limitedParallelism(1) guarantees FIFO order).
                val finalFrameCount = frameCount

                if (targetOutputDurationSec > 0 && finalFrameCount > 0 && outputFile.length() > 0) {
                    remuxToTargetDuration(
                        file = outputFile,
                        targetDurationUs = targetOutputDurationSec * 1_000_000L,
                        totalFrames = finalFrameCount,
                    )
                }

                Log.d(tag, "Recording stopped. frames=$finalFrameCount, file=${outputFile.absolutePath}")
                onFinished?.invoke(outputFile, finalFrameCount)
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

    /**
     * Remux [file] so its total playback duration equals [targetDurationUs].
     *
     * The encoded video data (H.264 bitstream) is copied byte-for-byte without
     * re-encoding; only the presentation timestamps are scaled uniformly.
     * The original file is replaced atomically on success; on failure the
     * original file is preserved unchanged.
     */
    private fun remuxToTargetDuration(file: File, targetDurationUs: Long, totalFrames: Long) {
        val actualDurationUs = totalFrames * frameDurationUs
        if (actualDurationUs <= 0) return

        val scale = targetDurationUs.toDouble() / actualDurationUs
        val tempFile = File(file.parent, "${file.nameWithoutExtension}_tmp.mp4")

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val trackIndices = (0 until extractor.trackCount).map { i ->
                    extractor.selectTrack(i)
                    muxer.addTrack(extractor.getTrackFormat(i))
                }
                muxer.start()

                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                val bufferInfo = MediaCodec.BufferInfo()

                while (true) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    if (bufferInfo.size < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.presentationTimeUs = (extractor.sampleTime * scale).toLong()
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(
                        trackIndices[extractor.sampleTrackIndex], buffer, bufferInfo
                    )
                    extractor.advance()
                }
                muxer.stop()
                Log.d(tag, "Remux done: ${totalFrames}f × ${scale}x → ${targetDurationUs / 1_000_000}s")
            } finally {
                muxer.release()
            }

            // Replace original file with remuxed version
            file.delete()
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file)
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(tag, "Remux failed, keeping original file", e)
            tempFile.delete()
        } finally {
            extractor.release()
        }
    }
}
