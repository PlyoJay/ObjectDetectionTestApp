package com.samin.objectdetection.logging

import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedWriter
import java.io.StringWriter
import java.io.Writer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrderedLogWriterTest {
    @Test fun closeDrainsAcceptedFramesInOrderAndRejectsLateFrames() {
        val text = StringWriter()
        val writer = OrderedLogWriter(BufferedWriter(text))
        repeat(100) { i -> assertTrue(writer.append { "frame=$i" }) }
        val done = writer.close { "END" }
        assertFalse(writer.append { "late" })
        done.get(5, TimeUnit.SECONDS)
        assertEquals((0..99).map { "frame=$it" } + "END", text.toString().trim().lines())
        assertEquals(1L, writer.droppedLines.get())
        assertSame(done, writer.close())
    }
    @Test fun blockedDiskKeepsBoundedQueueAndCloseStillDrains() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val text = StringWriter()
        val writer = OrderedLogWriter(BufferedWriter(text), 1)
        assertTrue(writer.append { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "first" })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(writer.append { "overflow" })
        val done = writer.close { "END dropped=${writer.droppedLines.get()}" }
        assertFalse(done.isDone)
        release.countDown(); done.get(5, TimeUnit.SECONDS)
        assertEquals("first\nEND dropped=1", text.toString().trim().replace("\r\n", "\n"))
    }
    @Test fun ioFailureClosesWriterAndCompletesStopExceptionallyWithoutHanging() {
        val failures = AtomicInteger(); val closed = CountDownLatch(1)
        val broken = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) { throw IOException("disk full") }
            override fun flush() {}
            override fun close() { closed.countDown() }
        }
        val writer = OrderedLogWriter(BufferedWriter(broken), 1) { failures.incrementAndGet() }
        assertTrue(writer.append { "frame" })
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        try { writer.close().get(5, TimeUnit.SECONDS); fail("Expected disk failure") }
        catch (error: ExecutionException) { assertTrue(error.cause is IOException) }
        assertEquals(1, failures.get())
        assertFalse(writer.append { "late" })
    }
}
