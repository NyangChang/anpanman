package com.example.studytimelapse.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.studytimelapse.detection.PenMotionDetector
import com.example.studytimelapse.map.CatRepository
import com.example.studytimelapse.timelapse.TimeLapseRecorder
import com.example.studytimelapse.util.FileUtil
import java.util.concurrent.atomic.AtomicInteger

/**
 * ViewModel for the main recording screen.
 *
 * Owns the [TimeLapseRecorder] lifecycle and exposes [recordingState] for the
 * Activity to observe.  The ViewModel survives configuration changes; the
 * [TimeLapseRecorder] continues running if the screen rotates while recording.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "MainViewModel"

    // -------------------------------------------------------------------------
    // Observable state
    // -------------------------------------------------------------------------

    private val _recordingState = MutableLiveData<RecordingState>(RecordingState.Idle)
    val recordingState: LiveData<RecordingState> = _recordingState

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private var recorder: TimeLapseRecorder? = null
    private val capturedFrameCount = AtomicInteger(0)
    private var recordingStartMs = 0L

    /** Elapsed-time ticker (runs on main thread, posts every second). */
    private val elapsedHandler = Handler(Looper.getMainLooper())
    private val elapsedRunnable = object : Runnable {
        override fun run() {
            val current = _recordingState.value
            if (current is RecordingState.Recording) {
                val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000L
                _recordingState.value = current.copy(elapsedSeconds = elapsed)
            }
            elapsedHandler.postDelayed(this, 1_000L)
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun startRecording() {
        if (_recordingState.value is RecordingState.Recording) return

        val context = getApplication<Application>()
        val outputFile = FileUtil.newTimeLapseFile(context)

        capturedFrameCount.set(0)
        recordingStartMs = System.currentTimeMillis()

        recorder = TimeLapseRecorder(
            outputFile = outputFile,
            captureIntervalMs = 500L,    // one timelapse frame every 500 ms real time
            outputFps = 30,              // plays back at 30 fps → ~15× speed-up
            motionDetector = PenMotionDetector(),
            onMotionDetected = { result ->
                // Move the cat based on detected writing motion
                CatRepository.onMotion(result.motionScore)

                val current = _recordingState.value
                if (current is RecordingState.Recording) {
                    // Post to main thread because LiveData observers run there
                    elapsedHandler.post {
                        _recordingState.value =
                            current.copy(motionScore = result.motionScore)
                    }
                }
            },
            onFrameCaptured = {
                // Called on the encoding thread each time a timelapse frame is actually encoded
                val newCount = capturedFrameCount.incrementAndGet()
                elapsedHandler.post {
                    val latest = _recordingState.value
                    if (latest is RecordingState.Recording) {
                        _recordingState.value = latest.copy(frameCount = newCount)
                    }
                }
            },
            onFinished = { file, frames ->
                FileUtil.addToMediaStore(getApplication(), file)
                elapsedHandler.post {
                    _recordingState.value = RecordingState.Saved(file, frames.toInt())
                }
                Log.d(tag, "Saved → ${file.absolutePath}, frames=$frames")
            },
            onError = { e ->
                Log.e(tag, "Recorder error", e)
                elapsedHandler.post {
                    _recordingState.value = RecordingState.Error(e.message ?: "Unknown error")
                }
            },
        )

        recorder!!.start()
        _recordingState.value = RecordingState.Recording()
        elapsedHandler.postDelayed(elapsedRunnable, 1_000L)

        Log.d(tag, "Recording started → $outputFile")
    }

    fun stopRecording() {
        val rec = recorder ?: return
        elapsedHandler.removeCallbacks(elapsedRunnable)

        val totalFrames = capturedFrameCount.get()
        rec.stop()
        recorder = null
        Log.d(tag, "Recording stopping, frames=$totalFrames")
    }

    /**
     * Called by [MainActivity] for every camera frame received from CameraX.
     * Forwards to the active [TimeLapseRecorder] (if recording).
     */
    fun onCameraFrame(bitmap: Bitmap, timestampMs: Long) {
        recorder?.onFrame(bitmap, timestampMs)
    }

    // -------------------------------------------------------------------------
    // ViewModel cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        elapsedHandler.removeCallbacksAndMessages(null)
        recorder?.release()
        recorder = null
    }
}
