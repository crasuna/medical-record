package com.loveluke.medicalrecord.app.reminder

import com.loveluke.medicalrecord.app.access.ReminderAccessGate
import com.loveluke.medicalrecord.app.access.ReminderAccessResult
import com.loveluke.medicalrecord.core.reminder.ReminderRuntime
import com.loveluke.medicalrecord.core.reminder.ReminderRuntimeHandler
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRuntimeCoordinatorTest {
    @Test
    fun `cold start bridge executes alarm and reconcile only inside minimal access gate`() = runTest {
        val handler = RecordingReminderHandler()
        val coordinator = ReminderRuntimeCoordinator(handlerFactory = { handler })
        var gateCalls = 0
        coordinator.installColdStartBridge(
            ReminderAccessGate { block ->
                gateCalls += 1
                block()
                ReminderAccessResult.Completed
            },
        )
        val scheduledAt = Instant.parse("2026-08-08T08:30:00Z")

        requireNotNull(ReminderRuntime.currentHandler()).onAlarm("reminder-id", scheduledAt)
        // Handler creation must not replace the access-gated process bridge.
        requireNotNull(ReminderRuntime.currentHandler()).reconcile()

        assertEquals(2, gateCalls)
        assertEquals(listOf("reminder-id" to scheduledAt), handler.alarms)
        assertEquals(1, handler.reconcileCalls)
        assertFalse(coordinator.hasRuntimeFailure())
    }

    @Test
    fun `unavailable minimal access does not create database backed handler and records failure`() =
        runTest {
            var handlerCreations = 0
            val coordinator = ReminderRuntimeCoordinator(
                handlerFactory = {
                    handlerCreations += 1
                    RecordingReminderHandler()
                },
            )
            coordinator.installColdStartBridge(
                ReminderAccessGate { ReminderAccessResult.Unavailable },
            )

            requireNotNull(ReminderRuntime.currentHandler()).reconcile()

            assertEquals(0, handlerCreations)
            assertTrue(coordinator.hasRuntimeFailure())
        }

    @Test
    fun `cold bridge rethrows cancellation from access gate`() = runTest {
        val coordinator = ReminderRuntimeCoordinator(handlerFactory = { RecordingReminderHandler() })
        coordinator.installColdStartBridge(
            ReminderAccessGate { throw CancellationException("test cancellation") },
        )
        var cancellationObserved = false

        try {
            requireNotNull(ReminderRuntime.currentHandler()).reconcile()
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `timed out alarm is retained for bounded access retry and delivered once`() = runTest {
        val handler = RecordingReminderHandler()
        var gateCalls = 0
        val coordinator = ReminderRuntimeCoordinator(
            handlerFactory = { handler },
            retryScope = backgroundScope,
            retryDelayMillis = 100L,
            maxRetryAttempts = 2,
        )
        coordinator.installColdStartBridge(
            ReminderAccessGate { block ->
                gateCalls += 1
                if (gateCalls == 1) {
                    ReminderAccessResult.TimedOut
                } else {
                    block()
                    ReminderAccessResult.Completed
                }
            },
        )
        val scheduledAt = Instant.parse("2026-08-08T08:30:00Z")

        requireNotNull(ReminderRuntime.currentHandler()).onAlarm("reminder-id", scheduledAt)
        assertTrue(handler.alarms.isEmpty())

        advanceTimeBy(100L)
        runCurrent()

        assertEquals(2, gateCalls)
        assertEquals(listOf("reminder-id" to scheduledAt), handler.alarms)
        assertFalse(coordinator.hasRuntimeFailure())
    }
}

private class RecordingReminderHandler : ReminderRuntimeHandler {
    val alarms = mutableListOf<Pair<String, Instant>>()
    var reconcileCalls = 0

    override suspend fun onAlarm(reminderId: String, scheduledAt: Instant) {
        alarms += reminderId to scheduledAt
    }

    override suspend fun reconcile() {
        reconcileCalls += 1
    }
}
