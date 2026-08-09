package com.loveluke.medicalrecord.app.reminder

import android.content.Context
import com.loveluke.medicalrecord.app.access.MedicalRecordAccessController
import com.loveluke.medicalrecord.app.access.ReminderAccessGate
import com.loveluke.medicalrecord.app.access.ReminderAccessResult
import com.loveluke.medicalrecord.app.di.ApplicationCoroutineScope
import com.loveluke.medicalrecord.core.reminder.DefaultReminderRuntimeHandler
import com.loveluke.medicalrecord.core.reminder.ReminderNotificationPublisher
import com.loveluke.medicalrecord.core.reminder.ReminderRuntime
import com.loveluke.medicalrecord.core.reminder.ReminderRuntimeHandler
import com.loveluke.medicalrecord.core.reminder.ReminderScheduleStore
import com.loveluke.medicalrecord.core.reminder.SystemReminderScheduler
import com.loveluke.medicalrecord.core.time.MedicalRecordTimeSource
import com.loveluke.medicalrecord.core.time.asClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ReminderRuntimeCoordinator internal constructor(
    private val handlerFactory: () -> ReminderRuntimeHandler,
    private val retryScope: CoroutineScope? = null,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val maxRetryAttempts: Int = DEFAULT_MAX_RETRY_ATTEMPTS,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        storeProvider: Provider<ReminderScheduleStore>,
        @ApplicationCoroutineScope retryScope: CoroutineScope,
        timeSource: MedicalRecordTimeSource,
    ) : this(
        handlerFactory = {
            val store = storeProvider.get()
            val scheduler = SystemReminderScheduler(
                context = context,
                store = store,
                clock = timeSource.asClock(),
                zoneIdProvider = timeSource::zoneId,
            )
            val publisher = ReminderNotificationPublisher(context).also {
                it.createChannel()
            }
            DefaultReminderRuntimeHandler(
                store = store,
                scheduler = scheduler,
                notificationPublisher = publisher,
                clock = timeSource.asClock(),
            )
        },
        retryScope = retryScope,
    )

    private val startMutex = Mutex()
    private val retryMutex = Mutex()
    @Volatile
    private var handler: ReminderRuntimeHandler? = null
    @Volatile
    private var lastOperationFailed: Boolean = false
    private var pendingAlarmRetry: PendingAlarmRetry? = null
    private var retryJob: Job? = null

    /**
     * Installs a synchronous process bridge without opening storage on the Application main
     * thread. Each receiver later enters only the controller's short reminder database gate.
     */
    fun installColdStartBridge(accessController: MedicalRecordAccessController) {
        installColdStartBridge(
            ReminderAccessGate { block ->
                accessController.runWithReminderAccess(block)
            },
        )
    }

    internal fun installColdStartBridge(accessGate: ReminderAccessGate) {
        ReminderRuntime.install(
            object : ReminderRuntimeHandler {
                override suspend fun onAlarm(reminderId: String, scheduledAt: Instant) {
                    runColdStartAlarm(accessGate, reminderId, scheduledAt)
                }

                override suspend fun reconcile() {
                    runColdStartOperation(accessGate, ReminderRuntimeHandler::reconcile)
                }
            },
        )
    }

    suspend fun startAndReconcile() {
        activeHandlerOrNull()?.reconcile()
    }

    suspend fun deliver(reminderId: String, scheduledAt: Instant) {
        activeHandlerOrNull()?.onAlarm(reminderId, scheduledAt)
    }

    fun hasRuntimeFailure(): Boolean = lastOperationFailed

    suspend fun reconcileIfStarted() {
        handler?.reconcile()
    }

    private suspend fun runColdStartOperation(
        accessGate: ReminderAccessGate,
        operation: suspend (ReminderRuntimeHandler) -> Unit,
    ): ReminderAccessResult {
        val result = try {
            accessGate.run {
                activeHandlerOrNull()?.let { active -> operation(active) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            ReminderAccessResult.Unavailable
        }
        if (result is ReminderAccessResult.Unavailable) {
            lastOperationFailed = true
        }
        if (result is ReminderAccessResult.TimedOut) {
            lastOperationFailed = true
        }
        return result
    }

    private suspend fun runColdStartAlarm(
        accessGate: ReminderAccessGate,
        reminderId: String,
        scheduledAt: Instant,
    ) {
        val result = runColdStartOperation(accessGate) { active ->
            active.onAlarm(reminderId, scheduledAt)
        }
        if (result is ReminderAccessResult.TimedOut) {
            retainForBoundedRetry(
                accessGate = accessGate,
                pending = PendingAlarmRetry(reminderId, scheduledAt),
            )
        }
    }

    private suspend fun retainForBoundedRetry(
        accessGate: ReminderAccessGate,
        pending: PendingAlarmRetry,
    ) {
        val scope = retryScope ?: return
        retryMutex.withLock {
            pendingAlarmRetry = pending
            if (retryJob?.isActive != true) {
                retryJob = scope.launch {
                    runBoundedAlarmRetry(accessGate)
                }
            }
        }
    }

    private suspend fun runBoundedAlarmRetry(accessGate: ReminderAccessGate) {
        try {
            repeat(maxRetryAttempts) {
                delay(retryDelayMillis)
                val pending = retryMutex.withLock {
                    pendingAlarmRetry
                } ?: return
                when (
                    runColdStartOperation(accessGate) { active ->
                        active.onAlarm(pending.reminderId, pending.scheduledAt)
                    }
                ) {
                    ReminderAccessResult.Completed -> {
                        retryMutex.withLock {
                            if (pendingAlarmRetry == pending) pendingAlarmRetry = null
                        }
                        return
                    }

                    ReminderAccessResult.TimedOut -> Unit
                    ReminderAccessResult.Unavailable -> return
                }
            }
        } finally {
            retryMutex.withLock { retryJob = null }
        }
    }

    private suspend fun activeHandlerOrNull(): ReminderRuntimeHandler? = try {
        startMutex.withLock {
            handler ?: handlerFactory().guardFailures().also { created ->
                handler = created
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: RuntimeException) {
        lastOperationFailed = true
        null
    }

    private fun ReminderRuntimeHandler.guardFailures(): ReminderRuntimeHandler =
        object : ReminderRuntimeHandler {
            override suspend fun onAlarm(reminderId: String, scheduledAt: Instant) {
                try {
                    this@guardFailures.onAlarm(reminderId, scheduledAt)
                    lastOperationFailed = false
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    lastOperationFailed = true
                }
            }

            override suspend fun reconcile() {
                try {
                    this@guardFailures.reconcile()
                    lastOperationFailed = false
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    lastOperationFailed = true
                }
            }
        }

    private data class PendingAlarmRetry(
        val reminderId: String,
        val scheduledAt: Instant,
    )

    private companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 250L
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 2
    }
}
