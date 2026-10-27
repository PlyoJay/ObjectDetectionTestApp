package com.samin.objectdetection.arcore

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class DepthLogExporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store = FakeDownloads()
    private fun exporter(directory: File) = DepthLogExporter(DepthLogFiles(directory), store)

    @Test fun exportWaitsForSuccessfulDrainAndPublishesOnlyClosedCompleteBytes() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "header\n2.35\n")
        val closed = CompletableFuture<Unit>()
        val result = exporter(directory).afterClosed(file, closed)
        assertEquals(0, store.inserted)
        assertFalse(result.isDone)
        closed.complete(Unit)
        val published = result.get(5, TimeUnit.SECONDS)
        assertNull(published.error)
        assertEquals("Download/GOTORO/ARCore/", published.published!!.relativePath)
        assertEquals(file.name, published.published.displayName)
        assertArrayEquals(file.readBytes(), store.rows.values.single().bytes)
        assertTrue(store.rows.values.single().published)
    }

    @Test fun failedDrainNeverTouchesDownloadsAndPreservesInternalLog() {
        val directory = temporary.newFolder()
        val file = csv(directory, "failed.csv", "partial")
        val closed = CompletableFuture<Unit>().apply { completeExceptionally(IOException("disk full")) }
        val result = exporter(directory).afterClosed(file, closed).get(5, TimeUnit.SECONDS)
        assertTrue(result.error!!.contains("disk full"))
        assertEquals(0, store.inserted); assertEquals("partial", file.readText())
    }

    @Test fun repeatExportIncludingANewExporterReusesSamePublishedBytes() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "csv data")
        val first = exporter(directory).export(file)
        val repeat = exporter(directory).export(file)
        assertEquals(first.published, repeat.published)
        assertTrue(repeat.reused); assertEquals(1, store.inserted)
        assertEquals("csv data", file.readText())
    }

    @Test fun sameNameDifferentContentsAreNeverOverwrittenAndAlternativeAlsoDeduplicates() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "new bytes")
        val old = store.add("log.csv", "old bytes".toByteArray())
        val result = exporter(directory).export(file)
        assertNull(result.error)
        assertTrue(result.published!!.displayName.startsWith("log__"))
        assertEquals("old bytes", String(store.rows.getValue(old.id).bytes))
        assertEquals(2, store.rows.size)
        assertTrue(exporter(directory).export(file).reused)
        assertEquals(2, store.rows.size)
    }

    @Test fun providerRenamedFileAndForeignUnreadableNameCollisionAreHandledSafely() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "ours")
        store.rename = { name -> name.removeSuffix(".csv") + " (1).csv" }
        val first = exporter(directory).export(file)
        assertEquals("log (1).csv", first.published!!.displayName)
        assertTrue(exporter(directory).export(file).reused)
        assertEquals(1, store.inserted)

        val otherFile = csv(directory, "foreign.csv", "our foreign-named log")
        val foreign = store.add("foreign.csv", "another app".toByteArray())
        store.deniedIds.add(foreign.id)
        val renamed = exporter(directory).export(otherFile)
        assertNotNull(renamed.published)
        assertTrue(exporter(directory).export(otherFile).reused)
        assertEquals("another app", String(store.rows.getValue(foreign.id).bytes))
    }

    @Test fun copyingClosingOrPublishingFailureDeletesOnlyTheNewPendingRow() {
        for (failure in listOf("open", "write", "close", "publish")) {
            val directory = temporary.newFolder()
            val file = csv(directory, "log.csv", "whole original")
            val downloads = FakeDownloads().apply { fail = failure; add("unrelated.csv", "keep".toByteArray()) }
            val result = DepthLogExporter(DepthLogFiles(directory), downloads).export(file)
            assertNotNull("$failure should fail", result.error)
            assertEquals("whole original", file.readText())
            assertEquals(listOf("unrelated.csv"), downloads.rows.values.map { it.log.displayName })
            assertEquals(1, downloads.deleted)
        }
    }

    @Test fun incompleteMissingAndOutsideFilesNeverCreateDownloads() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "in progress")
        DepthLogFiles.incompleteMarker(file).createNewFile()
        assertNotNull(exporter(directory).export(file).error)
        assertNotNull(exporter(directory).export(File(directory, "missing.csv")).error)
        assertNotNull(exporter(directory).export(temporary.newFile("outside.csv")).error)
        assertEquals(0, store.inserted)
    }

    @Test fun sourceChangingDuringCopyCannotPublishAPartialOrMixedFile() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "original")
        store.afterCopy = { file.appendText("late write") }
        val result = exporter(directory).export(file)
        assertNotNull(result.error)
        assertTrue(store.rows.isEmpty())
        assertEquals("originallate write", file.readText())
    }

    @Test fun concurrentlyRequestedAutoAndManualExportsDoNotCreateDuplicates() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "same")
        val a = CompletableFuture.supplyAsync { exporter(directory).export(file) }
        val b = CompletableFuture.supplyAsync { exporter(directory).export(file) }
        val results = listOf(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS))
        assertTrue(results.all { it.error == null })
        assertEquals(1, results.count { it.reused })
        assertEquals(1, store.inserted)
    }

    @Test fun readQueryAndCleanupFailuresAreReportedWithoutDeletingInternalCsv() {
        val directory = temporary.newFolder()
        val file = csv(directory, "log.csv", "preserve")
        store.fail = "query"
        assertNotNull(exporter(directory).export(file).error)
        assertEquals(0, store.inserted)
        store.add("log.csv", "previous".toByteArray()); store.fail = "read"
        assertNotNull(exporter(directory).export(file).error)
        assertEquals(0, store.inserted)
        assertEquals("previous", String(store.rows.values.single().bytes))
        store.fail = "write"; store.failDelete = true
        val result = exporter(directory).export(file)
        assertTrue(result.error!!.contains("정리 실패"))
        assertEquals("previous", String(store.rows.values.single { it.published }.bytes))
        assertTrue(store.rows.values.any { !it.published })
        assertEquals("preserve", file.readText())
    }

    private fun csv(directory: File, name: String, text: String) = File(directory, name).apply { writeText(text) }

    private class FakeDownloads : DepthLogDownloadStore {
        data class Row(val log: PublicDepthLog, var bytes: ByteArray, var published: Boolean, var closed: Boolean = false)
        val rows = linkedMapOf<String, Row>()
        val deniedIds = mutableSetOf<String>()
        var inserted = 0; var deleted = 0; var fail: String? = null; var failDelete = false
        var afterCopy: () -> Unit = {}
        var rename: (String) -> String = { it }
        fun add(name: String, bytes: ByteArray): PublicDepthLog {
            val log = PublicDepthLog("uri-${rows.size}-${name}", name)
            rows[log.id] = Row(log, bytes, true, true)
            return log
        }
        override fun listPublished(): List<PublicDepthLog> {
            if (fail == "query") throw IOException("query failed")
            return rows.values.filter { it.published }.map { it.log }
        }
        override fun openRead(log: PublicDepthLog): InputStream {
            if (fail == "read") throw IOException("read failed")
            if (log.id in deniedIds) throw SecurityException("foreign file")
            return ByteArrayInputStream(rows.getValue(log.id).bytes)
        }
        override fun createPending(name: String): PublicDepthLog {
            inserted++
            val log = PublicDepthLog("new-$inserted", name)
            rows[log.id] = Row(log, byteArrayOf(), false)
            return log
        }
        override fun openWrite(log: PublicDepthLog): OutputStream {
            if (fail == "open") throw IOException("open failed")
            return object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    assertFalse(rows.getValue(log.id).published)
                    if (fail == "write") throw IOException("write failed")
                    super.write(bytes, offset, length)
                }
                override fun close() {
                    if (fail == "close") throw IOException("close failed")
                    rows.getValue(log.id).bytes = toByteArray()
                    rows.getValue(log.id).closed = true
                    afterCopy()
                }
            }
        }
        override fun publish(log: PublicDepthLog): PublicDepthLog {
            if (fail == "publish") throw IOException("publish failed")
            val row = rows.getValue(log.id)
            assertTrue(row.closed)
            val actual = log.copy(displayName = rename(log.displayName))
            rows[log.id] = row.copy(log = actual, published = true)
            return actual
        }
        override fun deletePending(log: PublicDepthLog) {
            if (failDelete) throw IOException("cleanup failed")
            assertFalse(rows.getValue(log.id).published)
            rows.remove(log.id); deleted++
        }
    }
}
