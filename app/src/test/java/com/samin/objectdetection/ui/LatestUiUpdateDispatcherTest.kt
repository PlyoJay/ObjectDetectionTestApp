package com.samin.objectdetection.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class LatestUiUpdateDispatcherTest {
    @Test fun queuedUpdatesAreCoalescedWithoutWaitingForUi() {
        val queue = ArrayDeque<() -> Unit>()
        val applied = mutableListOf<Int>()
        val dispatcher = LatestUiUpdateDispatcher { queue.addLast(it) }
        repeat(100) { id -> dispatcher.submit { applied.add(id) } }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(listOf(99), applied)
        assertTrue(queue.isEmpty())
    }

    @Test fun newerUpdateArrivingDuringDrainIsAppliedNext() {
        val queue = ArrayDeque<() -> Unit>()
        val dispatcher = LatestUiUpdateDispatcher { queue.addLast(it) }
        val applied = mutableListOf<Int>()
        dispatcher.submit {
            applied.add(1)
            dispatcher.submit { applied.add(2) }
            dispatcher.submit { applied.add(3) }
        }
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals(listOf(1, 3), applied)
    }

    @Test fun concurrentProducersCannotLoseLastSubmissionWhileUiIsBlocked() {
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val dispatcher = LatestUiUpdateDispatcher { queue.add(it) }
        val start = CountDownLatch(1)
        var applied = -1
        val producers = (1..4).map { id -> thread { start.await(); repeat(1000) { dispatcher.submit { applied = id } } } }
        start.countDown()
        producers.forEach { it.join() }
        dispatcher.submit { applied = 99 }
        while (true) (queue.poll() ?: break).invoke()
        assertEquals(99, applied)
        assertTrue(queue.isEmpty())
    }

    @Test fun closedActivityDropsQueuedAndFutureUpdates() {
        val queue = ArrayDeque<() -> Unit>()
        val dispatcher = LatestUiUpdateDispatcher { queue.addLast(it) }
        var invoked = false
        dispatcher.submit { invoked = true }
        dispatcher.close()
        dispatcher.submit { invoked = true }
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertFalse(invoked)
    }
}
