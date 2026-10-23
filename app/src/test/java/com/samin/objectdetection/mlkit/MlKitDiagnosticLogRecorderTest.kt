package com.samin.objectdetection.mlkit

import com.samin.objectdetection.logging.OrderedLogWriter
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class MlKitDiagnosticLogRecorderTest {
    @Test fun headerRecordsActualRuntimeModeAndKeepsModeSessionsSeparateWithDiagnosticsAppliedOff() {
        val recorder = owner()
        val stream = "debug.enableMlKitDiagnostics=false\nmlKit.mode=STREAM_MODE"
        recorder.runtimeStarted("stream", false, stream, "STREAM_MODE")
        val first = recorder.start(stream, false, temporaryEnable = true).done()
        recorder.record("stream", "event=PROCESS_REQUEST mode=STREAM_MODE")
        val single = stream.replace("STREAM_MODE", "SINGLE_IMAGE_MODE")
        recorder.runtimeStarted("single", false, single, "SINGLE_IMAGE_MODE")
        recorder.files().done()
        assertTrue(recorder.preview(first).done().contains("sdkMode=STREAM_MODE"))
        val second = recorder.start(single, false, temporaryEnable = true).done()
        recorder.sourceEnded("stream").done()
        assertTrue(recorder.isRecordingFor("single"))
        recorder.stop().done()
        assertTrue(recorder.preview(second).done().contains("sdkMode=SINGLE_IMAGE_MODE"))
        assertTrue(recorder.preview(second).done().contains("mlKit.mode=SINGLE_IMAGE_MODE"))
    }
    @Test fun temporaryOptInRecordsAppliedOffSnapshotWithoutChangingRuntimeSetting() {
        val recorder = owner()
        val off = "debug.enableMlKitDiagnostics=false"
        recorder.runtimeStarted("A", false, off)
        val first = recorder.start(off, false, temporaryEnable = true).done()
        assertTrue(recorder.isRecordingFor("A")); assertFalse(recorder.isRecordingFor("B"))
        recorder.runtimeStarted("A", false, off)
        recorder.files().done()
        assertTrue(recorder.isRecordingFor("A"))
        recorder.record("A", "stage=SDK sdkCount=2")
        recorder.stop().done()
        assertFalse(recorder.isRecordingFor("A"))
        val text = recorder.preview(first).done()
        assertTrue(text.contains(off)); assertTrue(text.contains("recording.temporaryDiagnostics=true"))
        assertTrue(text.contains("sdkCount=2")); assertTrue(text.contains("END endedAt="))
        val second = recorder.start(off, false, temporaryEnable = true).done()
        assertNotEquals(first, second)
        recorder.runtimeStarted("B", false, off)
        recorder.files().done()
        assertFalse(recorder.isRecordingFor("A")); assertFalse(recorder.isRecordingFor("B"))
        try { recorder.start(off, false).done(); fail("Temporary opt-in must not persist") }
        catch (_: ExecutionException) { }
    }
    @get:Rule val temporary = TemporaryFolder()
    private val owners = mutableListOf<MlKitDiagnosticLogRecorder>()
    private val settings = "debug.enableMlKitDiagnostics=true\nmlKit.detectionIntervalMs=1500\ncamera.roi.top=0.2\ncamera.previewEnabled=true"
    private fun <T> CompletableFuture<T>.done(): T = get(5, TimeUnit.SECONDS)
    private fun owner(root: File = temporary.newFolder(), capacity: Int = 256, maxFileBytes: Long = 5L * 1024 * 1024,
        maxFiles: Int = 20, maxTotal: Long = 50L * 1024 * 1024, duration: Long = 1_800_000,
        open: (File) -> BufferedWriter = { it.outputStream().bufferedWriter(Charsets.UTF_8) }): MlKitDiagnosticLogRecorder =
        MlKitDiagnosticLogRecorder(File(root, "logs"), File(root, "shared"), capacity = capacity,
            maxFileBytes = maxFileBytes, maxFiles = maxFiles, maxTotalBytes = maxTotal, maxDurationMs = duration,
            openWriter = open).also { owners.add(it) }

    @After fun closeOwners() { owners.forEach { runCatching { it.shutdown().done() } } }

    @Test fun offAndStoppedDoNotCreateLogsOrOpenWriter() {
        val root = temporary.newFolder()
        var opened = 0
        val recorder = owner(root, open = { opened++; it.bufferedWriter() })
        recorder.record("app", "ignored")
        try { recorder.start(settings, false).done(); fail("OFF must reject start") }
        catch (expected: ExecutionException) { assertTrue(expected.cause is IllegalStateException) }
        assertTrue(recorder.files().done().isEmpty())
        assertFalse(File(root, "logs").exists())
        assertEquals(0, opened)
    }

    @Test fun sameDiagnosticMessagesReachLogcatSinkAndUtf8FileWithHeaderAndFooter() {
        val root = temporary.newFolder()
        val recorder = owner(root)
        recorder.runtimeStarted("app-session", true, settings)
        val name = recorder.start(settings, true).done()
        val logcat = mutableListOf<String>()
        val diagnostic = MlKitDiagnostics("app-session", { 1_000L }) { line -> logcat.add(line); recorder.record("app-session", line) }
        diagnostic.acquired(7)
        diagnostic.event(7, "SDK") { "event=TASK_COMPLETE outcome=SUCCESS sdkCount=2 boxes=한글" }
        diagnostic.event(7, "MAPPED") { "mappedCount=2" }
        diagnostic.event(7, "OVERLAY_DRAW") { "drawCalls=2 inViewCount=2" }
        diagnostic.released(7)
        val saved = recorder.stop().done()
        assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, saved.phase)
        val text = File(root, "logs/$name").readText(Charsets.UTF_8)
        assertTrue(text.contains(settings)); assertTrue(text.contains("stage=CONFIG"))
        assertTrue(text.contains("startedAt=")); assertTrue(text.contains("END endedAt="))
        assertTrue(text.contains("reason=MANUAL droppedLines=0"))
        assertEquals(logcat, text.lines().filter { it.startsWith("unixMs=") }.map { it.substringAfter("elapsedNs=").substringAfter(' ') })
        assertFalse(File(root, "logs/$name.open").exists())
        recorder.record("app-session", "after stop")
        assertFalse(File(root, "logs/$name").readText().contains("after stop"))
        assertEquals(saved, recorder.stop().done())
    }

    @Test fun boundedQueueDropsWithoutBlockingAndStopDrainsBeforeFooter() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val root = temporary.newFolder()
        val recorder = owner(root, capacity = 1, open = { file -> gated(file, entered, release) })
        val name = recorder.start(settings, true).done()
        try {
            recorder.record("app", "BLOCK")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            recorder.record("app", "dropped")
            val stop = recorder.stop()
            assertFalse(stop.isDone)
            release.countDown()
            assertEquals(1L, stop.done().droppedLines)
            val text = File(root, "logs/$name").readText()
            assertTrue(text.indexOf("BLOCK") < text.indexOf("END endedAt="))
            assertTrue(text.contains("droppedLines=1")); assertFalse(text.contains(" dropped\n"))
        } finally { release.countDown() }
    }

    @Test fun diskFailurePreservesPartialTxtAndNextOwnerMarksUnknownInterruption() {
        val root = temporary.newFolder()
        val closed = CountDownLatch(1)
        val recorder = owner(root, open = { file ->
            val output = file.writer(Charsets.UTF_8)
            BufferedWriter(object : Writer() {
                override fun write(chars: CharArray, offset: Int, count: Int) {
                    if (String(chars, offset, count).contains("FAIL_WRITE")) throw IOException("disk full")
                    output.write(chars, offset, count)
                }
                override fun flush() = output.flush()
                override fun close() { output.close(); closed.countDown() }
            })
        })
        val name = recorder.start(settings, true).done()
        recorder.record("app", "FAIL_WRITE")
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(MlKitDiagnosticLogRecorder.Phase.ERROR, recorder.stop().done().phase)
        val file = File(root, "logs/$name")
        assertTrue(file.isFile); assertTrue(file.readText().contains("startedAt="))
        val replacement = owner(root)
        replacement.files().done()
        assertTrue(file.readText().contains("reason=PROCESS_INTERRUPTED_OR_WRITE_ERROR"))
        assertTrue(file.readText().contains("droppedLines=UNKNOWN"))
    }

    @Test fun restartDiagnosticsOffAndLateOldActivityDoNotMixSessions() {
        val recorder = owner()
        recorder.runtimeStarted("A", true, settings)
        recorder.start(settings, true).done()
        recorder.runtimeStarted("B", true, settings)
        recorder.files().done() // Control barrier: A was closed.
        assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, recorder.status().phase)
        recorder.start(settings, true).done()
        recorder.sourceEnded("A").done()
        assertEquals(MlKitDiagnosticLogRecorder.Phase.RECORDING, recorder.status().phase)
        recorder.runtimeStarted("B", false, settings)
        recorder.files().done()
        assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, recorder.status().phase)
    }

    @Test fun sourceTerminationClosesButSettingsScreenTransitionsNeedNotStopRecording() {
        val recorder = owner()
        recorder.runtimeStarted("activity", true, settings)
        recorder.start(settings, true).done()
        // Listing/polling from a recreated editor does not own or close the process recorder.
        repeat(3) { assertTrue(recorder.files().done().isEmpty()) }
        assertEquals(MlKitDiagnosticLogRecorder.Phase.RECORDING, recorder.status().phase)
        recorder.sourceEnded("activity").done()
        assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, recorder.status().phase)
    }

    @Test fun runtimeChangeDuringHeaderWriteClosesTheStartingRecording() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val recorder = owner(open = { file ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            file.outputStream().bufferedWriter(Charsets.UTF_8)
        })
        recorder.runtimeStarted("A", true, settings)
        val starting = recorder.start(settings, true)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            recorder.runtimeStarted("B", false, settings)
        } finally { release.countDown() }
        starting.done()
        recorder.files().done()
        assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, recorder.status().phase)
    }

    @Test fun sharingStopsFlushesAndMakesIndependentReadOnlyGrantSnapshot() {
        val root = temporary.newFolder()
        val recorder = owner(root)
        recorder.start(settings, true).done()
        recorder.record("app", "sdkCount=3 stage=OVERLAY_DRAW")
        val first = recorder.prepareShare().done()
        assertEquals(File(root, "shared"), first.parentFile)
        assertTrue(first.readText().contains("sdkCount=3")); assertTrue(first.readText().contains("reason=SHARE"))
        val original = first.readBytes()
        val second = recorder.prepareShare().done()
        assertNotEquals(first.name, second.name)
        assertArrayEquals(original, second.readBytes())
        assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, recorder.status().phase)
    }

    @Test fun collisionsDeletionAndRetentionStayInsideMlKitDirectory() {
        val root = temporary.newFolder()
        val recorder = owner(root, maxFiles = 3)
        val names = mutableSetOf<String>()
        repeat(7) { names.add(recorder.start(settings, true).done()); recorder.stop().done() }
        assertEquals(7, names.size)
        val files = recorder.files().done()
        assertEquals(3, files.size)
        val victim = files.first().name
        assertTrue(recorder.preview(victim).done().contains("ML Kit Diagnostics"))
        recorder.delete(victim).done()
        assertEquals(2, recorder.files().done().size)
        try { recorder.delete("../outside.txt").done(); fail("Traversal must fail") } catch (_: ExecutionException) { }
        val archive = File(root, "shared/object_detection_latest_logs.zip").apply { parentFile!!.mkdirs(); writeText("P0 archive") }
        recorder.prepareShare().done()
        assertEquals("P0 archive", archive.readText())
    }

    @Test fun abnormalProcessMarkerIsRecoveredWithoutInventingEndTimeOrDropCount() {
        val root = temporary.newFolder()
        val folder = File(root, "logs").apply { mkdirs() }
        val partial = File(folder, "mlkit_interrupted.txt").apply { writeText("start\nsdkCount=1\n", Charsets.UTF_8) }
        File(folder, "${partial.name}.open").writeText("OPEN")
        val recorder = owner(root)
        assertEquals(1, recorder.files().done().size)
        assertTrue(partial.readText().contains("endedUnixMs=UNKNOWN"))
        assertTrue(partial.readText().contains("droppedLines=UNKNOWN"))
        val recovered = partial.readText()
        recorder.files().done()
        assertEquals(recovered, partial.readText())
    }

    @Test fun sizeLimitAutomaticallyClosesAndCountsRejectedEvents() {
        val root = temporary.newFolder()
        val recorder = owner(root, maxFileBytes = 8192)
        val name = recorder.start(settings, true).done()
        repeat(10) { recorder.record("app", "x".repeat(1000)) }
        val saved = recorder.stop().done()
        assertTrue(saved.droppedLines > 0)
        val file = File(root, "logs/$name")
        assertTrue(file.length() <= 8192)
        assertTrue(file.readText().contains("reason=SIZE_LIMIT"))
    }

    @Test fun completedFooterWithLeftoverMarkerIsNotDuplicated() {
        val root = temporary.newFolder()
        val recorder = owner(root)
        val name = recorder.start(settings, true).done()
        recorder.stop().done()
        val file = File(root, "logs/$name")
        val completed = file.readText()
        File(root, "logs/$name.open").writeText("OPEN")
        recorder.files().done()
        assertEquals(completed, file.readText())
        assertFalse(File(root, "logs/$name.open").exists())
    }

    @Test fun ageAndTotalSizeRetentionKeepNewestAndIgnoreOtherFiles() {
        val root = temporary.newFolder()
        val folder = File(root, "logs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        fun seed(name: String, age: Long) = File(folder, "mlkit_$name.txt").apply {
            writeText("x".repeat(60)); assertTrue(setLastModified(now - age))
        }
        seed("new", 1000); seed("old", 2000); seed("expired", TimeUnit.DAYS.toMillis(8))
        val other = File(folder, "FRAME.txt").apply { writeText("P0") }
        val recorder = owner(root, maxTotal = 100)
        assertEquals(listOf("mlkit_new.txt"), recorder.files().done().map { it.name })
        assertTrue(other.exists())
    }

    @Test fun durationLimitClosesWithoutAnyCameraEvents() {
        val closed = CountDownLatch(1)
        val recorder = owner(duration = 30, open = { file ->
            val writer = file.writer(Charsets.UTF_8)
            BufferedWriter(object : Writer() {
                override fun write(chars: CharArray, offset: Int, count: Int) = writer.write(chars, offset, count)
                override fun flush() = writer.flush()
                override fun close() { writer.close(); closed.countDown() }
            })
        })
        recorder.start(settings, true).done()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(MlKitDiagnosticLogRecorder.Phase.SAVED, recorder.stop().done().phase)
    }

    @Test fun blockedDiagnosticDiskDoesNotBlockIndependentP0Writer() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val recorder = owner(open = { file -> gated(file, entered, release) })
        recorder.start(settings, true).done()
        try {
            recorder.record("app", "BLOCK")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val text = StringWriter()
            val p0 = OrderedLogWriter(BufferedWriter(text))
            assertTrue(p0.append { "FRAME existing P0" })
            p0.close { "LATENCY existing P0" }.done()
            assertTrue(text.toString().contains("LATENCY existing P0"))
        } finally { release.countDown(); recorder.stop().done() }
    }

    private fun gated(file: File, entered: CountDownLatch, release: CountDownLatch): BufferedWriter {
        val output = file.writer(Charsets.UTF_8)
        return BufferedWriter(object : Writer() {
            override fun write(chars: CharArray, offset: Int, count: Int) {
                if (String(chars, offset, count).contains("BLOCK")) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                output.write(chars, offset, count)
            }
            override fun flush() = output.flush()
            override fun close() = output.close()
        })
    }
}
