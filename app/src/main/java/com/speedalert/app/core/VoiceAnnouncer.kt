package com.speedalert.app.core

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Đọc thông báo bằng giọng tiếng Việt (TextToSpeech của máy). Nếu máy chưa có
 * giọng tiếng Việt thì [available] = false và Service sẽ dùng tiếng bíp thay thế.
 * Có thể cài giọng: Cài đặt > Hệ thống > Ngôn ngữ > Chuyển văn bản thành giọng nói.
 */
class VoiceAnnouncer(context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile var available = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(Locale("vi", "VN"))
                available = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setSpeechRate(1.05f)
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                Log.d("Voice", "TTS ready, vi-VN available=$available")
            }
        }
    }

    /** urgent = true thì cắt câu đang đọc để đọc ngay. */
    fun speak(text: String, urgent: Boolean = false) {
        if (!available) return
        tts?.speak(
            text,
            if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "msg_${System.nanoTime()}"
        )
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
