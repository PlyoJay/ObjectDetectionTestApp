package com.samin.objectdetection.warning.output

import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.samin.objectdetection.MainActivity
import com.samin.objectdetection.camera.DetectionConfig
import com.samin.objectdetection.warning.CrowdDecision
import com.samin.objectdetection.warning.CrowdLevel
import com.samin.objectdetection.warning.FeedbackLevel
import com.samin.objectdetection.warning.HorizontalPosition
import com.samin.objectdetection.warning.ObjectPriority
import com.samin.objectdetection.warning.ProximityLevel
import com.samin.objectdetection.warning.RiskLevel
import com.samin.objectdetection.warning.WarningCandidate
import com.samin.objectdetection.warning.WarningCooldownManager
import com.samin.objectdetection.warning.WarningFeedback
import com.samin.objectdetection.warning.WarningScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Only diagnostic helpers and submit arbitration; never starts a camera or physical output. */
class WarningDiagnosticsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun candidate() = WarningCandidate(
        label = "phase1_probe", confidence = 0.9f, priority = ObjectPriority.HIGH,
        proximityLevel = ProximityLevel.NEAR, riskLevel = RiskLevel.HIGH,
        warningScenario = WarningScenario.FRONT_OBSTACLE, horizontalPosition = HorizontalPosition.CENTER,
        feedback = WarningFeedback(RiskLevel.HIGH, FeedbackLevel.MEDIUM, FeedbackLevel.MEDIUM,
            FeedbackLevel.NONE, "phase1 message", true), warningKey = "phase1_key"
    )

    private fun logs(action: () -> Unit): String {
        val token = UUID.randomUUID().toString()
        instrumentation.runOnMainSync {
            Log.i(PROBE_TAG, "BEGIN:$token")
            action()
            Log.i(PROBE_TAG, "END:$token")
        }
        val command = "logcat -d -v raw --pid=${Process.myPid()} -s " +
            "$PROBE_TAG:I GotoroWarning:D GotoroCrowd:D GotoroWarningOutput:D '*:S'"
        val text = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        assertTrue("Begin marker missing", text.contains("BEGIN:$token"))
        assertTrue("End marker missing", text.contains("END:$token"))
        return text.substringAfter("BEGIN:$token").substringBefore("END:$token").trim()
    }

    @Test fun activityHelpersSuppressOffAndKeepDiagnosticPayloadIncludingNone() {
        lateinit var activity: MainActivity
        // An unattached Activity is sufficient: these helpers depend only on detectionConfig.
        instrumentation.runOnMainSync { activity = MainActivity() }
        val config = MainActivity::class.java.getDeclaredField("detectionConfig").apply { isAccessible = true }
        val boolean = Boolean::class.javaPrimitiveType!!
        val selected = MainActivity::class.java.getDeclaredMethod("logSelectedWarningCandidate",
            WarningCandidate::class.java, boolean, boolean, boolean, boolean).apply { isAccessible = true }
        val output = MainActivity::class.java.getDeclaredMethod("logWarningOutput",
            WarningCandidate::class.java, boolean, boolean, boolean, boolean, String::class.java).apply { isAccessible = true }
        val crowd = MainActivity::class.java.getDeclaredMethod("logCrowdDecision",
            CrowdDecision::class.java, boolean).apply { isAccessible = true }
        for (enabled in listOf(false, true)) {
            val text = logs {
                config.set(activity, DetectionConfig(enableDetectorDiagnostics = enabled))
                selected.invoke(activity, candidate(), true, false, false, false)
                output.invoke(activity, candidate(), true, false, false, false, "probe_reason")
                crowd.invoke(activity, CrowdDecision(CrowdLevel.MEDIUM, 4, 3, 2, "crowd probe", true), false)
                selected.invoke(activity, null, false, false, false, false)
                output.invoke(activity, null, false, false, false, false, null)
            }
            if (!enabled) assertEquals("", text) else {
                assertTrue(text.contains("[WarningSelected]\nlabel=phase1_probe"))
                assertTrue(text.contains("[WarningOutput]\nlabel=phase1_probe"))
                assertTrue(text.contains("priority=HIGH"))
                assertTrue(text.contains("warningScenario=FRONT_OBSTACLE"))
                assertTrue(text.contains("message=phase1 message"))
                assertTrue(text.contains("ttsSkippedReason=probe_reason"))
                assertTrue(text.contains("[CrowdDecision] totalPersonCount=4, centerPersonCount=3, nearPersonCount=2, " +
                    "crowdLevel=MEDIUM, message=crowd probe, cooldownPassed=false"))
                assertTrue(text.contains("[WarningSelected] none"))
                assertTrue(text.contains("[WarningOutput] none"))
            }
        }
    }

    @Test fun controllerDiagnosticsDoNotChangeSubmitArbitrationOrCooldown() {
        val traces = mutableListOf<List<WarningOutputController.OutputSubmissionResult>>()
        for (enabled in listOf(false, true)) {
            val text = logs {
                val context = instrumentation.targetContext
                val vibration = VibrationWarningPlayer(context)
                val beep = BeepWarningPlayer()
                val tts = TtsWarningPlayer(context)
                val cooldown = WarningCooldownManager()
                // Cancelled scope keeps submit's playing state deterministic without running outputs.
                val job = SupervisorJob().apply { cancel() }
                val controller = WarningOutputController(CoroutineScope(job + Dispatchers.Main.immediate),
                    vibration, beep, tts, cooldown, { false }, { false }, { false }, enabled)
                try {
                    val candidate = candidate()
                    val results = listOf(
                        controller.submit(null, false, 1000),
                        controller.submit(candidate.copy(feedback = WarningFeedback.NONE), true, 1000),
                        controller.submit(candidate, false, 1000),
                        controller.submit(candidate, true, 1000),
                        controller.submit(candidate, true, 1000),
                        controller.submit(candidate.copy(riskLevel = RiskLevel.CRITICAL), true, 1000)
                    )
                    assertEquals(listOf("candidate_none", "should_notify_false", "cooldown", null,
                        "output_playing", null), results.map { it.skippedReason })
                    assertEquals(listOf(false, false, false, true, false, true), results.map { it.accepted })
                    assertTrue(results.last().interrupted)
                    assertTrue(results.none { it.vibrationExecuted || it.beepExecuted || it.ttsExecuted })
                    assertTrue("No executed output must mark cooldown", cooldown.canNotify(candidate.warningKey, 1001))
                    traces.add(results)
                } finally {
                    controller.release()
                    vibration.release()
                    beep.release()
                    tts.release()
                }
            }
            if (!enabled) assertEquals("", text) else {
                assertTrue(text.contains("[WarningOutput]\nlabel=none"))
                assertTrue(text.contains("label=phase1_probe"))
                assertTrue(text.contains("cooldownAllowed=false"))
                assertTrue(text.contains("skippedReason=cooldown"))
                assertTrue(text.contains("skippedReason=output_playing"))
                assertTrue(text.contains("interrupted=true"))
            }
        }
        assertEquals(traces[0], traces[1])
    }

    private companion object { const val PROBE_TAG = "Phase1DiagnosticsTest" }
}
