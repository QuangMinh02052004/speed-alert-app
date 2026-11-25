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

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _vehicle.value = runCatching {
            VehicleType.valueOf(prefs.getString("vehicle", VehicleType.MOTORBIKE.name)!!)
        }.getOrDefault(VehicleType.MOTORBIKE)
        _voiceEnabled.value = prefs.getBoolean("voice", true)
        loaded = true
    }

    fun setVehicle(context: Context, type: VehicleType) {
        _vehicle.value = type
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("vehicle", type.name).apply()
    }

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        _voiceEnabled.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("voice", enabled).apply()
    }
}
