package com.speedalert.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Nơi lưu trạng thái hiện tại dùng chung giữa Service (chạy nền, đọc GPS) và
 * UI (Compose/MapActivity). Đơn giản hoá bằng singleton object cho MVP.
 */
object SpeedState {
    data class State(
        val currentSpeedKmh: Int = 0,
        val speedLimitKmh: Int? = null,
        val roadName: String = "",
        val lastLat: Double? = null,
        val lastLon: Double? = null,
        val nearCamera: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(
        currentSpeedKmh: Int,
        speedLimitKmh: Int?,
        roadName: String,
        lat: Double,
        lon: Double,
        nearCamera: Boolean
    ) {
        _state.value = State(currentSpeedKmh, speedLimitKmh, roadName, lat, lon, nearCamera)
    }
}
