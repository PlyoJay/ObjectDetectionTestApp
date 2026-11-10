package com.samin.objectdetection.warning.output

import android.util.Log
import com.samin.objectdetection.warning.CrowdDecision
import com.samin.objectdetection.warning.WarningCandidate

/** Test-app log formatting only; diagnostic gates precede all message construction. */
internal object WarningDiagnostics {
    fun logSelectedWarningCandidate(
        enabled: Boolean,
        candidate: WarningCandidate?,
        cooldownPassed: Boolean,
        vibrationExecuted: Boolean,
        beepExecuted: Boolean,
        ttsExecuted: Boolean
    ) {
        if (!enabled) return
        if (candidate == null) {
            Log.d(WARNING_SELECTED_TAG, "[WarningSelected] none")
            return
        }

        Log.d(
            WARNING_SELECTED_TAG,
            "[WarningSelected]\n" +
                "label=${candidate.label}\n" +
                "category=${candidate.category}\n" +
                "priority=${candidate.priority}\n" +
                "proximityLevel=${candidate.proximityLevel}\n" +
                "horizontalPosition=${candidate.horizontalPosition}\n" +
                "riskLevel=${candidate.riskLevel}\n" +
                "motionDirection=${candidate.motionDirection}\n" +
                "warningScenario=${candidate.warningScenario}\n" +
                "message=${candidate.feedback.message}\n" +
                "beepLevel=${candidate.feedback.beepLevel}\n" +
                "vibrationLevel=${candidate.feedback.vibrationLevel}\n" +
                "voiceLevel=${candidate.feedback.voiceLevel}\n" +
                "cooldownPassed=$cooldownPassed\n" +
                "vibrationExecuted=$vibrationExecuted\n" +
                "beepExecuted=$beepExecuted\n" +
                "ttsExecuted=$ttsExecuted\n" +
                "shouldNotify=${candidate.feedback.shouldNotify}"
        )
    }

    fun logWarningOutput(
        enabled: Boolean,
        candidate: WarningCandidate?,
        cooldownPassed: Boolean,
        vibrationExecuted: Boolean,
        beepExecuted: Boolean,
        ttsExecuted: Boolean,
        ttsSkippedReason: String?
    ) {
        if (!enabled) return
        if (candidate == null) {
            Log.d(WARNING_OUTPUT_TAG, "[WarningOutput] none")
            return
        }

        Log.d(
            WARNING_OUTPUT_TAG,
            "[WarningOutput]\n" +
                "label=${candidate.label}\n" +
                "category=${candidate.category}\n" +
                "priority=${candidate.priority}\n" +
                "proximityLevel=${candidate.proximityLevel}\n" +
                "horizontalPosition=${candidate.horizontalPosition}\n" +
                "riskLevel=${candidate.riskLevel}\n" +
                "motionDirection=${candidate.motionDirection}\n" +
                "warningScenario=${candidate.warningScenario}\n" +
                "message=${candidate.feedback.message}\n" +
                "vibrationLevel=${candidate.feedback.vibrationLevel}\n" +
                "vibrationExecuted=$vibrationExecuted\n" +
                "beepLevel=${candidate.feedback.beepLevel}\n" +
                "beepExecuted=$beepExecuted\n" +
                "voiceLevel=${candidate.feedback.voiceLevel}\n" +
                "ttsExecuted=$ttsExecuted\n" +
                "ttsSkippedReason=${ttsSkippedReason ?: "none"}\n" +
                "cooldown=$cooldownPassed"
        )
    }

    fun logCrowdDecision(
        enabled: Boolean,
        crowdDecision: CrowdDecision,
        cooldownPassed: Boolean
    ) {
        if (!enabled) return
        Log.d(
            CROWD_DECISION_TAG,
            "[CrowdDecision] " +
                "totalPersonCount=${crowdDecision.totalPersonCount}, " +
                "centerPersonCount=${crowdDecision.centerPersonCount}, " +
                "nearPersonCount=${crowdDecision.nearPersonCount}, " +
                "crowdLevel=${crowdDecision.crowdLevel}, " +
                "message=${crowdDecision.message}, " +
                "cooldownPassed=$cooldownPassed"
        )
    }

    private const val WARNING_SELECTED_TAG = "GotoroWarning"
    private const val WARNING_OUTPUT_TAG = "GotoroWarning"
    private const val CROWD_DECISION_TAG = "GotoroCrowd"
}
