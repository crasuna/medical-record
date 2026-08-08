package com.loveluke.medicalrecord.core.reminder

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemReminderSchedulerTest {
    private val now = Instant.parse("2026-08-08T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `missing notification permission keeps intent but cancels platform alarm`() = runTest {
        val store = FakeStore(next = occurrence())
        val platform = FakePlatform()
        val scheduler = scheduler(store, platform, notificationsAllowed = false)

        val state = scheduler.reconcile()

        assertEquals(ReminderSchedulingState.NotificationsUnavailable, state)
        assertEquals(listOf(state), store.recordedStates)
        assertEquals(1, platform.cancelCount)
        assertNull(store.requestedAfter)
    }

    @Test
    fun `no future reminder cancels the one global platform alarm`() = runTest {
        val store = FakeStore(next = null)
        val platform = FakePlatform()

        val state = scheduler(store, platform).reconcile()

        assertEquals(ReminderSchedulingState.NoFutureReminder, state)
        assertEquals(1, platform.cancelCount)
        assertEquals(now, store.requestedAfter)
        assertEquals(zone, store.requestedZone)
    }

    @Test
    fun `scheduler persists exact or inexact precision returned by platform`() = runTest {
        val next = occurrence()
        val store = FakeStore(next = next)
        val platform = FakePlatform(precision = AlarmPrecision.INEXACT)

        val state = scheduler(store, platform).reconcile()

        assertEquals(
            ReminderSchedulingState.Scheduled(
                reminderId = next.reminderId,
                triggerAt = next.triggerAt,
                precision = AlarmPrecision.INEXACT,
            ),
            state,
        )
        assertEquals(listOf(next), platform.scheduled)
        assertEquals(listOf(state), store.recordedStates)
        assertEquals(0, platform.cancelCount)
    }

    @Test
    fun `alarm delivery can advance search floor past scheduled occurrence`() = runTest {
        val store = FakeStore(next = null)
        val platform = FakePlatform()
        val scheduledAt = now.plusSeconds(60)

        scheduler(store, platform).reconcileAfter(scheduledAt)

        assertEquals(scheduledAt, store.requestedAfter)
    }

    private fun scheduler(
        store: FakeStore,
        platform: FakePlatform,
        notificationsAllowed: Boolean = true,
    ) = SystemReminderScheduler(
        store = store,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        zoneIdProvider = { zone },
        notificationAccess = ReminderNotificationAccess { notificationsAllowed },
        alarmPlatform = platform,
    )

    private fun occurrence() = ReminderOccurrence(
        reminderId = "reminder-id",
        patientId = "patient-id",
        medicationId = "medication-id",
        triggerAt = now.plusSeconds(3_600),
    )

    private class FakeStore(
        private val next: ReminderOccurrence?,
    ) : ReminderScheduleStore {
        var requestedAfter: Instant? = null
        var requestedZone: ZoneId? = null
        val recordedStates = mutableListOf<ReminderSchedulingState>()

        override suspend fun findNextOccurrence(
            after: Instant,
            zoneId: ZoneId,
        ): ReminderOccurrence? {
            requestedAfter = after
            requestedZone = zoneId
            return next
        }

        override suspend fun findDueDelivery(
            anchorReminderId: String,
            scheduledAt: Instant,
            deliveredAt: Instant,
        ): ReminderDeliveryBatch? = null

        override suspend fun findPendingDueDelivery(
            atOrBefore: Instant,
        ): ReminderDeliveryBatch? = null

        override suspend fun recordSchedulingState(state: ReminderSchedulingState) {
            recordedStates += state
        }
    }

    private class FakePlatform(
        private val precision: AlarmPrecision = AlarmPrecision.EXACT,
    ) : ReminderAlarmPlatform {
        val scheduled = mutableListOf<ReminderOccurrence>()
        var cancelCount = 0

        override fun schedule(occurrence: ReminderOccurrence): AlarmPrecision {
            scheduled += occurrence
            return precision
        }

        override fun cancel() {
            cancelCount += 1
        }
    }
}
