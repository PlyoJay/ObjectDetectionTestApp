package com.samin.objectdetection.warning

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
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

    override fun playIfNeeded(decision: WarningDecision) {
        if (decision.voiceLevel == FeedbackLevel.NONE) return
        if (isReleased) return

        val now = System.currentTimeMillis()
        val cooldownKey = buildCooldownKey(decision)
        val message = buildMessage(decision)

        mainHandler.post {
            handleSpeechRequest(decision, message, cooldownKey, now)
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
        decision: WarningDecision,
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
            decision = decision,
            message = message,
            key = cooldownKey,
            createdAtMs = requestedAtMs
        )

        if (decision.riskLevel == RiskLevel.CRITICAL) {
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
            riskRank(speech.decision.riskLevel) > riskRank(current.decision.riskLevel)
        ) {
            pendingSpeech = speech
            lastSpokenAtMsByKey[speech.key] = speech.createdAtMs
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

    private fun buildMessage(decision: WarningDecision): String {
        val objectName = decision.obstacle?.detection?.label
            ?.trim()
            ?.lowercase(Locale.US)
            ?.let(::toKoreanObjectName)
        val subject = objectName?.let { "$it${subjectParticle(it)}" }

        return when {
            decision.riskLevel == RiskLevel.CRITICAL ||
                decision.voiceLevel == FeedbackLevel.HIGH -> {
                if (objectName == null) {
                    "정지. 전방 위험."
                } else {
                    "정지. 전방 $objectName 위험."
                }
            }
            decision.riskLevel == RiskLevel.HIGH ||
                decision.voiceLevel == FeedbackLevel.MEDIUM -> {
                if (subject == null) {
                    "주의. 전방에 장애물이 있습니다."
                } else {
                    "주의. 전방에 $subject 있습니다."
                }
            }
            decision.riskLevel == RiskLevel.MEDIUM ||
                decision.voiceLevel == FeedbackLevel.LOW -> {
                if (objectName == null) {
                    "전방 장애물 주의."
                } else {
                    "전방 $objectName 주의."
                }
            }
            else -> "전방 장애물 주의."
        }
    }

    private fun buildCooldownKey(decision: WarningDecision): String {
        val obstacle = decision.obstacle
        val label = obstacle?.detection?.label
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
        val proximityLevel = obstacle?.proximityLevel?.name.orEmpty()
        return "$label:${decision.riskLevel}:${decision.voiceLevel}:$proximityLevel"
    }

    private fun toKoreanObjectName(label: String): String? {
        return when (label) {
            "car" -> "자동차"
            "person" -> "사람"
            "bicycle" -> "자전거"
            "motorcycle" -> "오토바이"
            "traffic light" -> "신호등"
            else -> null
        }
    }

    private fun subjectParticle(label: String): String {
        if (label.isEmpty()) return "가"
        val last = label.last()
        if (last !in '가'..'힣') return "가"
        return if ((last.code - 0xAC00) % 28 == 0) "가" else "이"
    }

    companion object {
        private const val TAG = "TtsWarningPlayer"
        private const val SPEECH_RATE = 1.05f
        private const val PENDING_MAX_AGE_MS = 2_500L
        private const val GLOBAL_TTS_INTERVAL_MS = 1_500L
    }

    private data class PendingSpeech(
        val decision: WarningDecision,
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
