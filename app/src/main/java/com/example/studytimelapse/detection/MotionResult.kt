package com.example.studytimelapse.detection

/**
 * Result produced by a [MotionDetector].
 *
 * @param motionScore Normalised motion intensity in [0.0, 1.0].
 *                    0 = no motion, 1 = maximum detectable motion.
 * @param metadata    Optional extra data (e.g. bounding box of pen tip, velocity vector).
 *                    Kept as a generic map so each detector implementation can attach
 *                    whatever structured data the future character-movement feature needs
 *                    without changing the shared contract.
 */
data class MotionResult(
    val motionScore: Float,
    val metadata: Map<String, Any> = emptyMap(),
)
