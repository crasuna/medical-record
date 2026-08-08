package com.loveluke.medicalrecord.core.reminder

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultReminderRuntimeHandlerTest {
    private val scheduledAt = Instant.parse("2026-08-08T08:00:00Z")

    @Test
    fun `one global alarm publishes every distinct reminder due at the same instant`() = runTest {
        val first = content(reminderId = "first-reminder", medicationId = "first-medication")
        val second = content(reminderId = "second-reminder", medicationId = "second-medication")
        val store = FakeStore(
            dueDelivery = ReminderDeliveryBatch(
                anchorReminderId = first.reminderId,
                scheduledAt = scheduledAt,
                notifications = listOf(first, second, first),
            ),
        )
        val platform = FakePlatform()
        val published = mutableListOf<ReminderNotificationContent>()
        val scheduler = scheduler(store, platform)
        val handler = DefaultReminderRuntimeHandler(
            store = store,
            scheduler = scheduler,
            notificationPublisher = ReminderDeliveryPublisher {
                published += it
                true
            },
            clock = Clock.fixed(scheduledAt.minusSeconds(1), ZoneOffset.UTC),
        )

        handler.onAlarm(first.reminderId, scheduledAt)

        assertEquals(listOf(first, second), published)
        assertEquals(scheduledAt, store.nextSearchAfter)
        assertEquals(1, platform.cancelCount)
    }

    @Test
    fun `stale edited alarm is suppressed but schedule is still reconciled`() = runTest {
        val store = FakeStore(dueDelivery = null)
        val platform = FakePlatform()
        var published = false
        val handler = DefaultReminderRuntimeHandler(
            store = store,
            scheduler = scheduler(store, platform),
            notificationPublisher = ReminderDeliveryPublisher {
                published = true
                true
            },
            clock = Clock.fixed(scheduledAt.plusSeconds(30), ZoneOffset.UTC),
        )

        handler.onAlarm("removed-reminder", scheduledAt)

        assertTrue(!published)
        assertEquals(scheduledAt.plusSeconds(30), store.nextSearchAfter)
        assertEquals(1, platform.cancelCount)
    }

    @Test
    fun `replayed alarm does not publish an already advanced delivery batch twice`() = runTest {
        val content = content()
        val store = FakeStore(
            dueDelivery = ReminderDeliveryBatch(
                anchorReminderId = content.reminderId,
                scheduledAt = scheduledAt,
                notifications = listOf(content),
            ),
        )
        val published = mutableListOf<ReminderNotificationContent>()
        val handler = DefaultReminderRuntimeHandler(
            store = store,
            scheduler = scheduler(store, FakePlatform()),
            notificationPublisher = ReminderDeliveryPublisher {
                published += it
                true
            },
            clock = Clock.fixed(scheduledAt, ZoneOffset.UTC),
        )

        handler.onAlarm(content.reminderId, scheduledAt)
        handler.onAlarm(content.reminderId, scheduledAt)

        assertEquals(listOf(content), published)
    }

    @Test
    fun `reconcile compensates persisted due delivery before searching from now`() = runTest {
        val content = content()
        val store = FakeStore(
            dueDelivery = ReminderDeliveryBatch(
                anchorReminderId = content.reminderId,
                scheduledAt = scheduledAt,
                notifications = listOf(content),
            ),
        )
        val published = mutableListOf<ReminderNotificationContent>()
        val now = scheduledAt.plusSeconds(30)
        val handler = DefaultReminderRuntimeHandler(
            store = store,
            scheduler = scheduler(store, FakePlatform()),
            notificationPublisher = ReminderDeliveryPublisher {
                published += it
                true
            },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        handler.reconcile()

        assertEquals(listOf(content), published)
        assertEquals(now, store.nextSearchAfter)
    }

    @Test
    fun `delayed global alarm publishes every distinct occurrence in its verified due window`() =
        runTest {
            val eightOClock = content(
                reminderId = "eight-reminder",
                medicationId = "eight-medication",
            )
            val eightOhFive = content(
                reminderId = "eight-oh-five-reminder",
                medicationId = "eight-oh-five-medication",
                occurrenceAt = scheduledAt.plusSeconds(5 * 60),
            )
            val deliveredAt = scheduledAt.plusSeconds(10 * 60)
            val store = FakeStore(
                dueDelivery = ReminderDeliveryBatch(
                    anchorReminderId = eightOClock.reminderId,
                    scheduledAt = scheduledAt,
                    notifications = listOf(eightOClock, eightOhFive),
                ),
            )
            val published = mutableListOf<ReminderNotificationContent>()
            val handler = DefaultReminderRuntimeHandler(
                store = store,
                scheduler = scheduler(store, FakePlatform()),
                notificationPublisher = ReminderDeliveryPublisher {
                    published += it
                    true
                },
                clock = Clock.fixed(deliveredAt, ZoneOffset.UTC),
            )

            handler.onAlarm(eightOClock.reminderId, scheduledAt)

            assertEquals(listOf(eightOClock, eightOhFive), published)
            assertEquals(deliveredAt, store.nextSearchAfter)
            assertEquals(deliveredAt, store.lastDeliveryWindowEnd)
        }

    private fun scheduler(store: FakeStore, platform: FakePlatform) = SystemReminderScheduler(
        store = store,
        clock = Clock.fixed(scheduledAt, ZoneOffset.UTC),
        zoneIdProvider = { ZoneOffset.UTC },
        notificationAccess = ReminderNotificationAccess { true },
        alarmPlatform = platform,
    )

    private fun content(
        reminderId: String = "reminder-id",
        medicationId: String = "medication-id",
        occurrenceAt: Instant = scheduledAt,
    ) = ReminderNotificationContent(
        reminderId = reminderId,
        medicationId = medicationId,
        medicationName = "Medication",
        dose = "1 tablet",
        scheduledAt = occurrenceAt,
    )

    private class FakeStore(
        private var dueDelivery: ReminderDeliveryBatch?,
    ) : ReminderScheduleStore {
        var nextSearchAfter: Instant? = null
        var lastDeliveryWindowEnd: Instant? = null

        override suspend fun findNextOccurrence(
            after: Instant,
            zoneId: ZoneId,
        ): ReminderOccurrence? {
            nextSearchAfter = after
            return null
        }

        override suspend fun findDueDelivery(
            anchorReminderId: String,
            scheduledAt: Instant,
            deliveredAt: Instant,
        ): ReminderDeliveryBatch? = dueDelivery?.takeIf {
            lastDeliveryWindowEnd = deliveredAt
            it.anchorReminderId == anchorReminderId && it.scheduledAt == scheduledAt
        }

        override suspend fun findPendingDueDelivery(
            atOrBefore: Instant,
        ): ReminderDeliveryBatch? = dueDelivery?.takeIf { !it.scheduledAt.isAfter(atOrBefore) }

        override suspend fun recordSchedulingState(state: ReminderSchedulingState) {
            dueDelivery = null
        }
    }

    private class FakePlatform : ReminderAlarmPlatform {
        var cancelCount = 0

        override fun schedule(occurrence: ReminderOccurrence): AlarmPrecision = AlarmPrecision.EXACT

        override fun cancel() {
            cancelCount += 1
        }
    }
}
