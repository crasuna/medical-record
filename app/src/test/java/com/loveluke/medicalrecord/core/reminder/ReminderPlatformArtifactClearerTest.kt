package com.loveluke.medicalrecord.core.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ReminderPlatformArtifactClearerTest {
    @Test
    fun `clear removes the namespaced alarm pending intent and visible notifications`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = Instant.parse("2026-08-08T08:00:00Z")
        val occurrence = ReminderOccurrence(
            reminderId = "10000000-0000-4000-8000-000000000001",
            patientId = "10000000-0000-4000-8000-000000000002",
            medicationId = "10000000-0000-4000-8000-000000000003",
            triggerAt = now.plusSeconds(60),
        )
        val store = ArtifactTestScheduleStore(occurrence)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        SystemReminderScheduler(
            context = context,
            store = store,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            zoneIdProvider = { ZoneOffset.UTC },
        ).reconcile()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channelId = "artifact-clear-test"
        notificationManager.createNotificationChannel(
            NotificationChannel(channelId, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
        notificationManager.notify(
            42,
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Private medication")
                .build(),
        )

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        assertEquals(1, shadowOf(notificationManager).activeNotifications.size)

        assertTrue(ReminderPlatformArtifactClearer(context).clear())

        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
        assertTrue(shadowOf(notificationManager).activeNotifications.isEmpty())
    }
}

private class ArtifactTestScheduleStore(
    private val occurrence: ReminderOccurrence,
) : ReminderScheduleStore {
    override suspend fun findNextOccurrence(
        after: Instant,
        zoneId: ZoneId,
    ): ReminderOccurrence = occurrence

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
