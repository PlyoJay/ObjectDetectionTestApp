package com.samin.objectdetection.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AnalyzerShutdownTest {
    @Test(timeout = 5000) fun shutdownReturnsWhileInferenceRunsAndResourceCloseWaitsForCompletion() {
        val executor = Executors.newSingleThreadExecutor()
        val running = CountDownLatch(1); val release = CountDownLatch(1)
        val finishedInference = AtomicBoolean(); val closedDetector = AtomicBoolean()
        executor.execute { running.countDown(); release.await(); finishedInference.set(true) }
        assertTrue(running.await(2, TimeUnit.SECONDS))
        try {
            val stopped = shutdownAnalyzer(executor)
            val closed = stopped.thenRun { assertTrue(finishedInference.get()); closedDetector.set(true) }
            assertFalse(stopped.isDone); assertFalse(closedDetector.get())
            release.countDown(); closed.get(2, TimeUnit.SECONDS)
            assertTrue(closedDetector.get()); assertTrue(executor.isTerminated)
        } finally { release.countDown(); executor.shutdown() }
    }
}
