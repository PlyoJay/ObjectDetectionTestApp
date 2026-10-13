package com.samin.objectdetection.logging

import java.io.BufferedWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/** One owner writes in submission order. Frame work is bounded; close always drains accepted work.
 * Each complete line is flushed so low-FPS sessions do not retain a 50-frame tail in memory. */
class OrderedLogWriter(
    private val writer: BufferedWriter,
    capacity: Int = 512,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val slots = Semaphore(capacity)
    private val completion = CompletableFuture<Unit>()
    private var closing = false
    @Volatile var failure: Throwable? = null
        private set
    val droppedLines = AtomicLong()

    fun append(line: () -> String): Boolean = synchronized(lock) {
        if (closing || !slots.tryAcquire()) {
            droppedLines.incrementAndGet()
            return false
        }
        executor.execute {
            try {
                if (failure == null) { writer.appendLine(line()); writer.flush() }
                else droppedLines.incrementAndGet()
            } catch (error: Throwable) {
                fail(error)
            } finally { slots.release() }
        }
        true
    }

    fun close(footer: () -> String = { "" }): CompletableFuture<Unit> = synchronized(lock) {
        if (!closing) {
            closing = true
            executor.execute {
                try {
                    if (failure == null) {
                        val text = footer()
                        if (text.isNotEmpty()) writer.appendLine(text)
                        writer.flush()
                    }
                } catch (error: Throwable) { fail(error) }
                finally {
                    try { writer.close() } catch (error: Throwable) { fail(error) }
                    val error = failure
                    if (error == null) completion.complete(Unit) else completion.completeExceptionally(error)
                }
            }
            executor.shutdown()
        }
        completion
    }

    private fun fail(error: Throwable) {
        if (failure != null) return
        failure = error
        close()
        runCatching { onFailure(error) }
    }
}
