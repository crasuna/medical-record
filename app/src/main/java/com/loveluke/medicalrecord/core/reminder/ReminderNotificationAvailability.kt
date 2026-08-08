package com.loveluke.medicalrecord.core.reminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class ReminderNotificationBlockReason {
    RUNTIME_PERMISSION,
    APPLICATION_DISABLED,
    CHANNEL_DISABLED,
}

internal data class ReminderNotificationAvailability(
    val isAvailable: Boolean,
    val blockReason: ReminderNotificationBlockReason?,
)

internal fun availability(
    runtimePermissionGranted: Boolean,
    packageNotificationsEnabled: Boolean,
    channelImportance: Int?,
): ReminderNotificationAvailability = when {
    !runtimePermissionGranted -> ReminderNotificationAvailability(
        isAvailable = false,
        blockReason = ReminderNotificationBlockReason.RUNTIME_PERMISSION,
    )

    !packageNotificationsEnabled -> ReminderNotificationAvailability(
        isAvailable = false,
        blockReason = ReminderNotificationBlockReason.APPLICATION_DISABLED,
    )

    channelImportance == NotificationManager.IMPORTANCE_NONE ->
        ReminderNotificationAvailability(
            isAvailable = false,
            blockReason = ReminderNotificationBlockReason.CHANNEL_DISABLED,
        )

    else -> ReminderNotificationAvailability(isAvailable = true, blockReason = null)
}

internal class AndroidReminderNotificationAvailability(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(applicationContext)
    private val platformNotificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)

    fun current(): ReminderNotificationAvailability {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        val packageNotificationsEnabled = runCatching {
            notificationManager.areNotificationsEnabled()
        }.getOrDefault(false)
        val channelImportance = runCatching {
            platformNotificationManager
                .getNotificationChannel(medicationReminderChannelId(applicationContext))
                ?.importance
        }.getOrDefault(NotificationManager.IMPORTANCE_NONE)
        return availability(
            runtimePermissionGranted = runtimePermissionGranted,
            packageNotificationsEnabled = packageNotificationsEnabled,
            channelImportance = channelImportance,
        )
    }
}

internal fun medicationReminderChannelId(context: Context): String =
    "${context.applicationContext.packageName}.medication_reminders"
