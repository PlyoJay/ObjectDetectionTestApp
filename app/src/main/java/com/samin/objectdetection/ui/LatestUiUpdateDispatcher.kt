package com.samin.objectdetection.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Nonblocking latest-only handoff. Scheduling never writes a previously read update back. */
internal class LatestUiUpdateDispatcher(private val post: (() -> Unit) -> Unit) {
    private val pending = AtomicReference<(() -> Unit)?>(null)
    private val scheduled = AtomicBoolean(false)
    @Volatile private var closed = false

    fun submit(update: () -> Unit) {
        if (closed) return
        pending.set(update)
        if (closed) { pending.set(null); return }
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        post {
            try {
                val update = pending.getAndSet(null)
                if (!closed) update?.invoke()
            } finally {
                scheduled.set(false)
                if (!closed && pending.get() != null) schedule()
            }
        }
    }

    fun close() {
        closed = true
        pending.set(null)
    }
}
