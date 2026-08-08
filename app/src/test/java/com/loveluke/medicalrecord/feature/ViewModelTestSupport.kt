package com.loveluke.medicalrecord.feature

import android.net.Uri
import com.loveluke.medicalrecord.core.attachment.AttachmentDeleteResult
import com.loveluke.medicalrecord.core.attachment.AttachmentInputSource
import com.loveluke.medicalrecord.core.attachment.AttachmentPreviewFailure
import com.loveluke.medicalrecord.core.attachment.AttachmentPreviewResult
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceBatchRejection
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceImportResult
import com.loveluke.medicalrecord.core.attachment.CameraCaptureCommitResult
import com.loveluke.medicalrecord.core.attachment.CameraCaptureFailure
import com.loveluke.medicalrecord.core.attachment.CameraCaptureHandle
import com.loveluke.medicalrecord.core.attachment.CameraCapturePreparation
import com.loveluke.medicalrecord.core.attachment.EncounterDeleteResult
import com.loveluke.medicalrecord.core.attachment.EncryptedAttachmentService
import com.loveluke.medicalrecord.core.attachment.PlaintextCleanupResult
import com.loveluke.medicalrecord.core.database.EncounterRepository
import com.loveluke.medicalrecord.core.database.HomeRepository
import com.loveluke.medicalrecord.core.database.MedicationRepository
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.model.Attachment
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.AttachmentKind
import com.loveluke.medicalrecord.core.model.Encounter
import com.loveluke.medicalrecord.core.model.EncounterDetails
import com.loveluke.medicalrecord.core.model.GlobalSearchResults
import com.loveluke.medicalrecord.core.model.HomeCounts
import com.loveluke.medicalrecord.core.model.HomeOverview
import com.loveluke.medicalrecord.core.model.Medication
import com.loveluke.medicalrecord.core.model.MedicationFilter
import com.loveluke.medicalrecord.core.model.MedicationWithReminders
import com.loveluke.medicalrecord.core.model.PatientProfile
import com.loveluke.medicalrecord.core.model.Reminder
import com.loveluke.medicalrecord.core.model.ReminderDraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

internal class FakePatientRepository(
    var patient: PatientProfile = patientProfile(),
) : PatientRepository {
    var failuresRemaining: Int = 0
    var ensureCalls: Int = 0

    override suspend fun ensureDefaultPatient(): PatientProfile {
        ensureCalls += 1
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            error("Patient unavailable")
        }
        return patient
    }

    override fun observeDefaultPatient(): Flow<PatientProfile?> = flowOf(patient)

    override fun observePatient(patientId: String): Flow<PatientProfile?> =
        flowOf(patient.takeIf { it.id == patientId })
}

internal class FakeHomeRepository(
    initialOverview: HomeOverview = homeOverview(),
    initialSearchResults: GlobalSearchResults = GlobalSearchResults("", emptyList(), emptyList()),
) : HomeRepository {
    val overview = MutableStateFlow(initialOverview)
    val searchResults = MutableStateFlow(initialSearchResults)
    var overviewFailure: Throwable? = null
    var searchFailure: Throwable? = null
    val queries = mutableListOf<String>()
    val observedDates = mutableListOf<LocalDate>()

    override fun observeHome(patientId: String, today: LocalDate): Flow<HomeOverview> {
        observedDates += today
        return overviewFailure?.let { failure -> flow { throw failure } } ?: overview
    }

    override fun search(patientId: String, query: String): Flow<GlobalSearchResults> {
        queries += query
        return searchFailure?.let { failure -> flow { throw failure } } ?: searchResults
    }
}

internal class FakeEncounterRepository(
    initialEncounters: List<Encounter> = emptyList(),
    initialDetails: EncounterDetails? = null,
) : EncounterRepository {
    val encounters = MutableStateFlow(initialEncounters)
    val details = MutableStateFlow(initialDetails)
    val savedEncounters = mutableListOf<Encounter>()
    val savedAttachments = mutableListOf<Attachment>()
    var deleteEncounterResult: Boolean = true
    var deleteAttachmentResult: Boolean = true

    override fun observeEncounters(patientId: String): Flow<List<Encounter>> = encounters

    override fun observeEncounter(patientId: String, encounterId: String): Flow<EncounterDetails?> =
        details

    override suspend fun saveEncounter(encounter: Encounter) {
        savedEncounters += encounter
    }

    override suspend fun deleteEncounter(patientId: String, encounterId: String): Boolean =
        deleteEncounterResult

    override suspend fun saveAttachment(attachment: Attachment) {
        savedAttachments += attachment
    }

    override suspend fun deleteAttachment(patientId: String, attachmentId: String): Boolean =
        deleteAttachmentResult
}

internal class FakeMedicationRepository(
    initialMedications: List<Medication> = emptyList(),
    initialDetails: MedicationWithReminders? = null,
) : MedicationRepository {
    val medications = MutableStateFlow(initialMedications)
    val details = MutableStateFlow(initialDetails)
    val observedFilters = mutableListOf<MedicationFilter>()
    val observedDates = mutableListOf<LocalDate>()
    val savedMedications = mutableListOf<Medication>()
    var savedReminderDrafts: List<ReminderDraft> = emptyList()
    var deleteResult: Boolean = true

    override fun observeMedications(
        patientId: String,
        filter: MedicationFilter,
        today: LocalDate,
    ): Flow<List<Medication>> {
        observedFilters += filter
        observedDates += today
        return medications
    }

    override fun observeMedication(
        patientId: String,
        medicationId: String,
    ): Flow<MedicationWithReminders?> = details

    override suspend fun saveMedication(medication: Medication) {
        savedMedications += medication
    }

    override suspend fun saveMedicationWithReminders(
        medication: Medication,
        reminders: List<ReminderDraft>,
    ): MedicationWithReminders {
        savedMedications += medication
        savedReminderDrafts = reminders
        return MedicationWithReminders(medication, emptyList())
    }

    override suspend fun deleteMedication(patientId: String, medicationId: String): Boolean =
        deleteResult
}

internal class FakeEncryptedAttachmentService : EncryptedAttachmentService {
    var importUrisResult: AttachmentServiceImportResult = AttachmentServiceImportResult.Rejected(
        AttachmentServiceBatchRejection.INVALID_IDENTITY,
    )
    var cameraPreparationResult: CameraCapturePreparation =
        CameraCapturePreparation.Failed(CameraCaptureFailure.STORAGE_UNAVAILABLE)
    var cameraCommitResult: CameraCaptureCommitResult = CameraCaptureCommitResult.AlreadyFinalized
    var previewResult: AttachmentPreviewResult =
        AttachmentPreviewResult.Failed(AttachmentPreviewFailure.IO_FAILURE)
    var encounterDeleteResult: EncounterDeleteResult = EncounterDeleteResult.Deleted(
        ciphertextFilesDeleted = 0,
        encounterMetadataDeleted = true,
    )
    var attachmentDeleteResult: AttachmentDeleteResult = AttachmentDeleteResult.Deleted(
        ciphertextFilesDeleted = 1,
        metadataDeleted = true,
    )
    val importedUriBatches = mutableListOf<List<Uri>>()
    val deletedAttachments = mutableListOf<Attachment>()
    var deletedEncounter: EncounterDetails? = null

    override suspend fun importUris(
        patientId: String,
        encounterId: String,
        uris: List<Uri>,
    ): AttachmentServiceImportResult {
        importedUriBatches += uris
        return importUrisResult
    }

    override suspend fun importSources(
        patientId: String,
        encounterId: String,
        sources: List<AttachmentInputSource>,
    ): AttachmentServiceImportResult = error("Not configured for this test")

    override suspend fun prepareCameraCapture(): CameraCapturePreparation =
        cameraPreparationResult

    override suspend fun commitCameraCapture(
        patientId: String,
        encounterId: String,
        handle: CameraCaptureHandle,
    ): CameraCaptureCommitResult = cameraCommitResult

    override fun cancelCameraCapture(handle: CameraCaptureHandle): PlaintextCleanupResult =
        PlaintextCleanupResult.ALREADY_ABSENT

    override suspend fun openPreview(attachment: Attachment): AttachmentPreviewResult =
        previewResult

    override suspend fun delete(attachment: Attachment): AttachmentDeleteResult {
        deletedAttachments += attachment
        return attachmentDeleteResult
    }

    override suspend fun deleteEncounter(details: EncounterDetails): EncounterDeleteResult {
        deletedEncounter = details
        return encounterDeleteResult
    }
}

internal fun patientProfile(id: String = "00000000-0000-0000-0000-000000000001") = PatientProfile(
    id = id,
    isDefault = true,
    isHidden = true,
    createdAt = Instant.parse("2026-08-08T00:00:00Z"),
    updatedAt = Instant.parse("2026-08-08T00:00:00Z"),
)

internal fun encounter(
    id: String = "00000000-0000-0000-0000-000000000010",
    patientId: String = patientProfile().id,
) = Encounter(
    id = id,
    patientId = patientId,
    visitDate = LocalDate.of(2026, 8, 1),
    visitTime = LocalTime.of(9, 30),
    hospital = "Harbor Clinic",
    department = "Internal Medicine",
    doctor = "Dr. Lee",
    chiefComplaint = "Follow-up",
    diagnosis = "Stable",
    disposition = "Continue care",
    notes = null,
    createdAt = Instant.parse("2026-08-01T01:30:00Z"),
    updatedAt = Instant.parse("2026-08-01T01:30:00Z"),
)

internal fun attachment(
    id: String = "00000000-0000-0000-0000-000000000011",
    patientId: String = patientProfile().id,
    encounterId: String = encounter().id,
    displayName: String = "scan.jpg",
    integrityState: AttachmentIntegrityState = AttachmentIntegrityState.AVAILABLE,
) = Attachment(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    kind = AttachmentKind.IMAGE,
    displayName = displayName,
    mimeType = "image/jpeg",
    encryptedRelativePath = "attachments/$id.mra",
    encryptedThumbnailRelativePath = null,
    sizeBytes = 1_024,
    pageCount = null,
    cryptoVersion = 1,
    integrityState = integrityState,
    quarantinedAt = if (integrityState == AttachmentIntegrityState.QUARANTINED) {
        Instant.parse("2026-08-08T00:00:00Z")
    } else {
        null
    },
    createdAt = Instant.parse("2026-08-08T00:00:00Z"),
    updatedAt = Instant.parse("2026-08-08T00:00:00Z"),
)

internal fun medication(
    id: String = "00000000-0000-0000-0000-000000000020",
    patientId: String = patientProfile().id,
) = Medication(
    id = id,
    patientId = patientId,
    name = "Medicine A",
    dose = "5 mg",
    frequency = "Daily",
    startDate = LocalDate.of(2026, 8, 1),
    endDate = null,
    notes = null,
    createdAt = Instant.parse("2026-08-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
)

internal fun reminder(
    id: String = "00000000-0000-0000-0000-000000000030",
    medicationId: String = medication().id,
    timeMinutesOfDay: Int = 8 * 60,
) = Reminder(
    id = id,
    patientId = patientProfile().id,
    medicationId = medicationId,
    timeMinutesOfDay = timeMinutesOfDay,
    enabledByUser = true,
    createdAt = Instant.parse("2026-08-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
)

internal fun homeOverview(
    encounters: List<Encounter> = listOf(encounter()),
    medications: List<Medication> = listOf(medication()),
) = HomeOverview(
    counts = HomeCounts(
        encounterCount = encounters.size.toLong(),
        attachmentCount = 0,
        currentMedicationCount = medications.size.toLong(),
        todayReminderCount = 1,
    ),
    recentEncounters = encounters,
    recentCurrentMedications = medications,
)
