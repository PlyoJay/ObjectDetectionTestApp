package com.samin.objectdetection.warning.output

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.RiskLevel
import com.samin.objectdetection.warning.WarningCandidate
import com.samin.objectdetection.warning.WarningPolicy
import java.util.Locale

class TtsWarningPlayer(
    context: Context,
    private val cooldownMs: Long = 5_000L
) : WarningPlayer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastSpokenAtMsByKey = mutableMapOf<String, Long>()
    @Volatile
    private var isReady = false
    @Volatile
    private var isReleased = false
    private var isSpeaking = false
    private var pendingSpeech: PendingSpeech? = null
    private var currentUtteranceId: String? = null
    private var lastSpeechStartedAtMs = 0L
    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS && !isReleased) {
                    val result = textToSpeech.setLanguage(Locale.KOREAN)
                    isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    textToSpeech.setSpeechRate(SPEECH_RATE)
                    textToSpeech.setOnUtteranceProgressListener(createUtteranceProgressListener())
                    Log.d(TAG, "TextToSpeech ready=$isReady, languageResult=$result")
                } else {
                    isReady = false
                    Log.w(TAG, "TextToSpeech init failed: status=$status")
                }
            }
        }
    }

    override fun playIfNeeded(candidate: WarningCandidate) {
        if (candidate.feedback.voiceLevel == FeedbackLevel.NONE) return
        if (isReleased) return

        val now = System.currentTimeMillis()
        val cooldownKey = candidate.warningKey
        val message = buildMessage(candidate)

        mainHandler.post {
            handleSpeechRequest(candidate, message, cooldownKey, now)
        }
    }

    override fun release() {
        mainHandler.post {
            isReleased = true
            isReady = false
            isSpeaking = false
            pendingSpeech = null
            currentUtteranceId = null
            lastSpeechStartedAtMs = 0L
            mainHandler.removeCallbacksAndMessages(null)
            lastSpokenAtMsByKey.clear()
            try {
                textToSpeech.setOnUtteranceProgressListener(null)
                textToSpeech.stop()
                textToSpeech.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "TTS release failed", e)
            }
        }
    }

    private fun handleSpeechRequest(
        candidate: WarningCandidate,
        message: String,
        cooldownKey: String,
        requestedAtMs: Long
    ) {
        if (!isReady || isReleased) {
            Log.d(TAG, "skip TTS: ready=$isReady, released=$isReleased")
            return
        }

        val lastSpokenAtMs = lastSpokenAtMsByKey[cooldownKey] ?: 0L
        if (requestedAtMs - lastSpokenAtMs < cooldownMs) {
            Log.d(TAG, "skip TTS: cooldown key=$cooldownKey")
            return
        }

        val speech = PendingSpeech(
            candidate = candidate,
            message = message,
            key = cooldownKey,
            createdAtMs = requestedAtMs
        )

        if (candidate.riskLevel == RiskLevel.CRITICAL) {
            interruptAndSpeakNow(speech)
            return
        }

        val elapsedSinceLastSpeechMs = requestedAtMs - lastSpeechStartedAtMs
        if (!isSpeaking && elapsedSinceLastSpeechMs >= GLOBAL_TTS_INTERVAL_MS) {
            speakNow(speech, TextToSpeech.QUEUE_ADD)
            return
        }

        keepHigherPriorityPending(speech)
        schedulePendingAfterInterval()
    }

    private fun interruptAndSpeakNow(speech: PendingSpeech) {
        pendingSpeech = null
        try {
            textToSpeech.stop()
        } catch (e: Exception) {
            Log.w(TAG, "TTS stop failed", e)
        }
        isSpeaking = false
        speakNow(speech, TextToSpeech.QUEUE_FLUSH)
    }

    private fun speakNow(speech: PendingSpeech, queueMode: Int) {
        if (isReleased || !isReady) return

        try {
            val result = textToSpeech.speak(
                speech.message,
                queueMode,
                Bundle.EMPTY,
                speech.utteranceId
            )
            if (result == TextToSpeech.ERROR) {
                isSpeaking = false
                currentUtteranceId = null
                Log.w(TAG, "TTS speak returned ERROR")
                return
            }
            isSpeaking = true
            currentUtteranceId = speech.utteranceId
            lastSpeechStartedAtMs = System.currentTimeMillis()
            lastSpokenAtMsByKey[speech.key] = lastSpeechStartedAtMs
        } catch (e: Exception) {
            isSpeaking = false
            currentUtteranceId = null
            Log.w(TAG, "TTS speak failed", e)
        }
    }

    private fun keepHigherPriorityPending(speech: PendingSpeech) {
        val current = pendingSpeech
        if (current == null ||
            isPendingExpired(current, speech.createdAtMs) ||
            riskRank(speech.candidate.riskLevel) > riskRank(current.candidate.riskLevel)
        ) {
            pendingSpeech = speech
        }
    }

    private fun trySpeakPending() {
        if (isReleased || !isReady || isSpeaking) return

        val now = System.currentTimeMillis()
        val pending = pendingSpeech ?: return
        if (isPendingExpired(pending, now)) {
            pendingSpeech = null
            return
        }

        val elapsedSinceLastSpeechMs = now - lastSpeechStartedAtMs
        if (elapsedSinceLastSpeechMs < GLOBAL_TTS_INTERVAL_MS) {
            schedulePendingAfterInterval()
            return
        }

        pendingSpeech = null
        speakNow(pending, TextToSpeech.QUEUE_ADD)
    }

    private fun schedulePendingAfterInterval() {
        mainHandler.removeCallbacks(trySpeakPendingRunnable)
        val delayMs = (GLOBAL_TTS_INTERVAL_MS - (System.currentTimeMillis() - lastSpeechStartedAtMs))
            .coerceAtLeast(0L)
        mainHandler.postDelayed(trySpeakPendingRunnable, delayMs)
    }

    private fun createUtteranceProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    if (!isReleased && utteranceId == currentUtteranceId) {
                        isSpeaking = true
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                onSpeechFinished(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onSpeechFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onSpeechFinished(utteranceId)
            }
        }
    }

    private fun onSpeechFinished(utteranceId: String?) {
        mainHandler.post {
            if (isReleased) return@post
            if (utteranceId != null && utteranceId != currentUtteranceId) return@post
            isSpeaking = false
            currentUtteranceId = null
            trySpeakPending()
        }
    }

    private fun isPendingExpired(speech: PendingSpeech, nowMs: Long): Boolean {
        return nowMs - speech.createdAtMs > PENDING_MAX_AGE_MS
    }

    private fun riskRank(riskLevel: RiskLevel): Int {
        return when (riskLevel) {
            RiskLevel.NONE -> 0
            RiskLevel.LOW -> 1
            RiskLevel.MEDIUM -> 2
            RiskLevel.HIGH -> 3
            RiskLevel.CRITICAL -> 4
        }
    }

    private fun buildMessage(candidate: WarningCandidate): String {
        candidate.feedback.message?.let { return it }
        val objectName = WarningPolicy.labelToKorean(candidate.label)

        return when {
            candidate.riskLevel == RiskLevel.CRITICAL ||
                candidate.feedback.voiceLevel == FeedbackLevel.HIGH -> "정지. 전방 $objectName 위험."
            candidate.riskLevel == RiskLevel.HIGH ||
                candidate.feedback.voiceLevel == FeedbackLevel.MEDIUM -> "주의. 전방 $objectName 주의."
            candidate.riskLevel == RiskLevel.MEDIUM ||
                candidate.feedback.voiceLevel == FeedbackLevel.LOW -> "전방 $objectName 주의."
            else -> "전방 장애물 주의."
        }
    }

    companion object {
        private const val TAG = "TtsWarningPlayer"
        private const val SPEECH_RATE = 1.05f
        private const val PENDING_MAX_AGE_MS = 2_500L
        private const val GLOBAL_TTS_INTERVAL_MS = 1_500L
    }

    private data class PendingSpeech(
        val candidate: WarningCandidate,
        val message: String,
        val key: String,
        val createdAtMs: Long
    ) {
        val utteranceId: String = "$key:$createdAtMs"
    }

    private val trySpeakPendingRunnable = Runnable {
        trySpeakPending()
    }
}
