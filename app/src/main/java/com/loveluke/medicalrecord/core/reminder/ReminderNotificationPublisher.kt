package com.loveluke.medicalrecord.core.reminder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.loveluke.medicalrecord.R
import java.util.Date

fun interface ReminderDeliveryPublisher {
    fun publish(content: ReminderNotificationContent): Boolean
}

class ReminderNotificationPublisher(
    context: Context,
) : ReminderDeliveryPublisher {
    private val applicationContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(applicationContext)
    private val notificationAvailability = AndroidReminderNotificationAvailability(applicationContext)
    private val channelId = medicationReminderChannelId(applicationContext)

    fun createChannel() {
        val channel = NotificationChannel(
            channelId,
            applicationContext.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = applicationContext.getString(R.string.reminder_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    override fun publish(content: ReminderNotificationContent): Boolean {
        createChannel()
        if (!notificationAvailability.current().isAvailable) return false
        val time = DateFormat.getTimeFormat(applicationContext).format(Date.from(content.scheduledAt))
        val privateBody = content.dose?.takeIf(String::isNotBlank)?.let { dose ->
            applicationContext.getString(R.string.reminder_private_body_with_dose, dose, time)
        } ?: applicationContext.getString(R.string.reminder_private_body_time_only, time)

        val publicVersion = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(applicationContext.getString(R.string.reminder_public_title))
            .setContentText(applicationContext.getString(R.string.reminder_public_body))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(
                content.medicationName.takeIf(String::isNotBlank)
                    ?: applicationContext.getString(R.string.reminder_public_title),
            )
            .setContentText(privateBody)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(contentPendingIntent(content.medicationId))
            .setAutoCancel(true)
            .build()

        return try {
            notificationManager.notify(stableNotificationId(content.reminderId), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun contentPendingIntent(medicationId: String): PendingIntent? {
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply {
                action = "${applicationContext.packageName}.action.OPEN_MEDICATION"
                data = "medical-record://${applicationContext.packageName}/medication/$medicationId".toUri()
                putExtra(EXTRA_MEDICATION_ID, medicationId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } ?: return null
        return PendingIntent.getActivity(
            applicationContext,
            stableNotificationId(medicationId),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stableNotificationId(id: String): Int = id.hashCode() and Int.MAX_VALUE

    companion object {
        const val EXTRA_MEDICATION_ID = "medication_id"
    }
}
