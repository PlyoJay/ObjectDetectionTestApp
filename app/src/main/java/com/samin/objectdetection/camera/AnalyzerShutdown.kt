package com.samin.objectdetection.camera

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** The caller returns immediately; resources may be released only after this future succeeds. */
internal fun shutdownAnalyzer(executor: ExecutorService): CompletableFuture<Unit> {
    val stopped = CompletableFuture<Unit>()
    executor.shutdown()
    Thread({
        try {
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* Wait without a destructive timeout. */ }
            stopped.complete(Unit)
        } catch (error: InterruptedException) {
            stopped.completeExceptionally(error)
            Thread.currentThread().interrupt()
        }
    }, "camera-shutdown").start()
    return stopped
}
