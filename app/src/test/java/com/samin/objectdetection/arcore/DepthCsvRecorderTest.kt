package com.samin.objectdetection.arcore

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale
import java.util.concurrent.TimeUnit

class DepthCsvRecorderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun reading(id: Long) = DepthMeasurementFilter().update(
        DepthSample(id, "TRACKING", 160, 120, 80, 60, 2.35, 25, id, "UPDATED", 1_700_000_000_000), id, true, 2.0)

    @Test fun csvHasRequiredColumnsAndIsLocaleIndependentWithRawAndFilteredValues() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val value = reading(123).copy(filteredDepthMeters = 2.1)
            val fields = DepthCsv.row(value).split(',')
            val columns = DepthCsv.HEADER.split(',')
            fun cell(name: String) = fields[columns.indexOf(name)]
            assertEquals(columns.size, fields.size)
            assertEquals("2.35", cell("rawDepthMeters")); assertEquals("2.1", cell("filteredDepthMeters"))
            assertEquals("123", cell("depthTimestampNs"))
            assertEquals("ARCORE_DEPTH", cell("source")); assertEquals("CAMERA_FORWARD_Z", cell("distanceDefinition"))
            assertEquals(.35, cell("absoluteErrorMeters").toDouble(), 1e-9)
            assertEquals(17.5, cell("relativeErrorPercent").toDouble(), 1e-9)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun missingReferenceAndUnavailableDepthUseEmptyFieldsRatherThanZero() {
        val value = DepthMeasurementFilter().update(DepthSample(1, "PAUSED", depthStatus = "TRACKING_PAUSED"), 1, true, null)
        val row = DepthCsv.row(value).split(',')
        val columns = DepthCsv.HEADER.split(',')
        for (key in listOf("rawDepthMeters", "filteredDepthMeters", "groundTruthMeters", "absoluteErrorMeters", "relativeErrorPercent")) {
            assertEquals("", row[columns.indexOf(key)])
        }
    }

    @Test fun stopDrainsOrderedRowsAndRepeatedSessionsUseSeparateFilesWithoutLeak() {
        val recorder = DepthCsvRecorder(temporary.newFolder("arcore_depth"))
        repeat(4) {
            val file = recorder.start()
            assertTrue(recorder.isRecording())
            repeat(30) { n -> recorder.append(reading(n + 1L)) }
            val close = recorder.stop()
            close.get(5, TimeUnit.SECONDS)
            assertFalse(recorder.isRecording()); assertFalse(recorder.isClosing())
            assertEquals(0, recorder.droppedLines())
            val lines = file.readLines()
            assertEquals(31, lines.size); assertEquals(DepthCsv.HEADER, lines.first())
            assertEquals((1L..30L).toList(), lines.drop(1).map { row -> row.split(',')[1].toLong() })
            recorder.append(reading(999))
            assertEquals(31, file.readLines().size)
        }
        assertEquals(4, temporary.root.resolve("arcore_depth").listFiles()!!.size)
    }
}
