package com.counterpick.mlbb.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single-process pub/sub between ScreenCaptureService (producer) and OverlayService
 * (consumer). Both run in the same process, so a StateFlow is simpler and cheaper
 * than a bound-service/AIDL round trip.
 */
object DraftEventBus {
    private val _recommendations = MutableStateFlow<List<HeroRecommendation>>(emptyList())
    val recommendations: StateFlow<List<HeroRecommendation>> = _recommendations

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    fun publish(recs: List<HeroRecommendation>) {
        _recommendations.value = recs
    }

    fun setCapturing(active: Boolean) {
        _isCapturing.value = active
        if (!active) _recommendations.value = emptyList()
    }
}
