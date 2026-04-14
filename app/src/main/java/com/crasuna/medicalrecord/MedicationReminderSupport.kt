package com.crasuna.medicalrecord

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

const val EXTRA_OPEN_MEDICATION_ID = "com.crasuna.medicalrecord.extra.OPEN_MEDICATION_ID"

private const val ACTION_TRIGGER_MEDICATION_REMINDER = "com.crasuna.medicalrecord.action.TRIGGER_MEDICATION_REMINDER"
private const val EXTRA_REMINDER_ID = "com.crasuna.medicalrecord.extra.REMINDER_ID"
private const val MEDICATION_REMINDER_CHANNEL_ID = "medication_reminders"

internal fun computeNextReminderTrigger(
    schedule: MedicationReminderSchedule,
    now: ZonedDateTime,
    zoneId: ZoneId = now.zone,
): ZonedDateTime? {
    if (schedule.endDate != null && now.toLocalDate().isAfter(schedule.endDate)) {
        return null
    }

    val reminderTime = LocalTime.of(schedule.timeMinutesOfDay / 60, schedule.timeMinutesOfDay % 60)
    var candidateDate = maxOf(now.toLocalDate(), schedule.startDate)
    var candidate = ZonedDateTime.of(candidateDate, reminderTime, zoneId)
    if (!candidate.isAfter(now)) {
        candidateDate = candidateDate.plusDays(1)
        candidate = ZonedDateTime.of(candidateDate, reminderTime, zoneId)
    }

    if (schedule.endDate != null && candidateDate.isAfter(schedule.endDate)) {
        return null
    }
    return candidate
}

@Singleton
class MedicationReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicationReminderDao: MedicationReminderDao,
) {
    private val alarmManager: AlarmManager by lazy { context.getSystemService(AlarmManager::class.java) }
    private val zoneId: ZoneId by lazy { ZoneId.systemDefault() }

    fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MEDICATION_REMINDER_CHANNEL_ID,
            context.getString(R.string.medication_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.medication_reminder_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun needsNotificationPermissionRequest(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    fun areNotificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canScheduleExactAlarms(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    fun buildNotificationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun buildExactAlarmSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    suspend fun syncMedication(medicationId: String) {
        val schedules = medicationReminderDao.getSchedulesForMedication(medicationId)
        cancelReminderPendingIntents(schedules.map { it.reminderId }, dismissNotifications = false)
        if (!areNotificationsEnabled() || !canScheduleExactAlarms()) return

        schedules.forEach { schedule ->
            scheduleNextReminder(schedule)
        }
    }

    suspend fun rescheduleAll() {
        val schedules = medicationReminderDao.getAllSchedules()
        cancelReminderPendingIntents(schedules.map { it.reminderId }, dismissNotifications = false)
        if (!areNotificationsEnabled() || !canScheduleExactAlarms()) return

        schedules.forEach { schedule ->
            scheduleNextReminder(schedule)
        }
    }

    fun cancelReminderIds(reminderIds: List<String>) {
        cancelReminderPendingIntents(reminderIds, dismissNotifications = true)
    }

    private fun cancelReminderPendingIntents(reminderIds: List<String>, dismissNotifications: Boolean) {
        reminderIds.distinct().forEach { reminderId ->
            buildAlarmPendingIntent(reminderId, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { pendingIntent ->
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            if (dismissNotifications) {
                NotificationManagerCompat.from(context).cancel(reminderId.hashCode())
            }
        }
    }

    suspend fun handleTriggeredReminder(reminderId: String) {
        val schedule = medicationReminderDao.getScheduleByReminderId(reminderId) ?: return
        val now = ZonedDateTime.now(zoneId)
        if (now.toLocalDate().isBefore(schedule.startDate)) return
        if (schedule.endDate != null && now.toLocalDate().isAfter(schedule.endDate)) return

        ensureNotificationChannel()
        if (areNotificationsEnabled() && !needsNotificationPermissionRequest()) {
            NotificationManagerCompat.from(context).notify(
                reminderId.hashCode(),
                NotificationCompat.Builder(context, MEDICATION_REMINDER_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(schedule.medicationName)
                    .setContentText(buildNotificationBody(schedule))
                    .setAutoCancel(true)
                    .setContentIntent(buildOpenMedicationPendingIntent(schedule.medicationId))
                    .build(),
            )
        }

        cancelReminderPendingIntents(listOf(reminderId), dismissNotifications = false)
        if (canScheduleExactAlarms()) {
            scheduleNextReminder(schedule)
        }
    }

    private fun scheduleNextReminder(schedule: MedicationReminderSchedule) {
        val nextTrigger = computeNextReminderTrigger(schedule, ZonedDateTime.now(zoneId), zoneId) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTrigger.toInstant().toEpochMilli(),
            buildAlarmPendingIntent(
                reminderId = schedule.reminderId,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return,
        )
    }

    private fun buildNotificationBody(schedule: MedicationReminderSchedule): String {
        val detail = listOfNotNull(schedule.dose, schedule.frequency)
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        return detail.ifBlank { context.getString(R.string.medication_reminder_notification_body_fallback) }
    }

    private fun buildAlarmPendingIntent(reminderId: String, flags: Int): PendingIntent? {
        val intent = Intent(context, MedicationReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_MEDICATION_REMINDER
            data = Uri.parse("medicalrecord://reminders/$reminderId")
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun buildOpenMedicationPendingIntent(medicationId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("medicalrecord://medications/$medicationId")
            putExtra(EXTRA_OPEN_MEDICATION_ID, medicationId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            medicationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

@AndroidEntryPoint
class MedicationReminderReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: MedicationReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.handleTriggeredReminder(reminderId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

@AndroidEntryPoint
class MedicationReminderRescheduleReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: MedicationReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduler.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
