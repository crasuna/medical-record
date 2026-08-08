package com.loveluke.medicalrecord.core.database

import com.loveluke.medicalrecord.core.model.Attachment
import com.loveluke.medicalrecord.core.model.Encounter
import com.loveluke.medicalrecord.core.model.EncounterDetails
import com.loveluke.medicalrecord.core.model.GlobalSearchResults
import com.loveluke.medicalrecord.core.model.HomeOverview
import com.loveluke.medicalrecord.core.model.Medication
import com.loveluke.medicalrecord.core.model.MedicationFilter
import com.loveluke.medicalrecord.core.model.MedicationWithReminders
import com.loveluke.medicalrecord.core.model.PatientProfile
import com.loveluke.medicalrecord.core.model.ReminderDraft
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface PatientRepository {
    suspend fun ensureDefaultPatient(): PatientProfile

    fun observeDefaultPatient(): Flow<PatientProfile?>

    fun observePatient(patientId: String): Flow<PatientProfile?>
}

interface EncounterRepository {
    fun observeEncounters(patientId: String): Flow<List<Encounter>>

    fun observeEncounter(patientId: String, encounterId: String): Flow<EncounterDetails?>

    suspend fun saveEncounter(encounter: Encounter)

    suspend fun deleteEncounter(patientId: String, encounterId: String): Boolean

    suspend fun saveAttachment(attachment: Attachment)

    suspend fun deleteAttachment(patientId: String, attachmentId: String): Boolean
}

interface MedicationRepository {
    fun observeMedications(
        patientId: String,
        filter: MedicationFilter,
        today: LocalDate,
    ): Flow<List<Medication>>

    fun observeMedication(
        patientId: String,
        medicationId: String,
    ): Flow<MedicationWithReminders?>

    /** Updates medication fields without deleting or replacing its reminder children. */
    suspend fun saveMedication(medication: Medication)

    /** Atomically saves the medication and replaces its normalized reminder set. */
    suspend fun saveMedicationWithReminders(
        medication: Medication,
        reminders: List<ReminderDraft>,
    ): MedicationWithReminders

    suspend fun deleteMedication(patientId: String, medicationId: String): Boolean
}

interface HomeRepository {
    fun observeHome(patientId: String, today: LocalDate): Flow<HomeOverview>

    fun search(patientId: String, query: String): Flow<GlobalSearchResults>
}
