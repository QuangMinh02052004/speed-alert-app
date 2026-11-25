package com.speedalert.app.service

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Trạng thái dùng chung giữa Service (đọc GPS, khớp đường, cảnh báo) và các
 * màn hình (tốc độ, bản đồ, camera hành trình).
 */
object SpeedState {
    data class State(
        val currentSpeedKmh: Int = 0,
        val speedLimitKmh: Int? = null,
        /** true = giới hạn lấy từ biển báo có trong dữ liệu, false = suy theo luật. */
        val limitFromSign: Boolean = false,
        val roadName: String = "",
        val roadNote: String = "",
        val lastLat: Double? = null,
        val lastLon: Double? = null,
        val bearing: Float? = null,
        val accuracyM: Float? = null,
        val nearCamera: Boolean = false,
        val cameraDistanceM: Int? = null,
        val overLimit: Boolean = false,
        val dataStatus: String = "Đang chờ tín hiệu GPS...",
        val gpsWeak: Boolean = false,
        /** Vị trí gốc mới nhất, để bản đồ vẽ chấm định vị (có hướng + vòng sai số). */
        val location: Location? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    fun set(newState: State) {
        _state.value = newState
    }

    fun setStatus(status: String) {
        _state.value = _state.value.copy(dataStatus = status)
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }
}
