package com.samin.objectdetection.warning.output

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.samin.objectdetection.metrics.FrameLatencyRecorder
import com.samin.objectdetection.metrics.TtsStartTracker
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
    @Volatile
    private var lastMessage: String? = null
    @Volatile
    private var lastSpeakAtMs: Long = 0L
    private lateinit var textToSpeech: TextToSpeech
    private val startTracker = TtsStartTracker(SystemClock::elapsedRealtimeNanos)

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS && !isReleased) {
                    val result = textToSpeech.setLanguage(Locale.KOREAN)
                    isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    textToSpeech.setSpeechRate(SPEECH_RATE)
                    textToSpeech.setPitch(SPEECH_PITCH)
                    textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = startTracker.onStart(utteranceId)
                        override fun onDone(utteranceId: String) = startTracker.terminal(utteranceId, "DONE_WITHOUT_START")
                        @Deprecated("Legacy TTS callback")
                        override fun onError(utteranceId: String) = startTracker.terminal(utteranceId, "TTS_ERROR")
                        override fun onError(utteranceId: String, errorCode: Int) = startTracker.terminal(utteranceId, "TTS_ERROR")
                        override fun onStop(utteranceId: String, interrupted: Boolean) = startTracker.terminal(utteranceId, "TTS_STOPPED_OR_FLUSHED")
                    })
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

    fun speak(candidate: WarningCandidate, message: String, force: Boolean = false,
        latency: FrameLatencyRecorder? = null): TtsResult {
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
        val nowMs = System.currentTimeMillis()
        if (!force && lastMessage == message && nowMs - lastSpeakAtMs < DUPLICATE_SUPPRESS_MS) {
            Log.d(TAG, "skip TTS: duplicate message=$message")
            return TtsResult(executed = false, skippedReason = "tts_duplicate")
        }

        val utteranceId = if (latency == null) "${candidate.warningKey}:$nowMs" else
            "${candidate.warningKey}:$nowMs:${latency.trace.sessionId}:${latency.trace.frameId}"
        val request = latency?.let { startTracker.register(utteranceId, it) }
        return try {
            val result = textToSpeech.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                Bundle.EMPTY,
                utteranceId
            )
            request?.let { startTracker.requestReturned(it, result != TextToSpeech.ERROR) }
            if (result == TextToSpeech.ERROR) {
                Log.w(TAG, "TTS speak returned ERROR")
                TtsResult(executed = false, skippedReason = "tts_error")
            } else {
                lastMessage = message
                lastSpeakAtMs = nowMs
                Log.d(TAG, "speak TTS: key=${candidate.warningKey}, message=$message")
                TtsResult(executed = true, skippedReason = null)
            }
        } catch (e: Exception) {
            request?.let { startTracker.requestReturned(it, null, "API_EXCEPTION") }
            Log.w(TAG, "TTS speak failed", e)
            TtsResult(executed = false, skippedReason = "tts_exception")
        }
    }

    fun stop() {
        if (isReleased || !isReady) return
        try {
            textToSpeech.stop()
            startTracker.clear("STOP_WITHOUT_START")
        } catch (e: Exception) {
            Log.w(TAG, "TTS stop failed", e)
        }
    }

    fun isSpeaking(): Boolean {
        return !isReleased && isReady && textToSpeech.isSpeaking
    }

    fun isReady(): Boolean {
        return !isReleased && isReady
    }

    override fun release() {
        mainHandler.post {
            isReleased = true
            isReady = false
            try {
                textToSpeech.stop()
                textToSpeech.shutdown()
                startTracker.clear("RELEASE_WITHOUT_START")
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
        private const val DUPLICATE_SUPPRESS_MS = 1_500L
    }
}
