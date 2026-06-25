package com.samin.objectdetection.warning.output

import android.util.Log
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.ObjectPriority
import com.samin.objectdetection.warning.RiskLevel
import com.samin.objectdetection.warning.WarningCandidate
import com.samin.objectdetection.warning.WarningCooldownManager
import com.samin.objectdetection.warning.WarningScenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WarningOutputController(
    private val scope: CoroutineScope,
    private val vibrationWarningPlayer: VibrationWarningPlayer,
    private val beepWarningPlayer: BeepWarningPlayer,
    private val ttsWarningPlayer: TtsWarningPlayer,
    private val warningCooldownManager: WarningCooldownManager,
    private val enableActualVibration: () -> Boolean,
    private val enableActualBeep: () -> Boolean,
    private val enableActualTts: () -> Boolean
) {
    private val lock = Any()
    private var isOutputPlaying = false
    private var currentCandidate: WarningCandidate? = null
    private var currentJob: Job? = null
    private var currentOutputId = 0L

    fun submit(
        candidate: WarningCandidate?,
        cooldownAllowed: Boolean,
        timestampMs: Long
    ): OutputSubmissionResult {
        if (candidate == null) {
            val result = OutputSubmissionResult(skippedReason = "candidate_none")
            logOutput(candidate, cooldownAllowed, result)
            return result
        }
        if (!candidate.feedback.shouldNotify) {
            val result = OutputSubmissionResult(skippedReason = "should_notify_false")
            logOutput(candidate, cooldownAllowed, result)
            return result
        }
        if (!cooldownAllowed) {
            val result = OutputSubmissionResult(skippedReason = "cooldown")
            logOutput(candidate, cooldownAllowed, result)
            return result
        }

        val interrupted: Boolean
        val outputId: Long
        synchronized(lock) {
            val playingCandidate = currentCandidate
            if (isOutputPlaying && !canInterrupt(candidate, playingCandidate)) {
                val result = OutputSubmissionResult(
                    skippedReason = "output_playing",
                    isOutputPlaying = true
                )
                logOutput(candidate, cooldownAllowed, result)
                return result
            }

            interrupted = isOutputPlaying
            if (interrupted) {
                currentJob?.cancel()
                beepWarningPlayer.stop()
                ttsWarningPlayer.stop()
            }
            isOutputPlaying = true
            currentCandidate = candidate
            currentOutputId += 1
            outputId = currentOutputId
        }

        currentJob = scope.launch {
            runOutput(candidate, cooldownAllowed, timestampMs, interrupted, outputId)
        }

        val result = OutputSubmissionResult(
            accepted = true,
            skippedReason = null,
            interrupted = interrupted,
            isOutputPlaying = true
        )
        logOutput(candidate, cooldownAllowed, result)
        return result
    }

    fun release() {
        synchronized(lock) {
            currentJob?.cancel()
            currentJob = null
            currentCandidate = null
            isOutputPlaying = false
            currentOutputId += 1
        }
        beepWarningPlayer.stop()
        ttsWarningPlayer.stop()
    }

    private suspend fun runOutput(
        candidate: WarningCandidate,
        cooldownAllowed: Boolean,
        timestampMs: Long,
        interrupted: Boolean,
        outputId: Long
    ) {
        var vibrationExecuted = false
        var beepExecuted = false
        var ttsExecuted = false
        var skippedReason: String? = null

        try {
            val outputPlan = resolveOutputPlan(candidate)
            if (enableActualVibration() && outputPlan.vibrationLevel != FeedbackLevel.NONE) {
                vibrationExecuted = vibrationWarningPlayer.play(outputPlan.vibrationLevel)
            }

            if (enableActualBeep() && shouldPlayBeep(outputPlan.beepLevel)) {
                val beepDelayMs = beepWarningPlayer.expectedDurationMs(outputPlan.beepLevel)
                beepExecuted = beepWarningPlayer.play(outputPlan.beepLevel)
                delay(beepDelayMs + TTS_PRE_DELAY_MS)
            } else {
                delay(TTS_PRE_DELAY_MS)
            }

            if (enableActualTts() && outputPlan.shouldSpeak) {
                val message = candidate.feedback.message
                val ttsResult = when {
                    message.isNullOrBlank() -> TtsWarningPlayer.TtsResult(false, "message_blank")
                    !shouldSpeak(candidate) -> TtsWarningPlayer.TtsResult(false, "tts_policy_skip")
                    else -> ttsWarningPlayer.speak(
                        candidate = candidate,
                        message = message,
                        force = candidate.riskLevel == RiskLevel.CRITICAL || interrupted
                    )
                }
                ttsExecuted = ttsResult.executed
                skippedReason = ttsResult.skippedReason
            } else if (outputPlan.shouldSpeak) {
                skippedReason = "tts_disabled"
            } else {
                skippedReason = "tts_not_selected"
            }

            if (vibrationExecuted || beepExecuted || ttsExecuted) {
                warningCooldownManager.markNotified(candidate.warningKey, timestampMs)
            } else if (skippedReason == null) {
                skippedReason = "no_output_executed"
            }
        } catch (e: CancellationException) {
            skippedReason = "interrupted_by_critical"
            throw e
        } finally {
            synchronized(lock) {
                if (currentOutputId == outputId) {
                    isOutputPlaying = false
                    currentCandidate = null
                    currentJob = null
                }
            }
            logOutput(
                candidate = candidate,
                cooldownAllowed = cooldownAllowed,
                result = OutputSubmissionResult(
                    accepted = true,
                    skippedReason = skippedReason,
                    vibrationExecuted = vibrationExecuted,
                    beepExecuted = beepExecuted,
                    ttsExecuted = ttsExecuted,
                    interrupted = interrupted,
                    isOutputPlaying = isOutputPlaying()
                )
            )
        }
    }

    private fun canInterrupt(
        newCandidate: WarningCandidate,
        currentCandidate: WarningCandidate?
    ): Boolean {
        if (newCandidate.riskLevel != RiskLevel.CRITICAL) return false
        val currentRiskLevel = currentCandidate?.riskLevel ?: RiskLevel.NONE
        return riskRank(newCandidate.riskLevel) > riskRank(currentRiskLevel)
    }

    private fun riskRank(riskLevel: RiskLevel): Int {
        return when (riskLevel) {
            RiskLevel.CRITICAL -> 4
            RiskLevel.HIGH -> 3
            RiskLevel.MEDIUM -> 2
            RiskLevel.LOW -> 1
            RiskLevel.NONE -> 0
        }
    }

    private fun resolveOutputPlan(candidate: WarningCandidate): OutputPlan {
        if (candidate.riskLevel == RiskLevel.CRITICAL) {
            return OutputPlan(
                vibrationLevel = FeedbackLevel.HIGH,
                beepLevel = FeedbackLevel.NONE,
                shouldSpeak = true
            )
        }

        return when (candidate.warningScenario) {
            WarningScenario.FRONT_VEHICLE -> OutputPlan(
                vibrationLevel = FeedbackLevel.MEDIUM,
                beepLevel = FeedbackLevel.MEDIUM,
                shouldSpeak = false
            )
            WarningScenario.APPROACHING_OBJECT -> {
                if (candidate.riskLevel == RiskLevel.HIGH) {
                    OutputPlan(
                        vibrationLevel = FeedbackLevel.HIGH,
                        beepLevel = FeedbackLevel.HIGH,
                        shouldSpeak = false
                    )
                } else {
                    defaultOutputPlan(candidate)
                }
            }
            WarningScenario.FRONT_OBSTACLE -> {
                if (candidate.riskLevel == RiskLevel.HIGH) {
                    OutputPlan(
                        vibrationLevel = FeedbackLevel.MEDIUM,
                        beepLevel = FeedbackLevel.MEDIUM,
                        shouldSpeak = false
                    )
                } else {
                    defaultOutputPlan(candidate)
                }
            }
            WarningScenario.CROWD -> OutputPlan(
                vibrationLevel = maxOf(candidate.feedback.vibrationLevel, FeedbackLevel.LOW),
                beepLevel = FeedbackLevel.NONE,
                shouldSpeak = false
            )
            WarningScenario.IMMEDIATE_DANGER -> OutputPlan(
                vibrationLevel = FeedbackLevel.HIGH,
                beepLevel = FeedbackLevel.NONE,
                shouldSpeak = true
            )
            WarningScenario.TRAFFIC_INFO,
            WarningScenario.MONITORING -> defaultOutputPlan(candidate)
        }
    }

    private fun defaultOutputPlan(candidate: WarningCandidate): OutputPlan {
        return when (candidate.riskLevel) {
            RiskLevel.CRITICAL -> OutputPlan(
                vibrationLevel = FeedbackLevel.HIGH,
                beepLevel = FeedbackLevel.NONE,
                shouldSpeak = true
            )
            RiskLevel.HIGH -> OutputPlan(
                vibrationLevel = maxOf(candidate.feedback.vibrationLevel, FeedbackLevel.MEDIUM),
                beepLevel = maxOf(candidate.feedback.beepLevel, FeedbackLevel.MEDIUM),
                shouldSpeak = false
            )
            RiskLevel.MEDIUM -> OutputPlan(
                vibrationLevel = maxOf(candidate.feedback.vibrationLevel, FeedbackLevel.LOW),
                beepLevel = FeedbackLevel.NONE,
                shouldSpeak = false
            )
            RiskLevel.LOW,
            RiskLevel.NONE -> OutputPlan(
                vibrationLevel = FeedbackLevel.NONE,
                beepLevel = FeedbackLevel.NONE,
                shouldSpeak = false
            )
        }
    }

    private fun shouldPlayBeep(level: FeedbackLevel): Boolean {
        return when (level) {
            FeedbackLevel.HIGH,
            FeedbackLevel.MEDIUM -> true
            FeedbackLevel.LOW,
            FeedbackLevel.NONE -> false
        }
    }

    private fun shouldSpeak(candidate: WarningCandidate): Boolean {
        return when (candidate.warningScenario) {
            WarningScenario.IMMEDIATE_DANGER -> true
            WarningScenario.APPROACHING_OBJECT -> candidate.priority == ObjectPriority.HIGH
            WarningScenario.FRONT_OBSTACLE -> candidate.riskLevel == RiskLevel.HIGH ||
                candidate.riskLevel == RiskLevel.CRITICAL
            WarningScenario.FRONT_VEHICLE,
            WarningScenario.CROWD,
            WarningScenario.TRAFFIC_INFO,
            WarningScenario.MONITORING -> false
        }
    }

    private fun isOutputPlaying(): Boolean {
        return synchronized(lock) { isOutputPlaying }
    }

    private fun logOutput(
        candidate: WarningCandidate?,
        cooldownAllowed: Boolean,
        result: OutputSubmissionResult
    ) {
        Log.d(
            TAG,
            "[WarningOutput]\n" +
                "label=${candidate?.label ?: "none"}\n" +
                "riskLevel=${candidate?.riskLevel ?: RiskLevel.NONE}\n" +
                "warningScenario=${candidate?.warningScenario ?: WarningScenario.MONITORING}\n" +
                "motionDirection=${candidate?.motionDirection}\n" +
                "userObjectRelation=${candidate?.userObjectRelation}\n" +
                "message=${candidate?.feedback?.message}\n" +
                "cooldownAllowed=$cooldownAllowed\n" +
                "skippedReason=${result.skippedReason ?: "none"}\n" +
                "vibrationExecuted=${result.vibrationExecuted}\n" +
                "beepExecuted=${result.beepExecuted}\n" +
                "ttsExecuted=${result.ttsExecuted}\n" +
                "beepAndTtsOverlap=${result.beepExecuted && result.ttsExecuted}\n" +
                "interrupted=${result.interrupted}\n" +
                "isOutputPlaying=${result.isOutputPlaying}"
        )
    }

    data class OutputSubmissionResult(
        val accepted: Boolean = false,
        val skippedReason: String? = null,
        val vibrationExecuted: Boolean = false,
        val beepExecuted: Boolean = false,
        val ttsExecuted: Boolean = false,
        val interrupted: Boolean = false,
        val isOutputPlaying: Boolean = false
    )

    private data class OutputPlan(
        val vibrationLevel: FeedbackLevel,
        val beepLevel: FeedbackLevel,
        val shouldSpeak: Boolean
    )

    companion object {
        private const val TAG = "GotoroWarningOutput"
        private const val TTS_PRE_DELAY_MS = 220L
    }
}
