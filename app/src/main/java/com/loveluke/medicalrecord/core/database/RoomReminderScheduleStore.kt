package com.loveluke.medicalrecord.core.database

import androidx.room.withTransaction
import com.loveluke.medicalrecord.core.reminder.AlarmPrecision
import com.loveluke.medicalrecord.core.reminder.ReminderDeliveryBatch
import com.loveluke.medicalrecord.core.reminder.ReminderNotificationContent
import com.loveluke.medicalrecord.core.reminder.ReminderOccurrence
import com.loveluke.medicalrecord.core.reminder.ReminderPlan
import com.loveluke.medicalrecord.core.reminder.ReminderScheduleStore
import com.loveluke.medicalrecord.core.reminder.ReminderSchedulingState
import com.loveluke.medicalrecord.core.reminder.nextOccurrence
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed source of truth used by the AlarmManager adapter. */
@Singleton
class RoomReminderScheduleStore internal constructor(
    private val database: AppDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) : ReminderScheduleStore {
    @Inject
    constructor(database: AppDatabase) : this(
        database = database,
        clock = Clock.systemUTC(),
        zoneIdProvider = ZoneId::systemDefault,
    )

    override suspend fun findNextOccurrence(
        after: Instant,
        zoneId: ZoneId,
    ): ReminderOccurrence? = database.medicationDao()
        .getEnabledReminderPlans()
        .asSequence()
        .map(ReminderPlanRow::toPlan)
        .mapNotNull { it.nextOccurrence(after, zoneId) }
        .minWithOrNull(
            compareBy<ReminderOccurrence>(ReminderOccurrence::triggerAt)
                .thenBy(ReminderOccurrence::patientId)
                .thenBy(ReminderOccurrence::medicationId)
                .thenBy(ReminderOccurrence::reminderId),
        )

    override suspend fun findDueDelivery(
        anchorReminderId: String,
        scheduledAt: Instant,
        deliveredAt: Instant,
    ): ReminderDeliveryBatch? {
        if (deliveredAt.isBefore(scheduledAt)) return null
        return database.withTransaction {
            val persistedState = database.reminderScheduleDao().getState()
            if (!persistedState.matchesScheduledAlarm(anchorReminderId, scheduledAt)) {
                return@withTransaction null
            }
            buildDeliveryBatch(anchorReminderId, scheduledAt, deliveredAt)
        }
    }

    override suspend fun findPendingDueDelivery(
        atOrBefore: Instant,
    ): ReminderDeliveryBatch? = database.withTransaction {
        val persistedState = database.reminderScheduleDao().getState()
            ?.takeIf { state ->
                state.state == STATE_SCHEDULED &&
                    state.reminderId != null &&
                    state.triggerAt?.let { !it.isAfter(atOrBefore) } == true
            }
            ?: return@withTransaction null
        buildDeliveryBatch(
            anchorReminderId = requireNotNull(persistedState.reminderId),
            scheduledAt = requireNotNull(persistedState.triggerAt),
            deliveredAt = atOrBefore,
        )
    }

    override suspend fun recordSchedulingState(state: ReminderSchedulingState) {
        val entity = when (state) {
            ReminderSchedulingState.NoFutureReminder -> ReminderScheduleStateEntity(
                state = STATE_NO_FUTURE_REMINDER,
                reminderId = null,
                triggerAt = null,
                precision = null,
                updatedAt = clock.instant(),
            )

            ReminderSchedulingState.NotificationsUnavailable -> ReminderScheduleStateEntity(
                state = STATE_NOTIFICATIONS_UNAVAILABLE,
                reminderId = null,
                triggerAt = null,
                precision = null,
                updatedAt = clock.instant(),
            )

            is ReminderSchedulingState.Scheduled -> ReminderScheduleStateEntity(
                state = STATE_SCHEDULED,
                reminderId = state.reminderId,
                triggerAt = state.triggerAt,
                precision = state.precision.name,
                updatedAt = clock.instant(),
            )
        }
        database.reminderScheduleDao().upsertState(entity)
    }

    suspend fun readPersistedState(): ReminderSchedulingState? =
        database.reminderScheduleDao().getState()?.toSchedulingState()

    private suspend fun buildDeliveryBatch(
        anchorReminderId: String,
        scheduledAt: Instant,
        deliveredAt: Instant,
    ): ReminderDeliveryBatch {
        val zoneId = zoneIdProvider()
        val searchFloor = runCatching { scheduledAt.minusNanos(1) }
            .getOrElse { scheduledAt }
        val notifications = database.medicationDao()
            .getEnabledReminderPlans()
            .asSequence()
            .mapNotNull { row ->
                row.toPlan()
                    .nextOccurrence(searchFloor, zoneId)
                    ?.takeUnless { occurrence -> occurrence.triggerAt.isAfter(deliveredAt) }
                    ?.let { occurrence -> row to occurrence }
            }
            .distinctBy { (_, occurrence) -> occurrence.reminderId to occurrence.triggerAt }
            .sortedWith(
                compareBy<Pair<ReminderPlanRow, ReminderOccurrence>> { (_, occurrence) ->
                    occurrence.triggerAt
                }.thenBy { (_, occurrence) -> occurrence.patientId }
                    .thenBy { (_, occurrence) -> occurrence.medicationId }
                    .thenBy { (_, occurrence) -> occurrence.reminderId },
            )
            .map { (row, occurrence) ->
                ReminderNotificationContent(
                    reminderId = row.reminderId,
                    medicationId = row.medicationId,
                    medicationName = row.medicationName,
                    dose = row.dose,
                    scheduledAt = occurrence.triggerAt,
                )
            }
            .toList()
        return ReminderDeliveryBatch(
            anchorReminderId = anchorReminderId,
            scheduledAt = scheduledAt,
            notifications = notifications,
        )
    }

    private fun ReminderScheduleStateEntity?.matchesScheduledAlarm(
        anchorReminderId: String,
        scheduledAt: Instant,
    ): Boolean = this != null &&
        state == STATE_SCHEDULED &&
        reminderId == anchorReminderId &&
        triggerAt == scheduledAt

    private fun ReminderScheduleStateEntity.toSchedulingState(): ReminderSchedulingState? {
        return when (state) {
            STATE_NO_FUTURE_REMINDER -> ReminderSchedulingState.NoFutureReminder
            STATE_NOTIFICATIONS_UNAVAILABLE -> ReminderSchedulingState.NotificationsUnavailable
            STATE_SCHEDULED -> {
                val persistedReminderId = reminderId ?: return null
                val persistedTriggerAt = triggerAt ?: return null
                val persistedPrecision = precision?.let(AlarmPrecision::valueOf) ?: return null
                ReminderSchedulingState.Scheduled(
                    reminderId = persistedReminderId,
                    triggerAt = persistedTriggerAt,
                    precision = persistedPrecision,
                )
            }

            else -> null
        }
    }

    private companion object {
        const val STATE_NO_FUTURE_REMINDER = "NO_FUTURE_REMINDER"
        const val STATE_NOTIFICATIONS_UNAVAILABLE = "NOTIFICATIONS_UNAVAILABLE"
        const val STATE_SCHEDULED = "SCHEDULED"
    }
}

private fun ReminderPlanRow.toPlan() = ReminderPlan(
    reminderId = reminderId,
    patientId = patientId,
    medicationId = medicationId,
    medicationName = medicationName,
    dose = dose,
    startDate = startDate,
    endDate = endDate,
    timeMinutesOfDay = timeMinutesOfDay,
    enabledByUser = enabledByUser,
)
