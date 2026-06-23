package com.samin.objectdetection.warning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningCooldownManagerTest {

    @Test
    fun canNotify_doesNotMutateLastNotifiedTime() {
        val manager = WarningCooldownManager()
        val key = "crowd_HIGH"

        assertTrue(manager.canNotify(key, nowMs = 1_000L, cooldownMs = 5_000L))
        assertTrue(manager.canNotify(key, nowMs = 1_100L, cooldownMs = 5_000L))
    }

    @Test
    fun markNotified_updatesLastNotifiedTime() {
        val manager = WarningCooldownManager()
        val key = "crowd_HIGH"

        manager.markNotified(key, nowMs = 1_000L)

        assertFalse(manager.canNotify(key, nowMs = 5_999L, cooldownMs = 5_000L))
        assertTrue(manager.canNotify(key, nowMs = 6_000L, cooldownMs = 5_000L))
    }
}
