package com.example.studytimelapse.ui

import java.io.File

/**
 * Represents the complete UI state of the recording screen.
 *
 * Using a sealed class (rather than individual LiveData fields) makes it easy
 * to add new states (e.g. "processing", "error") without touching observers.
 */
sealed class RecordingState {

    /** Camera is ready; user has not started recording yet. */
    object Idle : RecordingState()

    /**
     * Actively recording.
     *
     * @param frameCount      Number of timelapse frames captured so far.
     * @param motionScore     Latest motion score [0, 1] (0 if detector not attached).
     * @param elapsedSeconds  Wall-clock seconds since recording began.
     */
    data class Recording(
        val frameCount: Int = 0,
        val motionScore: Float = 0f,
        val elapsedSeconds: Long = 0L,
    ) : RecordingState()

    /**
     * Recording has finished and the file was saved successfully.
     *
     * @param outputFile  The saved MP4 file.
     * @param frameCount  Total number of timelapse frames.
     */
    data class Saved(
        val outputFile: File,
        val frameCount: Int,
    ) : RecordingState()

    /**
     * A non-recoverable error occurred.
     *
     * @param message Human-readable description.
     */
    data class Error(val message: String) : RecordingState()
}
