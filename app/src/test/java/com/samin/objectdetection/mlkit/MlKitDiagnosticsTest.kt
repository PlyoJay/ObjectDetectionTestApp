package com.samin.objectdetection.mlkit

import org.junit.Assert.*
import org.junit.Test

class MlKitDiagnosticsTest {
    @Test fun skipsShareOneFiveSecondLimitAcrossReasonsAndCountSuppressedEvents() {
        val fixture = Fixture()
        fixture.logger.skipped(1, "DISABLED", false)
        fixture.nowMs = 2_000
        fixture.logger.skipped(2, "INTERVAL", false)
        fixture.nowMs = 5_999
        fixture.logger.skipped(3, "BUSY", true)
        assertEquals(1, fixture.lines.size)

        fixture.nowMs = 6_000 // Exactly five seconds after the first skip is eligible.
        fixture.logger.skipped(4, "INTERVAL", false)
        assertEquals(2, fixture.lines.size)
        assertFields(fixture.lines.last(), "frame" to "4", "stage" to "SKIP",
            "reason" to "INTERVAL", "suppressedSkips" to "2")

        fixture.logger.skipped(5, "DISABLED", false)
        fixture.nowMs = 11_000
        fixture.logger.skipped(6, "BUSY", true)
        assertEquals(3, fixture.lines.size)
        assertFields(fixture.lines.last(), "frame" to "6", "suppressedSkips" to "1")
    }

    @Test fun busySkipCorrelatesSkippedFrameWithActiveFrameAndLeaseDuration() {
        val fixture = Fixture()
        fixture.logger.acquired(41)
        fixture.nowMs += 275
        fixture.logger.skipped(42, "BUSY", true)
        assertFields(fixture.lines.last(), "session" to "test-session", "frame" to "42",
            "stage" to "SKIP", "reason" to "BUSY", "inFlight" to "true",
            "activeFrame" to "41", "inFlightMs" to "275")

        fixture.nowMs += 625
        fixture.logger.released(41)
        assertFields(fixture.lines.last(), "frame" to "41", "stage" to "LEASE_RELEASED",
            "heldMs" to "900", "inFlight" to "false")
    }

    @Test fun eachAcquisitionHasAnIncreasingRequestIndexAndItsSourceFrame() {
        val fixture = Fixture()
        for (frame in listOf(17L, 22L, 35L)) {
            fixture.logger.acquired(frame)
            fixture.logger.released(frame)
        }
        val acquisitions = fixture.lines.filter { field(it, "stage") == "LEASE_ACQUIRED" }
        assertEquals(3, acquisitions.size)
        acquisitions.forEachIndexed { index, line ->
            assertFields(line, "requestIndex" to (index + 1).toString(), "inFlight" to "true")
        }
        assertEquals(listOf("17", "22", "35"), acquisitions.map { field(it, "frame") })
    }

    @Test fun lateOldReleaseCannotClearANewerAcquisitionOrResetItsDuration() {
        val fixture = Fixture()
        fixture.logger.acquired(10)
        fixture.nowMs += 20
        fixture.logger.released(10)
        fixture.logger.acquired(11)
        fixture.nowMs += 30
        fixture.logger.released(10) // Delayed duplicate completion from the previous request.
        assertFields(fixture.lines.last(), "frame" to "10", "heldMs" to "-1")

        fixture.nowMs += 70
        fixture.logger.skipped(12, "BUSY", true)
        assertFields(fixture.lines.last(), "frame" to "12", "activeFrame" to "11",
            "inFlight" to "true", "inFlightMs" to "100")
        fixture.logger.released(11)
        assertFields(fixture.lines.last(), "frame" to "11", "heldMs" to "100")
    }

    @Test fun releaseClearsActiveFrameBeforeLaterIdleSkips() {
        val fixture = Fixture()
        fixture.logger.acquired(7)
        fixture.nowMs += 80
        fixture.logger.released(7)
        fixture.logger.skipped(8, "INTERVAL", false)
        assertFields(fixture.lines.last(), "activeFrame" to "-1", "inFlightMs" to "-1",
            "inFlight" to "false")
    }

    @Test fun throwingSinkDoesNotInterruptAcquisitionSkipOrReleaseBookkeeping() {
        var nowMs = 1_000L
        val attempted = mutableListOf<String>()
        val logger = MlKitDiagnostics("throwing-session", { nowMs * 1_000_000L }) {
            attempted.add(it)
            throw IllegalStateException("Log destination unavailable")
        }
        logger.acquired(1)
        nowMs += 40
        logger.skipped(2, "BUSY", true)
        nowMs += 10
        logger.released(1)
        logger.acquired(3)
        logger.skipped(4, "BUSY", true) // Still suppressed despite the first sink failure.
        nowMs += 5_000
        logger.skipped(5, "BUSY", true)
        assertEquals(5, attempted.size)
        assertFields(attempted[3], "frame" to "3", "requestIndex" to "2")
        assertFields(attempted.last(), "frame" to "5", "activeFrame" to "3",
            "inFlightMs" to "5000", "suppressedSkips" to "1")
        logger.released(3)
        assertFields(attempted.last(), "frame" to "3", "heldMs" to "5000")
    }

    private class Fixture {
        var nowMs = 1_000L
        val lines = mutableListOf<String>()
        val logger = MlKitDiagnostics("test-session", { nowMs * 1_000_000L }) { lines.add(it) }
    }

    private fun assertFields(line: String, vararg expected: Pair<String, String>) {
        assertTrue(line, line.startsWith("[MLKIT_DIAG] "))
        expected.forEach { (name, value) -> assertEquals("$name in $line", value, field(line, name)) }
    }

    private fun field(line: String, name: String): String? =
        line.split(' ').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}
