package com.loveluke.medicalrecord.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** The hidden patient used until the product exposes patient management. */
data class PatientProfile(
    val id: String,
    val isDefault: Boolean,
    val isHidden: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Encounter(
    val id: String,
    val patientId: String,
    val visitDate: LocalDate,
    val visitTime: LocalTime?,
    val hospital: String,
    val department: String?,
    val doctor: String?,
    val chiefComplaint: String?,
    val diagnosis: String?,
    val disposition: String?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class AttachmentKind {
    IMAGE,
    PDF,
}

enum class AttachmentIntegrityState {
    AVAILABLE,
    QUARANTINED,
}

/** Metadata only. Attachment bytes remain encrypted in app-private storage. */
data class Attachment(
    val id: String,
    val patientId: String,
    val encounterId: String,
    val kind: AttachmentKind,
    val displayName: String,
    val mimeType: String,
    val encryptedRelativePath: String,
    val encryptedThumbnailRelativePath: String?,
    val sizeBytes: Long,
    val pageCount: Int?,
    val cryptoVersion: Int,
    val integrityState: AttachmentIntegrityState,
    val quarantinedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class EncounterDetails(
    val encounter: Encounter,
    val attachments: List<Attachment>,
)

data class Medication(
    val id: String,
    val patientId: String,
    val name: String,
    val dose: String?,
    val frequency: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Reminder(
    val id: String,
    val patientId: String,
    val medicationId: String,
    val timeMinutesOfDay: Int,
    val enabledByUser: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ReminderDraft(
    val timeMinutesOfDay: Int,
    val enabledByUser: Boolean = true,
)

data class MedicationWithReminders(
    val medication: Medication,
    val reminders: List<Reminder>,
)

enum class MedicationCourseStatus {
    CURRENT,
    UPCOMING,
    ENDED,
}

enum class MedicationFilter {
    CURRENT,
    UPCOMING,
    ENDED,
    ALL,
}

fun Medication.courseStatus(today: LocalDate): MedicationCourseStatus = when {
    startDate.isAfter(today) -> MedicationCourseStatus.UPCOMING
    endDate != null && endDate.isBefore(today) -> MedicationCourseStatus.ENDED
    else -> MedicationCourseStatus.CURRENT
}

data class HomeCounts(
    val encounterCount: Long,
    val attachmentCount: Long,
    val currentMedicationCount: Long,
    val todayReminderCount: Long,
)

data class HomeOverview(
    val counts: HomeCounts,
    val recentEncounters: List<Encounter>,
    val recentCurrentMedications: List<Medication>,
)

data class GlobalSearchResults(
    val query: String,
    val encounters: List<Encounter>,
    val medications: List<Medication>,
)
