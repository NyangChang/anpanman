package com.example.studytimelapse.map

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Singleton that holds the cat's current position and step count.
 *
 * Motion scores from [PenMotionDetector] are fed via [onMotion]; once enough
 * motion accumulates the cat advances one step north (~11 m).
 */
object CatRepository {

    /** Default start: Shibuya, Tokyo */
    private const val DEFAULT_LAT = 35.6580
    private const val DEFAULT_LNG = 139.7016

    /** Degrees of latitude per step (≈ 11 m). */
    private const val STEP_LAT = 0.0001

    /** Sum of motionScore values needed to earn one step. */
    private const val MOTION_PER_STEP = 3f

    data class CatPosition(val lat: Double, val lng: Double)

    private val _position = MutableLiveData(CatPosition(DEFAULT_LAT, DEFAULT_LNG))
    val position: LiveData<CatPosition> = _position

    private val _stepCount = MutableLiveData(0)
    val stepCount: LiveData<Int> = _stepCount

    private var motionAccum = 0f

    /**
     * Feed one motion score (0..1) from the detector.
     * Ignored if score is below noise floor.
     */
    fun onMotion(score: Float) {
        if (score < 0.02f) return
        motionAccum += score
        while (motionAccum >= MOTION_PER_STEP) {
            motionAccum -= MOTION_PER_STEP
            advance()
        }
    }

    private fun advance() {
        val cur = _position.value ?: CatPosition(DEFAULT_LAT, DEFAULT_LNG)
        _position.postValue(CatPosition(cur.lat + STEP_LAT, cur.lng))
        _stepCount.postValue((_stepCount.value ?: 0) + 1)
    }

    fun reset() {
        motionAccum = 0f
        _position.postValue(CatPosition(DEFAULT_LAT, DEFAULT_LNG))
        _stepCount.postValue(0)
    }
}
