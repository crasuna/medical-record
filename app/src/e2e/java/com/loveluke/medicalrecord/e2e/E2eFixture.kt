package com.loveluke.medicalrecord.e2e

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.loveluke.medicalrecord.app.access.MedicalRecordAccessState
import com.loveluke.medicalrecord.core.attachment.AttachmentRelativePath
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceImportResult
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceItemResult
import com.loveluke.medicalrecord.core.attachment.AttachmentSourceParseResult
import com.loveluke.medicalrecord.core.attachment.InputStreamAttachmentSource
import com.loveluke.medicalrecord.core.model.Attachment
import com.loveluke.medicalrecord.core.model.Encounter
import com.loveluke.medicalrecord.core.model.EncounterDetails
import com.loveluke.medicalrecord.core.model.GlobalSearchResults
import com.loveluke.medicalrecord.core.model.Medication
import com.loveluke.medicalrecord.core.model.MedicationFilter
import com.loveluke.medicalrecord.core.model.MedicationWithReminders
import com.loveluke.medicalrecord.core.model.ReminderDraft
import com.loveluke.medicalrecord.core.reminder.ReminderSchedulingState
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

/** E2E-only façade over the production Hilt graph. It intentionally exposes no DAO or SQL API. */
class E2eFixture private constructor(
    private val entryPoint: E2eFixtureEntryPoint,
) {
    val now: Instant
        get() = entryPoint.timeSource().instant()

    val today: LocalDate
        get() = entryPoint.timeSource().today()

    val zoneId: ZoneId
        get() = entryPoint.timeSource().zoneId()

    suspend fun awaitReady(): String = withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
        entryPoint.accessController().state
            .filterIsInstance<MedicalRecordAccessState.Ready>()
            .first()
            .patientId
    }

    suspend fun defaultPatientId(): String =
        entryPoint.patientRepository().ensureDefaultPatient().id

    fun freezeTime(instant: Instant, zoneId: ZoneId = DEFAULT_ZONE_ID) {
        entryPoint.timeSource().freeze(instant, zoneId)
    }

    fun resetTime() {
        entryPoint.timeSource().reset()
    }

    suspend fun findEncounter(marker: String): Encounter? {
        val patientId = defaultPatientId()
        return entryPoint.encounterRepository().observeEncounters(patientId).first()
            .singleOrNull { encounter -> encounter.containsMarker(marker) }
    }

    suspend fun awaitEncounter(
        marker: String,
        predicate: (Encounter) -> Boolean = { true },
    ): Encounter = withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
        val patientId = defaultPatientId()
        entryPoint.encounterRepository().observeEncounters(patientId)
            .map { encounters -> encounters.singleOrNull { it.containsMarker(marker) } }
            .filterNotNull()
            .first(predicate)
    }

    suspend fun encounterDetails(encounterId: String): EncounterDetails? =
        entryPoint.encounterRepository()
            .observeEncounter(defaultPatientId(), encounterId)
            .first()

    suspend fun awaitEncounterAbsent(marker: String) = withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
        val patientId = defaultPatientId()
        entryPoint.encounterRepository().observeEncounters(patientId)
            .first { encounters -> encounters.none { it.containsMarker(marker) } }
        Unit
    }

    suspend fun seedEncounter(
        marker: String,
        visitDate: LocalDate = today,
        hospital: String = "E2E 医院 $marker",
        diagnosis: String = "E2E 诊断 $marker",
        notes: String = "E2E 备注 $marker",
    ): Encounter {
        val patientId = defaultPatientId()
        val encounter = Encounter(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            visitDate = visitDate,
            visitTime = LocalTime.of(9, 30),
            hospital = hospital,
            department = "E2E 科室",
            doctor = "E2E 医生",
            chiefComplaint = "E2E 主诉 $marker",
            diagnosis = diagnosis,
            disposition = "E2E 处置",
            notes = notes,
            createdAt = now,
            updatedAt = now,
        )
        entryPoint.encounterRepository().saveEncounter(encounter)
        return encounter
    }

    suspend fun findMedication(marker: String): MedicationWithReminders? {
        val patientId = defaultPatientId()
        val medication = entryPoint.medicationRepository()
            .observeMedications(patientId, MedicationFilter.ALL, today)
            .first()
            .singleOrNull { it.containsMarker(marker) }
            ?: return null
        return entryPoint.medicationRepository()
            .observeMedication(patientId, medication.id)
            .first()
    }

    suspend fun awaitMedication(marker: String): MedicationWithReminders =
        withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
            val patientId = defaultPatientId()
            val medication = entryPoint.medicationRepository()
                .observeMedications(patientId, MedicationFilter.ALL, today)
                .map { medications -> medications.singleOrNull { it.containsMarker(marker) } }
                .filterNotNull()
                .first()
            entryPoint.medicationRepository()
                .observeMedication(patientId, medication.id)
                .filterNotNull()
                .first()
        }

    suspend fun awaitMedicationAbsent(marker: String) = withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
        val patientId = defaultPatientId()
        entryPoint.medicationRepository()
            .observeMedications(patientId, MedicationFilter.ALL, today)
            .first { medications -> medications.none { it.containsMarker(marker) } }
        Unit
    }

    suspend fun seedMedication(
        marker: String,
        startDate: LocalDate,
        endDate: LocalDate?,
        reminderMinutes: List<Int> = emptyList(),
        name: String = "E2E 药品 $marker",
    ): MedicationWithReminders {
        val patientId = defaultPatientId()
        val medication = Medication(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            name = name,
            dose = "1 片 $marker",
            frequency = "每日一次",
            startDate = startDate,
            endDate = endDate,
            notes = "E2E 用药备注 $marker",
            createdAt = now,
            updatedAt = now,
        )
        return entryPoint.medicationRepository().saveMedicationWithReminders(
            medication = medication,
            reminders = reminderMinutes.map(::ReminderDraft),
        )
    }

    suspend fun search(marker: String): GlobalSearchResults =
        entryPoint.homeRepository().search(defaultPatientId(), marker).first()

    suspend fun importEncryptedJpeg(encounterId: String, marker: String): Attachment {
        val jpeg = generatedJpeg()
        val source = InputStreamAttachmentSource(
            displayName = "$marker.jpg",
            declaredMimeType = "image/jpeg",
            openStream = { ByteArrayInputStream(jpeg) },
            parseabilityCheck = { AttachmentSourceParseResult.Passed() },
        )
        val result = entryPoint.attachmentService().importSources(
            patientId = defaultPatientId(),
            encounterId = encounterId,
            sources = listOf(source),
        )
        val imported = (result as? AttachmentServiceImportResult.Completed)
            ?.items
            ?.singleOrNull() as? AttachmentServiceItemResult.Imported
        return checkNotNull(imported?.attachment) {
            "Real encrypted attachment import failed: $result"
        }
    }

    suspend fun awaitAttachment(encounterId: String, marker: String): Attachment =
        withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
            entryPoint.encounterRepository()
                .observeEncounter(defaultPatientId(), encounterId)
                .map { details ->
                    details?.attachments?.singleOrNull { it.displayName.contains(marker) }
                }
                .filterNotNull()
                .first()
        }

    suspend fun awaitSingleAttachment(encounterId: String): Attachment =
        withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
            entryPoint.encounterRepository()
                .observeEncounter(defaultPatientId(), encounterId)
                .map { details -> details?.attachments?.singleOrNull() }
                .filterNotNull()
                .first()
        }

    suspend fun readDecryptedAttachment(attachment: Attachment): ByteArray {
        val result = entryPoint.attachmentService().openPreview(attachment)
        val ready = result
            as? com.loveluke.medicalrecord.core.attachment.AttachmentPreviewResult.Ready
        return checkNotNull(ready) {
            "Encrypted attachment preview did not open: $result"
        }.handle.use { handle -> handle.file.readBytes() }
    }

    suspend fun awaitAttachmentAbsent(encounterId: String, attachmentId: String) =
        withTimeout(REPOSITORY_TIMEOUT_MILLIS) {
            entryPoint.encounterRepository()
                .observeEncounter(defaultPatientId(), encounterId)
                .first { details -> details?.attachments?.none { it.id == attachmentId } != false }
            Unit
        }

    fun encryptedAttachmentFile(attachment: Attachment): File =
        entryPoint.attachmentStoragePaths().resolve(
            AttachmentRelativePath.parseStored(attachment.encryptedRelativePath),
        )

    suspend fun reconcileReminders() {
        entryPoint.reminderRuntimeCoordinator().startAndReconcile()
    }

    suspend fun persistedReminderState(): ReminderSchedulingState? =
        entryPoint.reminderScheduleStore().readPersistedState()

    companion object {
        private const val REPOSITORY_TIMEOUT_MILLIS = 20_000L
        private val DEFAULT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

        fun from(context: Context): E2eFixture = E2eFixture(
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                E2eFixtureEntryPoint::class.java,
            ),
        )

        private fun generatedJpeg(): ByteArray {
            val bitmap = createBitmap(width = 48, height = 48)
            return try {
                bitmap.eraseColor(Color.rgb(34, 102, 153))
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

private fun Encounter.containsMarker(marker: String): Boolean = listOfNotNull(
    hospital,
    department,
    doctor,
    chiefComplaint,
    diagnosis,
    disposition,
    notes,
).any { it.contains(marker) }

private fun Medication.containsMarker(marker: String): Boolean = listOfNotNull(
    name,
    dose,
    frequency,
    notes,
).any { it.contains(marker) }
