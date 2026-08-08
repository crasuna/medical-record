package com.loveluke.medicalrecord.core.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ReminderNotificationGateIntegrationTest {
    @Test
    fun `disabled medication channel blocks gateway scheduler and publisher consistently`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    medicationReminderChannelId(context),
                    "Medication reminders",
                    NotificationManager.IMPORTANCE_NONE,
                ),
            )

            val permissionGateway = ReminderPermissionGateway(context)
            val snapshot = permissionGateway.snapshot()

            assertFalse(snapshot.notificationsEnabled)
            assertEquals(
                ReminderNotificationBlockReason.CHANNEL_DISABLED,
                snapshot.notificationBlockReason,
            )
            val settingsIntent = permissionGateway.notificationSettingsIntent(snapshot)
            assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settingsIntent.action)
            assertEquals(
                medicationReminderChannelId(context),
                settingsIntent.getStringExtra(Settings.EXTRA_CHANNEL_ID),
            )

            val store = NotificationGateScheduleStore()
            val schedulingState = SystemReminderScheduler(
                context = context,
                store = store,
                clock = Clock.fixed(NOW, ZoneOffset.UTC),
                zoneIdProvider = { ZoneOffset.UTC },
            ).reconcile()

            assertEquals(ReminderSchedulingState.NotificationsUnavailable, schedulingState)
            assertEquals(0, store.nextOccurrenceQueries)

            val published = ReminderNotificationPublisher(context).publish(
                ReminderNotificationContent(
                    reminderId = "reminder-id",
                    medicationId = "medication-id",
                    medicationName = "Medication",
                    dose = "1 tablet",
                    scheduledAt = NOW,
                ),
            )

            assertFalse(published)
            assertTrue(shadowOf(notificationManager).activeNotifications.isEmpty())
        }

    private class NotificationGateScheduleStore : ReminderScheduleStore {
        var nextOccurrenceQueries = 0

        override suspend fun findNextOccurrence(
            after: Instant,
            zoneId: ZoneId,
        ): ReminderOccurrence? {
            nextOccurrenceQueries += 1
            return null
        }

        override suspend fun findDueDelivery(
            anchorReminderId: String,
            scheduledAt: Instant,
            deliveredAt: Instant,
        ): ReminderDeliveryBatch? = null

        override suspend fun findPendingDueDelivery(
            atOrBefore: Instant,
        ): ReminderDeliveryBatch? = null

        override suspend fun recordSchedulingState(state: ReminderSchedulingState) = Unit
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-08T08:00:00Z")
    }
}
