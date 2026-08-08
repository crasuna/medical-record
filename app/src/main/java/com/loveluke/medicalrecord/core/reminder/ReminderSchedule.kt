package com.loveluke.medicalrecord.core.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A persisted user reminder intention, independent of current Android permissions. */
data class ReminderPlan(
    val reminderId: String,
    val patientId: String,
    val medicationId: String,
    val medicationName: String,
    val dose: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val timeMinutesOfDay: Int,
    val enabledByUser: Boolean,
) {
    init {
        require(timeMinutesOfDay in 0..1_439) { "Reminder time is outside a day" }
        require(endDate == null || !endDate.isBefore(startDate)) {
            "Reminder end date precedes its start date"
        }
    }
}

data class ReminderOccurrence(
    val reminderId: String,
    val patientId: String,
    val medicationId: String,
    val triggerAt: Instant,
)

data class ReminderNotificationContent(
    val reminderId: String,
    val medicationId: String,
    val medicationName: String,
    val dose: String?,
    val scheduledAt: Instant,
)

/**
 * The persisted single AlarmManager occurrence plus every still-valid reminder occurrence that
 * became due before that alarm was actually delivered.
 */
data class ReminderDeliveryBatch(
    val anchorReminderId: String,
    val scheduledAt: Instant,
    val notifications: List<ReminderNotificationContent>,
)

enum class AlarmPrecision {
    EXACT,
    INEXACT,
}

sealed interface ReminderSchedulingState {
    data object NoFutureReminder : ReminderSchedulingState
    data object NotificationsUnavailable : ReminderSchedulingState
    data class Scheduled(
        val reminderId: String,
        val triggerAt: Instant,
        val precision: AlarmPrecision,
    ) : ReminderSchedulingState
}

/**
 * Persistence boundary used by the platform scheduler. The database implementation remains the
 * source of truth; AlarmManager only mirrors the single next occurrence.
 */
interface ReminderScheduleStore {
    suspend fun findNextOccurrence(after: Instant, zoneId: ZoneId): ReminderOccurrence?

    suspend fun findDueDelivery(
        anchorReminderId: String,
        scheduledAt: Instant,
        deliveredAt: Instant,
    ): ReminderDeliveryBatch?

    suspend fun findPendingDueDelivery(atOrBefore: Instant): ReminderDeliveryBatch?

    suspend fun recordSchedulingState(state: ReminderSchedulingState)
}

fun ReminderPlan.nextOccurrence(
    after: Instant,
    zoneId: ZoneId,
): ReminderOccurrence? {
    if (!enabledByUser) return null

    val afterLocal = after.atZone(zoneId)
    var candidateDate = maxOf(startDate, afterLocal.toLocalDate())
    val reminderTime = LocalTime.of(timeMinutesOfDay / 60, timeMinutesOfDay % 60)
    var candidate = candidateDate.atTime(reminderTime).atZone(zoneId).toInstant()

    if (!candidate.isAfter(after)) {
        candidateDate = candidateDate.plusDays(1)
        candidate = candidateDate.atTime(reminderTime).atZone(zoneId).toInstant()
    }

    if (endDate != null && candidateDate.isAfter(endDate)) return null

    return ReminderOccurrence(
        reminderId = reminderId,
        patientId = patientId,
        medicationId = medicationId,
        triggerAt = candidate,
    )
}
