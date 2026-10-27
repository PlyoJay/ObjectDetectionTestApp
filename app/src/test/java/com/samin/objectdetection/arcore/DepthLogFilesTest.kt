package com.samin.objectdetection.arcore

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class DepthLogFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyCsvMetadataSortsNewestFirstAndOtherFilesStayOutOfTheList() {
        val directory = temporary.newFolder()
        val old = csv(directory, "older.csv", "abc").apply { assertTrue(setLastModified(1_600_000_000_000)) }
        val newer = csv(directory, "newer.CSV", "12345").apply { assertTrue(setLastModified(1_700_000_000_000)) }
        csv(directory, "ignore.txt", "text")
        File(directory, "folder.csv").mkdir()
        val catalog = DepthLogFiles(directory)
        val logs = catalog.list()
        assertEquals(listOf(newer.name, old.name), logs.map { it.file.name })
        assertEquals(5L, logs.first().sizeBytes)
        assertEquals(1_700_000_000_000, logs.first().modifiedAtMs)
        assertTrue(logs.all { it.ready && it.createdAtMs > 0 })
        assertEquals(old, catalog.requireReady(old))
    }

    @Test fun incompleteCsvSurvivesButCannotBeSelectedSharedOrExported() {
        val directory = temporary.newFolder()
        val ready = csv(directory, "ready.csv", "complete")
        val incomplete = csv(directory, "partial.csv", "partial")
        DepthLogFiles.incompleteMarker(incomplete).createNewFile()
        val catalog = DepthLogFiles(directory)
        val selection = DepthLogSelection()
        selection.selectAll(catalog.list())
        assertEquals(listOf(ready), selection.selected(catalog.list()).map { it.file })
        selection.toggle(catalog.list().single { it.file == incomplete })
        assertEquals(1, selection.selected(catalog.list()).size)
        assertThrows(IllegalStateException::class.java) { catalog.requireReady(incomplete) }
        assertTrue(incomplete.isFile)
        assertThrows(IllegalStateException::class.java) { catalog.requireReady(temporary.newFile("outside.csv")) }
    }

    @Test fun selectionUsesNamesAcrossRefreshAndSupportsToggleSelectAllAndClear() {
        val directory = temporary.newFolder()
        val a = csv(directory, "a.csv", "a")
        csv(directory, "b.csv", "b")
        val catalog = DepthLogFiles(directory)
        val selection = DepthLogSelection()
        selection.toggle(catalog.list().single { it.file == a })
        csv(directory, "new.csv", "new")
        assertEquals(listOf(a), selection.selected(catalog.list()).map { it.file })
        selection.toggle(catalog.list().single { it.file == a })
        assertTrue(selection.selected(catalog.list()).isEmpty())
        selection.selectAll(catalog.list())
        assertEquals(3, selection.selected(catalog.list()).size)
        selection.clear()
        assertTrue(selection.selected(catalog.list()).isEmpty())
    }

    @Test fun readinessIsNotPublishedUntilWriterFlushAndCloseHaveFinished() {
        val directory = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val recorder = DepthCsvRecorder(directory) { file ->
            val actual = file.writer()
            BufferedWriter(object : Writer() {
                override fun write(chars: CharArray, offset: Int, length: Int) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); actual.write(chars, offset, length)
                }
                override fun flush() = actual.flush()
                override fun close() = actual.close()
            })
        }
        val file = recorder.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stopped = recorder.stop()
            assertFalse(stopped.isDone)
            assertFalse(DepthLogFiles(directory).list().single().ready)
            assertThrows(IllegalStateException::class.java) { DepthLogFiles(directory).requireReady(file) }
            release.countDown(); stopped.get(5, TimeUnit.SECONDS)
            assertTrue(DepthLogFiles(directory).list().single().ready)
            assertEquals(DepthCsv.HEADER, file.readText().trim())
        } finally { release.countDown(); recorder.stop().get(5, TimeUnit.SECONDS) }
    }

    @Test fun diskFailureLeavesPersistentIncompleteMarkerAndInternalBytesIntact() {
        val directory = temporary.newFolder()
        val recorder = DepthCsvRecorder(directory) { file ->
            file.writeText("partial bytes")
            BufferedWriter(object : Writer() {
                override fun write(chars: CharArray, offset: Int, length: Int) { throw IOException("disk full") }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val file = recorder.start()
        assertThrows(ExecutionException::class.java) { recorder.stop().get(5, TimeUnit.SECONDS) }
        assertEquals("partial bytes", file.readText())
        assertFalse(DepthLogFiles(directory).list().single().ready)
        assertNotNull(recorder.error)
    }

    private fun csv(directory: File, name: String, content: String) = File(directory, name).apply { writeText(content) }
}
