package com.loveluke.medicalrecord.core.reminder

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderNotificationAvailabilityTest {
    @Test
    fun `availability requires runtime permission package switch and medication channel`() {
        assertEquals(
            ReminderNotificationBlockReason.RUNTIME_PERMISSION,
            availability(
                runtimePermissionGranted = false,
                packageNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
            ).blockReason,
        )
        assertEquals(
            ReminderNotificationBlockReason.APPLICATION_DISABLED,
            availability(
                runtimePermissionGranted = true,
                packageNotificationsEnabled = false,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
            ).blockReason,
        )
        assertEquals(
            ReminderNotificationBlockReason.CHANNEL_DISABLED,
            availability(
                runtimePermissionGranted = true,
                packageNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_NONE,
            ).blockReason,
        )
    }

    @Test
    fun `missing channel is provisionable while any non-none importance is available`() {
        val missingChannel = availability(
            runtimePermissionGranted = true,
            packageNotificationsEnabled = true,
            channelImportance = null,
        )
        val lowImportance = availability(
            runtimePermissionGranted = true,
            packageNotificationsEnabled = true,
            channelImportance = NotificationManager.IMPORTANCE_LOW,
        )

        assertTrue(missingChannel.isAvailable)
        assertNull(missingChannel.blockReason)
        assertTrue(lowImportance.isAvailable)
        assertNull(lowImportance.blockReason)
    }

    @Test
    fun `blocked channel is unavailable even when package notifications are enabled`() {
        val result = availability(
            runtimePermissionGranted = true,
            packageNotificationsEnabled = true,
            channelImportance = NotificationManager.IMPORTANCE_NONE,
        )

        assertFalse(result.isAvailable)
    }
}
