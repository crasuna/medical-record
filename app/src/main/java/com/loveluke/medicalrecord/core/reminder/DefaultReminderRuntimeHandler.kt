package com.loveluke.medicalrecord.core.reminder

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultReminderRuntimeHandler(
    private val store: ReminderScheduleStore,
    private val scheduler: SystemReminderScheduler,
    private val notificationPublisher: ReminderDeliveryPublisher,
    private val clock: Clock = Clock.systemUTC(),
) : ReminderRuntimeHandler {
    private val deliveryMutex = Mutex()

    override suspend fun onAlarm(reminderId: String, scheduledAt: Instant) =
        deliveryMutex.withLock {
            val deliveredAt = maxOf(clock.instant(), scheduledAt)
            store.findDueDelivery(reminderId, scheduledAt, deliveredAt)
                ?.publishDistinctNotifications()
            scheduler.reconcileAfter(deliveredAt)
            Unit
        }

    override suspend fun reconcile() = deliveryMutex.withLock {
        val deliveredAt = clock.instant()
        val pendingDelivery = store.findPendingDueDelivery(deliveredAt)
        if (pendingDelivery == null) {
            scheduler.reconcile()
        } else {
            pendingDelivery.publishDistinctNotifications()
            scheduler.reconcileAfter(deliveredAt)
        }
        Unit
    }

    private fun ReminderDeliveryBatch.publishDistinctNotifications() {
        notifications
            .distinctBy { content -> content.reminderId to content.scheduledAt }
            .forEach(notificationPublisher::publish)
    }
}
