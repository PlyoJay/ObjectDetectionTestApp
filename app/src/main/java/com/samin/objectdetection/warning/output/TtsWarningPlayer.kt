package com.samin.objectdetection.warning.output

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.WarningCandidate
import java.util.Locale

class TtsWarningPlayer(
    context: Context
) : WarningPlayer {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var isReady = false
    @Volatile
    private var isReleased = false
    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS && !isReleased) {
                    val result = textToSpeech.setLanguage(Locale.KOREAN)
                    isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    textToSpeech.setSpeechRate(SPEECH_RATE)
                    textToSpeech.setPitch(SPEECH_PITCH)
                    Log.d(TAG, "TextToSpeech ready=$isReady, languageResult=$result")
                } else {
                    isReady = false
                    Log.w(TAG, "TextToSpeech init failed: status=$status")
                }
            }
        }
    }

    override fun playIfNeeded(candidate: WarningCandidate) {
        val message = candidate.feedback.message ?: return
        speak(candidate, message)
    }

    fun speak(candidate: WarningCandidate, message: String): TtsResult {
        if (candidate.feedback.voiceLevel == FeedbackLevel.NONE) {
            return TtsResult(executed = false, skippedReason = "voice_none")
        }
        if (message.isBlank()) {
            return TtsResult(executed = false, skippedReason = "message_blank")
        }
        if (isReleased) {
            return TtsResult(executed = false, skippedReason = "tts_released")
        }
        if (!isReady) {
            Log.d(TAG, "skip TTS: not ready")
            return TtsResult(executed = false, skippedReason = "tts_not_ready")
        }

        return try {
            val utteranceId = "${candidate.warningKey}:${System.currentTimeMillis()}"
            val result = textToSpeech.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                Bundle.EMPTY,
                utteranceId
            )
            if (result == TextToSpeech.ERROR) {
                Log.w(TAG, "TTS speak returned ERROR")
                TtsResult(executed = false, skippedReason = "tts_error")
            } else {
                Log.d(TAG, "speak TTS: key=${candidate.warningKey}, message=$message")
                TtsResult(executed = true, skippedReason = null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed", e)
            TtsResult(executed = false, skippedReason = "tts_exception")
        }
    }

    override fun release() {
        mainHandler.post {
            isReleased = true
            isReady = false
            try {
                textToSpeech.stop()
                textToSpeech.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "TTS release failed", e)
            }
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    data class TtsResult(
        val executed: Boolean,
        val skippedReason: String?
    )

    companion object {
        private const val TAG = "GotoroTts"
        private const val SPEECH_RATE = 1.15f
        private const val SPEECH_PITCH = 1.0f
    }
}
