package com.loveluke.medicalrecord.core.reminder

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

data class ReminderPermissionSnapshot(
    val notificationsEnabled: Boolean,
    val notificationBlockReason: ReminderNotificationBlockReason?,
    val exactAlarmsEnabled: Boolean,
)

/** Read-only permission state plus explicit settings intents; it never launches or loops prompts. */
class ReminderPermissionGateway(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val notificationAvailability =
        AndroidReminderNotificationAvailability(applicationContext)

    fun snapshot(): ReminderPermissionSnapshot {
        val availability = notificationAvailability.current()
        return ReminderPermissionSnapshot(
            notificationsEnabled = availability.isAvailable,
            notificationBlockReason = availability.blockReason,
            exactAlarmsEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms(),
        )
    }

    fun exactAlarmSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            "package:${applicationContext.packageName}".toUri(),
        )
    }

    fun notificationSettingsIntent(snapshot: ReminderPermissionSnapshot = snapshot()): Intent =
        if (snapshot.notificationBlockReason == ReminderNotificationBlockReason.CHANNEL_DISABLED) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
                .putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    medicationReminderChannelId(applicationContext),
                )
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
        }
}
