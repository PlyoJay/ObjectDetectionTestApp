package com.samin.objectdetection.warning

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
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
    private lateinit var textToSpeech: TextToSpeech

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post {
                if (status == TextToSpeech.SUCCESS && !isReleased) {
                    val result = textToSpeech.setLanguage(Locale.KOREAN)
                    isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    textToSpeech.setSpeechRate(SPEECH_RATE)
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
        synchronized(lastSpokenAtMsByKey) {
            val lastSpokenAtMs = lastSpokenAtMsByKey[cooldownKey] ?: 0L
            if (now - lastSpokenAtMs < cooldownMs) {
                Log.d(TAG, "skip TTS: cooldown key=$cooldownKey")
                return
            }
            lastSpokenAtMsByKey[cooldownKey] = now
        }

        val message = buildMessage(decision)

        mainHandler.post {
            if (!isReady || isReleased) {
                Log.d(TAG, "skip TTS: ready=$isReady, released=$isReleased")
                return@post
            }

            try {
                textToSpeech.speak(
                    message,
                    TextToSpeech.QUEUE_FLUSH,
                    Bundle.EMPTY,
                    cooldownKey
                )
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak failed", e)
            }
        }
    }

    override fun release() {
        mainHandler.post {
            isReleased = true
            isReady = false
            synchronized(lastSpokenAtMsByKey) {
                lastSpokenAtMsByKey.clear()
            }
            try {
                textToSpeech.stop()
                textToSpeech.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "TTS release failed", e)
            }
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
    }
}
