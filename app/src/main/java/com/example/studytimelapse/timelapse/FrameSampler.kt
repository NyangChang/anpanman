package com.example.studytimelapse.timelapse

/**
 * Decides whether the current frame should be included in the timelapse.
 *
 * The simplest policy is time-based: accept a frame only if at least
 * [captureIntervalMs] milliseconds have elapsed since the last accepted frame.
 * This gives a timelapse speed-up factor of:
 *
 *   speedUp = captureIntervalMs / (1000 / outputFps)
 *
 * Example: captureIntervalMs=500, outputFps=30  →  15× speed-up.
 *
 * The class is intentionally stateless between sessions; call [reset] when a
 * new recording starts so the very first frame is always captured.
 *
 * @param captureIntervalMs Minimum real-world time between captured frames (ms).
 */
class FrameSampler(val captureIntervalMs: Long = 500L) {

    private var lastCapturedTimeMs: Long = Long.MIN_VALUE

    /**
     * Returns true if the frame at [currentTimeMs] should be captured.
     * Updates internal state when true is returned.
     */
    fun shouldCapture(currentTimeMs: Long): Boolean {
        return if (currentTimeMs - lastCapturedTimeMs >= captureIntervalMs) {
            lastCapturedTimeMs = currentTimeMs
            true
        } else {
            false
        }
    }

    /** Reset so the very next call to [shouldCapture] returns true. */
    fun reset() {
        lastCapturedTimeMs = Long.MIN_VALUE
    }
}
