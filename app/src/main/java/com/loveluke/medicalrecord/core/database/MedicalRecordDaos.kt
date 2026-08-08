package com.loveluke.medicalrecord.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientProfileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(profile: PatientProfileEntity): Long

    @Upsert
    suspend fun upsert(profile: PatientProfileEntity)

    @Update
    suspend fun update(profile: PatientProfileEntity): Int

    @Query("SELECT * FROM patient_profiles WHERE id = :patientId LIMIT 1")
    suspend fun getById(patientId: String): PatientProfileEntity?

    @Query(
        """
        SELECT * FROM patient_profiles
        WHERE is_default = 1
        ORDER BY created_at ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun getDefault(): PatientProfileEntity?

    @Query("SELECT * FROM patient_profiles WHERE id = :patientId LIMIT 1")
    fun observeById(patientId: String): Flow<PatientProfileEntity?>

    @Query(
        """
        SELECT * FROM patient_profiles
        WHERE is_default = 1
        ORDER BY created_at ASC, id ASC
        LIMIT 1
        """,
    )
    fun observeDefault(): Flow<PatientProfileEntity?>

    @Query("SELECT COUNT(*) FROM patient_profiles")
    suspend fun count(): Int

    @Delete
    suspend fun delete(profile: PatientProfileEntity)
}

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(encounter: EncounterEntity)

    @Upsert
    suspend fun upsert(encounter: EncounterEntity)

    @Update
    suspend fun update(encounter: EncounterEntity): Int

    @Query("SELECT * FROM encounters WHERE patient_id = :patientId AND id = :encounterId LIMIT 1")
    suspend fun get(patientId: String, encounterId: String): EncounterEntity?

    @Query(
        """
        SELECT * FROM encounters
        WHERE patient_id = :patientId
        ORDER BY visit_date DESC, visit_time DESC, created_at DESC, id DESC
        """,
    )
    fun observeAll(patientId: String): Flow<List<EncounterEntity>>

    @Query("SELECT * FROM encounters WHERE patient_id = :patientId AND id = :encounterId LIMIT 1")
    fun observe(
        patientId: String,
        encounterId: String,
    ): Flow<EncounterEntity?>

    @Query("DELETE FROM encounters WHERE patient_id = :patientId AND id = :encounterId")
    suspend fun delete(patientId: String, encounterId: String): Int

    @Query(
        """
        SELECT * FROM encounters
        WHERE patient_id = :patientId
        ORDER BY visit_date DESC, visit_time DESC, created_at DESC, id DESC
        LIMIT 3
        """,
    )
    fun observeRecentThree(patientId: String): Flow<List<EncounterEntity>>

    @Query(
        """
        SELECT DISTINCT e.*
        FROM encounters AS e
        WHERE e.patient_id = :patientId
          AND (
            e.hospital LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR e.department LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR e.doctor LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR e.chief_complaint LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR e.diagnosis LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR e.disposition LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR e.notes LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR EXISTS (
                SELECT 1
                FROM attachments AS a
                WHERE a.patient_id = e.patient_id
                  AND a.encounter_id = e.id
                  AND a.display_name LIKE :pattern ESCAPE '\' COLLATE NOCASE
            )
          )
        ORDER BY e.visit_date DESC, e.visit_time DESC, e.created_at DESC, e.id DESC
        """,
    )
    fun search(patientId: String, pattern: String): Flow<List<EncounterEntity>>

    @Insert
    suspend fun insertAttachment(attachment: AttachmentEntity)

    @Upsert
    suspend fun upsertAttachment(attachment: AttachmentEntity)

    @Update
    suspend fun updateAttachment(attachment: AttachmentEntity): Int

    @Query(
        """
        SELECT * FROM attachments
        WHERE patient_id = :patientId AND encounter_id = :encounterId
        ORDER BY created_at ASC, id ASC
        """,
    )
    suspend fun getAttachments(patientId: String, encounterId: String): List<AttachmentEntity>

    @Query(
        """
        SELECT * FROM attachments
        WHERE patient_id = :patientId AND encounter_id = :encounterId
        ORDER BY created_at ASC, id ASC
        """,
    )
    fun observeAttachments(
        patientId: String,
        encounterId: String,
    ): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachments WHERE patient_id = :patientId AND id = :attachmentId")
    suspend fun deleteAttachment(patientId: String, attachmentId: String): Int

    /** Includes quarantined attachments because their encrypted payloads remain diagnostic data. */
    @Query(
        """
        SELECT
            encrypted_relative_path AS original_relative_path,
            encrypted_thumbnail_relative_path AS thumbnail_relative_path
        FROM attachments
        """,
    )
    suspend fun getAllStoredAttachmentPaths(): List<AttachmentStoredPathsRow>
}

@Dao
interface MedicationDao {
    @Insert
    suspend fun insert(medication: MedicationEntity)

    @Upsert
    suspend fun upsert(medication: MedicationEntity)

    @Update
    suspend fun update(medication: MedicationEntity): Int

    @Query("SELECT * FROM medications WHERE patient_id = :patientId AND id = :medicationId LIMIT 1")
    suspend fun get(patientId: String, medicationId: String): MedicationEntity?

    @Query(
        """
        SELECT * FROM medications
        WHERE patient_id = :patientId
        ORDER BY start_date DESC, created_at DESC, id DESC
        """,
    )
    fun observeAll(patientId: String): Flow<List<MedicationEntity>>

    @Query(
        """
        SELECT * FROM medications
        WHERE patient_id = :patientId
          AND start_date <= :today
          AND (end_date IS NULL OR end_date >= :today)
        ORDER BY start_date DESC, created_at DESC, id DESC
        """,
    )
    fun observeCurrent(patientId: String, today: LocalDate): Flow<List<MedicationEntity>>

    @Query(
        """
        SELECT * FROM medications
        WHERE patient_id = :patientId AND start_date > :today
        ORDER BY start_date ASC, created_at DESC, id DESC
        """,
    )
    fun observeUpcoming(patientId: String, today: LocalDate): Flow<List<MedicationEntity>>

    @Query(
        """
        SELECT * FROM medications
        WHERE patient_id = :patientId AND end_date IS NOT NULL AND end_date < :today
        ORDER BY end_date DESC, start_date DESC, id DESC
        """,
    )
    fun observeEnded(patientId: String, today: LocalDate): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE patient_id = :patientId AND id = :medicationId LIMIT 1")
    fun observe(
        patientId: String,
        medicationId: String,
    ): Flow<MedicationEntity?>

    @Query("DELETE FROM medications WHERE patient_id = :patientId AND id = :medicationId")
    suspend fun delete(patientId: String, medicationId: String): Int

    @Query(
        """
        SELECT * FROM medications
        WHERE patient_id = :patientId
          AND start_date <= :today
          AND (end_date IS NULL OR end_date >= :today)
        ORDER BY start_date DESC, created_at DESC, id DESC
        LIMIT 3
        """,
    )
    fun observeRecentThreeCurrent(
        patientId: String,
        today: LocalDate,
    ): Flow<List<MedicationEntity>>

    @Query(
        """
        SELECT * FROM medications
        WHERE patient_id = :patientId
          AND (
            name LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR dose LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR frequency LIKE :pattern ESCAPE '\' COLLATE NOCASE
            OR notes LIKE :pattern ESCAPE '\' COLLATE NOCASE
          )
        ORDER BY start_date DESC, created_at DESC, id DESC
        """,
    )
    fun search(patientId: String, pattern: String): Flow<List<MedicationEntity>>

    @Insert
    suspend fun insertReminder(reminder: ReminderEntity)

    @Insert
    suspend fun insertReminders(reminders: List<ReminderEntity>)

    @Upsert
    suspend fun upsertReminder(reminder: ReminderEntity)

    @Query(
        """
        SELECT * FROM reminders
        WHERE patient_id = :patientId AND medication_id = :medicationId
        ORDER BY time_minutes ASC, id ASC
        """,
    )
    suspend fun getReminders(patientId: String, medicationId: String): List<ReminderEntity>

    @Query(
        """
        SELECT * FROM reminders
        WHERE patient_id = :patientId AND medication_id = :medicationId
        ORDER BY time_minutes ASC, id ASC
        """,
    )
    fun observeReminders(
        patientId: String,
        medicationId: String,
    ): Flow<List<ReminderEntity>>

    @Query("DELETE FROM reminders WHERE patient_id = :patientId AND medication_id = :medicationId")
    suspend fun deleteReminders(patientId: String, medicationId: String): Int

    @Query(
        """
        SELECT
            r.id AS reminder_id,
            r.patient_id AS patient_id,
            r.medication_id AS medication_id,
            m.name AS medication_name,
            m.dose AS dose,
            m.start_date AS start_date,
            m.end_date AS end_date,
            r.time_minutes AS time_minutes,
            r.enabled_by_user AS enabled_by_user
        FROM reminders AS r
        INNER JOIN medications AS m
            ON m.patient_id = r.patient_id AND m.id = r.medication_id
        WHERE r.enabled_by_user = 1
        """,
    )
    suspend fun getEnabledReminderPlans(): List<ReminderPlanRow>

    @Query(
        """
        SELECT
            r.id AS reminder_id,
            r.patient_id AS patient_id,
            r.medication_id AS medication_id,
            m.name AS medication_name,
            m.dose AS dose,
            m.start_date AS start_date,
            m.end_date AS end_date,
            r.time_minutes AS time_minutes,
            r.enabled_by_user AS enabled_by_user
        FROM reminders AS r
        INNER JOIN medications AS m
            ON m.patient_id = r.patient_id AND m.id = r.medication_id
        WHERE r.id = :reminderId
        LIMIT 1
        """,
    )
    suspend fun getReminderPlan(reminderId: String): ReminderPlanRow?

}

@Dao
interface HomeDao {
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM encounters WHERE patient_id = :patientId) AS encounter_count,
            (SELECT COUNT(*) FROM attachments WHERE patient_id = :patientId) AS attachment_count,
            (
                SELECT COUNT(*)
                FROM medications
                WHERE patient_id = :patientId
                  AND start_date <= :today
                  AND (end_date IS NULL OR end_date >= :today)
            ) AS current_medication_count,
            (
                SELECT COUNT(*)
                FROM reminders AS r
                INNER JOIN medications AS m
                    ON m.patient_id = r.patient_id AND m.id = r.medication_id
                WHERE r.patient_id = :patientId
                  AND r.enabled_by_user = 1
                  AND m.start_date <= :today
                  AND (m.end_date IS NULL OR m.end_date >= :today)
            ) AS today_reminder_count
        """,
    )
    fun observeCounts(patientId: String, today: LocalDate): Flow<HomeCountsRow>
}

@Dao
interface ReminderScheduleDao {
    @Upsert
    suspend fun upsertState(state: ReminderScheduleStateEntity)

    @Query("SELECT * FROM reminder_schedule_state WHERE singleton_id = 1 LIMIT 1")
    suspend fun getState(): ReminderScheduleStateEntity?
}
