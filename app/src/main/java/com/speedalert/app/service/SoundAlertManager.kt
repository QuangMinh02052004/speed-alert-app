package com.speedalert.app.service

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Phát âm thanh cảnh báo bằng ToneGenerator có sẵn trong Android SDK - không
 * cần file âm thanh đính kèm, không cần thêm quyền gì. Có cooldown để không
 * kêu liên tục mỗi lần cập nhật GPS (1-2 giây/lần).
 */
class SoundAlertManager {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    private var lastAlertAtMs = 0L
    private val cooldownMs = 4000L

    fun alertIfNeeded(shouldAlert: Boolean) {
        if (!shouldAlert) return
        val now = System.currentTimeMillis()
        if (now - lastAlertAtMs < cooldownMs) return
        lastAlertAtMs = now
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
    }

    fun release() {
        toneGenerator.release()
    }
}
