package com.speedalert.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VehicleType(val label: String) {
    MOTORBIKE("Xe máy (mô tô)"),
    MOPED("Xe gắn máy ≤50cc"),
    CAR("Ô tô con")
}

/** Cài đặt người dùng, lưu SharedPreferences, phát ra StateFlow để UI/Service cùng thấy. */
object AppSettings {
    private const val PREFS = "speed_alert_settings"

    private val _vehicle = MutableStateFlow(VehicleType.MOTORBIKE)
    val vehicle: StateFlow<VehicleType> = _vehicle

    private val _voiceEnabled = MutableStateFlow(true)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled

    /** % cộng thêm vào tốc độ hiển thị cho khớp đồng hồ xe (0 = tốc độ thật GPS). */
    private val _speedCalibrationPercent = MutableStateFlow(0)
    val speedCalibrationPercent: StateFlow<Int> = _speedCalibrationPercent

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Theo yêu cầu: app chỉ dành cho xe máy (Honda, Yamaha... mô tô trên 50cc).
        _vehicle.value = VehicleType.MOTORBIKE
        _voiceEnabled.value = prefs.getBoolean("voice", true)
        _speedCalibrationPercent.value = prefs.getInt("speed_cal", 0).coerceIn(0, 15)
        loaded = true
    }

    fun setVehicle(context: Context, type: VehicleType) {
        _vehicle.value = type
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("vehicle", type.name).apply()
    }

    fun setSpeedCalibration(context: Context, percent: Int) {
        val p = percent.coerceIn(0, 15)
        _speedCalibrationPercent.value = p
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("speed_cal", p).apply()
    }

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        _voiceEnabled.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("voice", enabled).apply()
    }
}
