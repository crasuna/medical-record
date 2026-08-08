package com.loveluke.medicalrecord.core.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Mirrors the database's single next reminder into AlarmManager. */
class SystemReminderScheduler internal constructor(
    private val store: ReminderScheduleStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    private val notificationAccess: ReminderNotificationAccess,
    private val alarmPlatform: ReminderAlarmPlatform,
) {
    constructor(
        context: Context,
        store: ReminderScheduleStore,
        clock: Clock = Clock.systemUTC(),
        zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    ) : this(
        store = store,
        clock = clock,
        zoneIdProvider = zoneIdProvider,
        notificationAccess = AndroidReminderNotificationAccess(context),
        alarmPlatform = AndroidReminderAlarmPlatform(context),
    )

    suspend fun reconcile(): ReminderSchedulingState = reconcileAfter(clock.instant())

    suspend fun reconcileAfter(after: Instant): ReminderSchedulingState {
        if (!notificationAccess.canPostNotifications()) {
            cancelPlatformAlarm()
            return ReminderSchedulingState.NotificationsUnavailable.also {
                store.recordSchedulingState(it)
            }
        }

        val occurrence = store.findNextOccurrence(after, zoneIdProvider())
        if (occurrence == null) {
            cancelPlatformAlarm()
            return ReminderSchedulingState.NoFutureReminder.also {
                store.recordSchedulingState(it)
            }
        }

        val precision = schedulePlatformAlarm(occurrence)
        return ReminderSchedulingState.Scheduled(
            reminderId = occurrence.reminderId,
            triggerAt = occurrence.triggerAt,
            precision = precision,
        ).also { store.recordSchedulingState(it) }
    }

    fun cancelPlatformAlarm() {
        alarmPlatform.cancel()
    }

    private fun schedulePlatformAlarm(occurrence: ReminderOccurrence): AlarmPrecision {
        return alarmPlatform.schedule(occurrence)
    }

    internal companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at_epoch_millis"
    }
}

internal fun interface ReminderNotificationAccess {
    fun canPostNotifications(): Boolean
}

internal interface ReminderAlarmPlatform {
    fun schedule(occurrence: ReminderOccurrence): AlarmPrecision

    fun cancel()
}

private class AndroidReminderNotificationAccess(
    context: Context,
) : ReminderNotificationAccess {
    private val availability = AndroidReminderNotificationAvailability(context)

    override fun canPostNotifications(): Boolean = availability.current().isAvailable
}

private class AndroidReminderAlarmPlatform(
    context: Context,
) : ReminderAlarmPlatform {
    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    override fun schedule(occurrence: ReminderOccurrence): AlarmPrecision {
        val triggerAtMillis = occurrence.triggerAt.toEpochMilli()
        val pendingIntent = alarmPendingIntent(
            reminderId = occurrence.reminderId,
            scheduledAtEpochMillis = triggerAtMillis,
        )
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (exactAllowed) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
                return AlarmPrecision.EXACT
            } catch (_: SecurityException) {
                // Permission can change between canScheduleExactAlarms() and this call. Preserve
                // the reminder intention and degrade to an inexact alarm instead of dropping it.
            }
        }

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
        return AlarmPrecision.INEXACT
    }

    override fun cancel() {
        alarmManager.cancel(alarmPendingIntent())
    }

    private fun alarmPendingIntent(
        reminderId: String? = null,
        scheduledAtEpochMillis: Long? = null,
    ): PendingIntent = checkNotNull(
        medicationReminderPendingIntent(
            context = applicationContext,
            reminderId = reminderId,
            scheduledAtEpochMillis = scheduledAtEpochMillis,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    )
}

/** Clears alarm/notification artifacts without opening Room or resolving encryption material. */
@Singleton
class ReminderPlatformArtifactClearer @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val applicationContext = context.applicationContext

    fun clear(): Boolean = try {
        medicationReminderPendingIntent(
            context = applicationContext,
            reminderId = null,
            scheduledAtEpochMillis = null,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { pendingIntent ->
            applicationContext.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
            pendingIntent.cancel()
        }
        NotificationManagerCompat.from(applicationContext).cancelAll()
        true
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}

private fun medicationReminderPendingIntent(
    context: Context,
    reminderId: String?,
    scheduledAtEpochMillis: Long?,
    flags: Int,
): PendingIntent? {
    val action = "${context.packageName}.action.DELIVER_MEDICATION_REMINDER"
    val intent = Intent(context, ReminderAlarmReceiver::class.java)
        .setAction(action)
        .setPackage(context.packageName)
        .setData("medical-record://${context.packageName}/next-reminder".toUri())
    if (reminderId != null && scheduledAtEpochMillis != null) {
        intent.putExtra(SystemReminderScheduler.EXTRA_REMINDER_ID, reminderId)
        intent.putExtra(SystemReminderScheduler.EXTRA_SCHEDULED_AT, scheduledAtEpochMillis)
    }
    return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
}

private const val ALARM_REQUEST_CODE = 0x4D52
