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

    // 0L means "never captured"; System.currentTimeMillis() >> captureIntervalMs,
    // so the first shouldCapture() call after reset() always returns true without overflow.
    // Long.MIN_VALUE must NOT be used here: currentTimeMs - Long.MIN_VALUE overflows
    // to a large negative value, making the comparison always false.
    private var lastCapturedTimeMs: Long = 0L

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
        lastCapturedTimeMs = 0L
    }
}
