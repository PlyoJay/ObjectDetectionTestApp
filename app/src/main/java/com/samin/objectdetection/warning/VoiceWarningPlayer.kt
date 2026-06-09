package com.samin.objectdetection.warning

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceWarningPlayer(
    context: Context,
    private val cooldownMs: Long = 3500L
) : WarningPlayer, TextToSpeech.OnInitListener {

    private val textToSpeech = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var lastSpokenAtMs = 0L

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val result = textToSpeech.setLanguage(Locale.KOREAN)
            if (
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "Korean TTS language is not available")
                ready = false
            }
        } else {
            Log.w(TAG, "TextToSpeech init failed: $status")
        }
    }

    override fun playIfNeeded(decision: WarningDecision) {
        if (decision.voiceLevel == FeedbackLevel.NONE) return
        if (!ready) return

        val now = System.currentTimeMillis()
        if (now - lastSpokenAtMs < cooldownMs) return

        textToSpeech.speak(
            decision.message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "warning-${now}"
        )
        lastSpokenAtMs = now
    }

    override fun close() {
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    companion object {
        private const val TAG = "VoiceWarningPlayer"
    }
}
