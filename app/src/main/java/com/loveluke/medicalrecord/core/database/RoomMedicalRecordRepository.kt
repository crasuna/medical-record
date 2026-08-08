package com.loveluke.medicalrecord.core.database

import androidx.room.withTransaction
import com.loveluke.medicalrecord.core.attachment.AttachmentPayloadKind
import com.loveluke.medicalrecord.core.attachment.AttachmentRelativePath
import com.loveluke.medicalrecord.core.model.Attachment
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
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Singleton
class RoomMedicalRecordRepository internal constructor(
    private val database: AppDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : PatientRepository, EncounterRepository, MedicationRepository, HomeRepository {
    @Inject
    constructor(database: AppDatabase) : this(
        database = database,
        clock = Clock.systemUTC(),
        idGenerator = { UUID.randomUUID().toString() },
    )

    private val patientDao = database.patientProfileDao()
    private val encounterDao = database.encounterDao()
    private val medicationDao = database.medicationDao()
    private val homeDao = database.homeDao()

    override suspend fun ensureDefaultPatient(): PatientProfile = database.withTransaction {
        patientDao.getDefault()?.let { return@withTransaction it.toModel() }
        val now = clock.instant()
        val generatedPatientId = idGenerator().requiredUuid("generated patient id")
        patientDao.insertIfAbsent(
            PatientProfileEntity(
                id = generatedPatientId,
                isDefault = true,
                isHidden = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        checkNotNull(patientDao.getDefault()) {
            "Default patient was not available after initialization"
        }.toModel()
    }

    override fun observeDefaultPatient(): Flow<PatientProfile?> =
        patientDao.observeDefault().map { it?.toModel() }

    override fun observePatient(patientId: String): Flow<PatientProfile?> =
        patientDao.observeById(patientId.requiredUuid("patientId")).map { it?.toModel() }

    override fun observeEncounters(patientId: String): Flow<List<Encounter>> =
        encounterDao.observeAll(patientId.requiredUuid("patientId")).map { encounters ->
            encounters.map(EncounterEntity::toModel)
        }

    override fun observeEncounter(
        patientId: String,
        encounterId: String,
    ): Flow<EncounterDetails?> {
        val requiredPatientId = patientId.requiredUuid("patientId")
        val requiredEncounterId = encounterId.requiredUuid("encounterId")
        return combine(
            encounterDao.observe(requiredPatientId, requiredEncounterId),
            encounterDao.observeAttachments(requiredPatientId, requiredEncounterId),
        ) { encounter, attachments ->
            encounter?.let {
                EncounterDetails(
                    encounter = it.toModel(),
                    attachments = attachments.map(AttachmentEntity::toModel),
                )
            }
        }
    }

    override suspend fun saveEncounter(encounter: Encounter) {
        encounterDao.upsert(encounter.normalized().toEntity())
    }

    override suspend fun deleteEncounter(patientId: String, encounterId: String): Boolean =
        encounterDao.delete(
            patientId.requiredUuid("patientId"),
            encounterId.requiredUuid("encounterId"),
        ) > 0

    override suspend fun saveAttachment(attachment: Attachment) {
        encounterDao.upsertAttachment(attachment.normalized().toEntity())
    }

    override suspend fun deleteAttachment(patientId: String, attachmentId: String): Boolean =
        encounterDao.deleteAttachment(
            patientId.requiredUuid("patientId"),
            attachmentId.requiredUuid("attachmentId"),
        ) > 0

    override fun observeMedications(
        patientId: String,
        filter: MedicationFilter,
        today: LocalDate,
    ): Flow<List<Medication>> {
        val requiredPatientId = patientId.requiredUuid("patientId")
        val entities = when (filter) {
            MedicationFilter.CURRENT -> medicationDao.observeCurrent(requiredPatientId, today)
            MedicationFilter.UPCOMING -> medicationDao.observeUpcoming(requiredPatientId, today)
            MedicationFilter.ENDED -> medicationDao.observeEnded(requiredPatientId, today)
            MedicationFilter.ALL -> medicationDao.observeAll(requiredPatientId)
        }
        return entities.map { medications -> medications.map(MedicationEntity::toModel) }
    }

    override fun observeMedication(
        patientId: String,
        medicationId: String,
    ): Flow<MedicationWithReminders?> {
        val requiredPatientId = patientId.requiredUuid("patientId")
        val requiredMedicationId = medicationId.requiredUuid("medicationId")
        return combine(
            medicationDao.observe(requiredPatientId, requiredMedicationId),
            medicationDao.observeReminders(requiredPatientId, requiredMedicationId),
        ) { medication, reminders ->
            medication?.let {
                MedicationWithReminders(
                    medication = it.toModel(),
                    reminders = reminders.map(ReminderEntity::toModel),
                )
            }
        }
    }

    override suspend fun saveMedication(medication: Medication) {
        medicationDao.upsert(medication.normalized().toEntity())
    }

    override suspend fun saveMedicationWithReminders(
        medication: Medication,
        reminders: List<ReminderDraft>,
    ): MedicationWithReminders = database.withTransaction {
        val normalizedMedication = medication.normalized()
        val normalizedReminders = reminders
            .onEach { require(it.timeMinutesOfDay in 0..1_439) }
            .groupBy(ReminderDraft::timeMinutesOfDay)
            .map { (timeMinutes, duplicates) ->
                ReminderDraft(
                    timeMinutesOfDay = timeMinutes,
                    enabledByUser = duplicates.any(ReminderDraft::enabledByUser),
                )
            }
            .sortedBy(ReminderDraft::timeMinutesOfDay)

        medicationDao.upsert(normalizedMedication.toEntity())
        val existingByTime = medicationDao.getReminders(
            normalizedMedication.patientId,
            normalizedMedication.id,
        ).associateBy(ReminderEntity::timeMinutesOfDay)

        medicationDao.deleteReminders(
            normalizedMedication.patientId,
            normalizedMedication.id,
        )

        val now = clock.instant()
        val reminderEntities = normalizedReminders.map { draft ->
            val existing = existingByTime[draft.timeMinutesOfDay]
            ReminderEntity(
                id = existing?.id ?: idGenerator().requiredUuid("generated reminder id"),
                patientId = normalizedMedication.patientId,
                medicationId = normalizedMedication.id,
                timeMinutesOfDay = draft.timeMinutesOfDay,
                enabledByUser = draft.enabledByUser,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        }
        if (reminderEntities.isNotEmpty()) {
            medicationDao.insertReminders(reminderEntities)
        }

        MedicationWithReminders(
            medication = normalizedMedication,
            reminders = reminderEntities.map(ReminderEntity::toModel),
        )
    }

    override suspend fun deleteMedication(patientId: String, medicationId: String): Boolean =
        medicationDao.delete(
            patientId.requiredUuid("patientId"),
            medicationId.requiredUuid("medicationId"),
        ) > 0

    override fun observeHome(patientId: String, today: LocalDate): Flow<HomeOverview> {
        val requiredPatientId = patientId.requiredUuid("patientId")
        return combine(
            homeDao.observeCounts(requiredPatientId, today),
            encounterDao.observeRecentThree(requiredPatientId),
            medicationDao.observeRecentThreeCurrent(requiredPatientId, today),
        ) { counts, encounters, medications ->
            HomeOverview(
                counts = HomeCounts(
                    encounterCount = counts.encounterCount,
                    attachmentCount = counts.attachmentCount,
                    currentMedicationCount = counts.currentMedicationCount,
                    todayReminderCount = counts.todayReminderCount,
                ),
                recentEncounters = encounters.map(EncounterEntity::toModel),
                recentCurrentMedications = medications.map(MedicationEntity::toModel),
            )
        }
    }

    override fun search(patientId: String, query: String): Flow<GlobalSearchResults> {
        val requiredPatientId = patientId.requiredUuid("patientId")
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return flowOf(
                GlobalSearchResults(
                    query = normalizedQuery,
                    encounters = emptyList(),
                    medications = emptyList(),
                ),
            )
        }

        val pattern = normalizedQuery.toEscapedLikePattern()
        return combine(
            encounterDao.search(requiredPatientId, pattern),
            medicationDao.search(requiredPatientId, pattern),
        ) { encounters, medications ->
            GlobalSearchResults(
                query = normalizedQuery,
                encounters = encounters.map(EncounterEntity::toModel),
                medications = medications.map(MedicationEntity::toModel),
            )
        }
    }
}

private fun PatientProfileEntity.toModel() = PatientProfile(
    id = id,
    isDefault = isDefault,
    isHidden = isHidden,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun EncounterEntity.toModel() = Encounter(
    id = id,
    patientId = patientId,
    visitDate = visitDate,
    visitTime = visitTime,
    hospital = hospital,
    department = department,
    doctor = doctor,
    chiefComplaint = chiefComplaint,
    diagnosis = diagnosis,
    disposition = disposition,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Encounter.toEntity() = EncounterEntity(
    id = id,
    patientId = patientId,
    visitDate = visitDate,
    visitTime = visitTime,
    hospital = hospital,
    department = department,
    doctor = doctor,
    chiefComplaint = chiefComplaint,
    diagnosis = diagnosis,
    disposition = disposition,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AttachmentEntity.toModel() = Attachment(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    kind = kind,
    displayName = displayName,
    mimeType = mimeType,
    encryptedRelativePath = encryptedRelativePath,
    encryptedThumbnailRelativePath = encryptedThumbnailRelativePath,
    sizeBytes = sizeBytes,
    pageCount = pageCount,
    cryptoVersion = cryptoVersion,
    integrityState = integrityState,
    quarantinedAt = quarantinedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Attachment.toEntity() = AttachmentEntity(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    kind = kind,
    displayName = displayName,
    mimeType = mimeType,
    encryptedRelativePath = encryptedRelativePath,
    encryptedThumbnailRelativePath = encryptedThumbnailRelativePath,
    sizeBytes = sizeBytes,
    pageCount = pageCount,
    cryptoVersion = cryptoVersion,
    integrityState = integrityState,
    quarantinedAt = quarantinedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MedicationEntity.toModel() = Medication(
    id = id,
    patientId = patientId,
    name = name,
    dose = dose,
    frequency = frequency,
    startDate = startDate,
    endDate = endDate,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Medication.toEntity() = MedicationEntity(
    id = id,
    patientId = patientId,
    name = name,
    dose = dose,
    frequency = frequency,
    startDate = startDate,
    endDate = endDate,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ReminderEntity.toModel() = Reminder(
    id = id,
    patientId = patientId,
    medicationId = medicationId,
    timeMinutesOfDay = timeMinutesOfDay,
    enabledByUser = enabledByUser,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Encounter.normalized(): Encounter = copy(
    id = id.requiredUuid("encounter id"),
    patientId = patientId.requiredUuid("patientId"),
    visitTime = visitTime?.also {
        require(it.second == 0 && it.nano == 0) { "Encounter time must have minute precision" }
    },
    hospital = hospital.required("hospital"),
    department = department.optionalText(),
    doctor = doctor.optionalText(),
    chiefComplaint = chiefComplaint.optionalText(),
    diagnosis = diagnosis.optionalText(),
    disposition = disposition.optionalText(),
    notes = notes.optionalText(),
)

private fun Attachment.normalized(): Attachment {
    val normalizedId = id.requiredUuid("attachment id")
    val originalPath = encryptedRelativePath.validatedAttachmentPath(
        expectedKind = AttachmentPayloadKind.ORIGINAL,
        label = "encrypted attachment relative path",
    )
    val thumbnailPath = encryptedThumbnailRelativePath?.validatedAttachmentPath(
        expectedKind = AttachmentPayloadKind.THUMBNAIL,
        label = "encrypted thumbnail relative path",
    )
    require(originalPath.attachmentIdFromPath() == normalizedId) {
        "Encrypted attachment path must use the attachment id"
    }
    require(thumbnailPath == null || thumbnailPath.attachmentIdFromPath() == normalizedId) {
        "Encrypted thumbnail path must use the attachment id"
    }
    when (kind) {
        com.loveluke.medicalrecord.core.model.AttachmentKind.IMAGE -> {
            require(pageCount == null) { "Image attachments must not have a page count" }
        }

        com.loveluke.medicalrecord.core.model.AttachmentKind.PDF -> {
            require(pageCount != null && pageCount > 0) {
                "PDF attachments require a positive page count"
            }
        }
    }
    when (integrityState) {
        com.loveluke.medicalrecord.core.model.AttachmentIntegrityState.AVAILABLE -> {
            require(quarantinedAt == null) {
                "Available attachments must not have a quarantine timestamp"
            }
        }

        com.loveluke.medicalrecord.core.model.AttachmentIntegrityState.QUARANTINED -> {
            require(quarantinedAt != null) {
                "Quarantined attachments require a quarantine timestamp"
            }
        }
    }
    return copy(
        id = normalizedId,
        patientId = patientId.requiredUuid("patientId"),
        encounterId = encounterId.requiredUuid("encounterId"),
        displayName = displayName.required("attachment display name"),
        mimeType = mimeType.required("attachment MIME type"),
        encryptedRelativePath = originalPath,
        encryptedThumbnailRelativePath = thumbnailPath,
    )
}

private fun Medication.normalized(): Medication = copy(
    id = id.requiredUuid("medication id"),
    patientId = patientId.requiredUuid("patientId"),
    name = name.required("medication name"),
    dose = dose.optionalText(),
    frequency = frequency.optionalText(),
    notes = notes.optionalText(),
).also {
    require(it.endDate == null || !it.endDate.isBefore(it.startDate)) {
        "Medication end date precedes its start date"
    }
}

private fun String.required(label: String): String = trim().also {
    require(it.isNotEmpty()) { "$label must not be blank" }
}

private fun String.requiredUuid(label: String): String {
    val normalized = required(label).lowercase()
    val parsed = runCatching { UUID.fromString(normalized) }.getOrNull()
    require(parsed != null && parsed.toString() == normalized) {
        "$label must be a canonical UUID"
    }
    return normalized
}

private fun String?.optionalText(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String.validatedAttachmentPath(
    expectedKind: AttachmentPayloadKind,
    label: String,
): String {
    val parsed = AttachmentRelativePath.parseStored(required(label))
    require(parsed.payloadKind == expectedKind) { "$label has the wrong payload kind" }
    return parsed.value
}

private fun String.attachmentIdFromPath(): String =
    substringAfter('/').substringBeforeLast('.')

private fun String.toEscapedLikePattern(): String = buildString(length + 2) {
    append('%')
    this@toEscapedLikePattern.forEach { character ->
        if (character == '%' || character == '_' || character == '\\') {
            append('\\')
        }
        append(character)
    }
    append('%')
}
