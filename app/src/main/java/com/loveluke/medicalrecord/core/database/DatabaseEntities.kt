package com.loveluke.medicalrecord.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.AttachmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(
    tableName = "patient_profiles",
    indices = [
        Index(name = "index_patient_profiles_is_default", value = ["is_default"]),
    ],
)
data class PatientProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean,
    @ColumnInfo(name = "is_hidden")
    val isHidden: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

@Entity(
    tableName = "encounters",
    foreignKeys = [
        ForeignKey(
            entity = PatientProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["patient_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "index_encounters_patient_id_id",
            value = ["patient_id", "id"],
            unique = true,
        ),
        Index(
            name = "index_encounters_patient_id_visit_date",
            value = ["patient_id", "visit_date", "visit_time"],
        ),
    ],
)
data class EncounterEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "patient_id")
    val patientId: String,
    @ColumnInfo(name = "visit_date")
    val visitDate: LocalDate,
    @ColumnInfo(name = "visit_time")
    val visitTime: LocalTime?,
    @ColumnInfo(name = "hospital")
    val hospital: String,
    @ColumnInfo(name = "department")
    val department: String?,
    @ColumnInfo(name = "doctor")
    val doctor: String?,
    @ColumnInfo(name = "chief_complaint")
    val chiefComplaint: String?,
    @ColumnInfo(name = "diagnosis")
    val diagnosis: String?,
    @ColumnInfo(name = "disposition")
    val disposition: String?,
    @ColumnInfo(name = "notes")
    val notes: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = EncounterEntity::class,
            parentColumns = ["patient_id", "id"],
            childColumns = ["patient_id", "encounter_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "index_attachments_patient_id_encounter_id",
            value = ["patient_id", "encounter_id"],
        ),
        Index(
            name = "index_attachments_patient_id_created_at",
            value = ["patient_id", "created_at"],
        ),
    ],
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "patient_id")
    val patientId: String,
    @ColumnInfo(name = "encounter_id")
    val encounterId: String,
    @ColumnInfo(name = "kind")
    val kind: AttachmentKind,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "encrypted_relative_path")
    val encryptedRelativePath: String,
    @ColumnInfo(name = "encrypted_thumbnail_relative_path")
    val encryptedThumbnailRelativePath: String?,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "page_count")
    val pageCount: Int?,
    @ColumnInfo(name = "crypto_version")
    val cryptoVersion: Int,
    @ColumnInfo(name = "integrity_state")
    val integrityState: AttachmentIntegrityState,
    @ColumnInfo(name = "quarantined_at")
    val quarantinedAt: Instant?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(sizeBytes >= 0L) { "Attachment size cannot be negative" }
        require(cryptoVersion > 0) { "Attachment crypto version must be positive" }
        require(pageCount == null || pageCount > 0) { "Attachment page count must be positive" }
    }
}

@Entity(
    tableName = "medications",
    foreignKeys = [
        ForeignKey(
            entity = PatientProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["patient_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "index_medications_patient_id_id",
            value = ["patient_id", "id"],
            unique = true,
        ),
        Index(
            name = "index_medications_patient_id_dates",
            value = ["patient_id", "start_date", "end_date"],
        ),
    ],
)
data class MedicationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "patient_id")
    val patientId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "dose")
    val dose: String?,
    @ColumnInfo(name = "frequency")
    val frequency: String?,
    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,
    @ColumnInfo(name = "end_date")
    val endDate: LocalDate?,
    @ColumnInfo(name = "notes")
    val notes: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(endDate == null || !endDate.isBefore(startDate)) {
            "Medication end date precedes its start date"
        }
    }
}

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["patient_id", "id"],
            childColumns = ["patient_id", "medication_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "index_reminders_patient_id_medication_id",
            value = ["patient_id", "medication_id"],
        ),
        Index(
            name = "index_reminders_patient_medication_time",
            value = ["patient_id", "medication_id", "time_minutes"],
            unique = true,
        ),
        Index(
            name = "index_reminders_enabled_time",
            value = ["enabled_by_user", "time_minutes"],
        ),
    ],
)
data class ReminderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "patient_id")
    val patientId: String,
    @ColumnInfo(name = "medication_id")
    val medicationId: String,
    @ColumnInfo(name = "time_minutes")
    val timeMinutesOfDay: Int,
    @ColumnInfo(name = "enabled_by_user")
    val enabledByUser: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    init {
        require(timeMinutesOfDay in 0..1_439) { "Reminder time is outside a day" }
    }
}

/** Singleton persisted mirror of the AlarmManager state. */
@Entity(tableName = "reminder_schedule_state")
data class ReminderScheduleStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "reminder_id")
    val reminderId: String?,
    @ColumnInfo(name = "trigger_at")
    val triggerAt: Instant?,
    @ColumnInfo(name = "precision")
    val precision: String?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

data class HomeCountsRow(
    @ColumnInfo(name = "encounter_count")
    val encounterCount: Long,
    @ColumnInfo(name = "attachment_count")
    val attachmentCount: Long,
    @ColumnInfo(name = "current_medication_count")
    val currentMedicationCount: Long,
    @ColumnInfo(name = "today_reminder_count")
    val todayReminderCount: Long,
)

data class AttachmentStoredPathsRow(
    @ColumnInfo(name = "original_relative_path")
    val originalRelativePath: String,
    @ColumnInfo(name = "thumbnail_relative_path")
    val thumbnailRelativePath: String?,
)

data class ReminderPlanRow(
    @ColumnInfo(name = "reminder_id")
    val reminderId: String,
    @ColumnInfo(name = "patient_id")
    val patientId: String,
    @ColumnInfo(name = "medication_id")
    val medicationId: String,
    @ColumnInfo(name = "medication_name")
    val medicationName: String,
    @ColumnInfo(name = "dose")
    val dose: String?,
    @ColumnInfo(name = "start_date")
    val startDate: LocalDate,
    @ColumnInfo(name = "end_date")
    val endDate: LocalDate?,
    @ColumnInfo(name = "time_minutes")
    val timeMinutesOfDay: Int,
    @ColumnInfo(name = "enabled_by_user")
    val enabledByUser: Boolean,
)
