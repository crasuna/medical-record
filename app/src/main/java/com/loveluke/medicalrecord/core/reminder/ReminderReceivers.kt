package com.loveluke.medicalrecord.core.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface ReminderRuntimeHandler {
    suspend fun onAlarm(reminderId: String, scheduledAt: Instant)

    suspend fun reconcile()
}

/**
 * Process-local bridge installed by [com.loveluke.medicalrecord.app.MedicalRecordApplication]
 * before any manifest receiver runs. It contains no medical data and persists no state.
 */
object ReminderRuntime {
    private val handler = AtomicReference<ReminderRuntimeHandler?>()

    fun install(runtimeHandler: ReminderRuntimeHandler) {
        handler.set(runtimeHandler)
    }

    internal fun currentHandler(): ReminderRuntimeHandler? = handler.get()
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(SystemReminderScheduler.EXTRA_REMINDER_ID)
            ?.takeIf(String::isNotBlank) ?: return
        val scheduledAtMillis = intent.getLongExtra(
            SystemReminderScheduler.EXTRA_SCHEDULED_AT,
            INVALID_EPOCH_MILLIS,
        )
        if (scheduledAtMillis == INVALID_EPOCH_MILLIS) return

        val runtimeHandler = ReminderRuntime.currentHandler() ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runtimeHandler.onAlarm(reminderId, Instant.ofEpochMilli(scheduledAtMillis))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val INVALID_EPOCH_MILLIS = Long.MIN_VALUE
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val runtimeHandler = ReminderRuntime.currentHandler() ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runtimeHandler.reconcile()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // A literal avoids referencing an API 31 field while this receiver also runs on API 26–30.
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
        )

        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
