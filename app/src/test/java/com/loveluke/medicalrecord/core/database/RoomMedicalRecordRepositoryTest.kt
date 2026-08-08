package com.loveluke.medicalrecord.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.loveluke.medicalrecord.core.attachment.AttachmentRelativePath
import com.loveluke.medicalrecord.core.model.Attachment
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.AttachmentKind
import com.loveluke.medicalrecord.core.model.Encounter
import com.loveluke.medicalrecord.core.model.Medication
import com.loveluke.medicalrecord.core.model.MedicationFilter
import com.loveluke.medicalrecord.core.model.ReminderDraft
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMedicalRecordRepositoryTest {
    private val now = Instant.parse("2026-08-08T08:00:00Z")
    private val today = LocalDate.of(2026, 8, 8)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val defaultPatientId = generatedUuid(1)

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomMedicalRecordRepository
    private var generatedId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.inMemoryBuilder(context)
            .allowMainThreadQueries()
            .build()
        repository = RoomMedicalRecordRepository(database, clock) {
            generatedUuid(++generatedId)
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun defaultPatientInitializationIsPersistedAndIdempotent() = runTest {
        val first = repository.ensureDefaultPatient()
        val second = repository.ensureDefaultPatient()

        assertEquals(defaultPatientId, first.id)
        assertEquals(first, second)
        assertTrue(first.isDefault)
        assertTrue(first.isHidden)
        assertEquals(1, database.patientProfileDao().count())
    }

    @Test
    fun onlyOneDefaultPatientIsAllowedWhileMultipleNonDefaultPatientsCanCoexist() = runTest {
        repository.ensureDefaultPatient()
        insertPatient("patient-b")
        insertPatient("patient-c")

        assertEquals(3, database.patientProfileDao().count())
        assertConstraintFailure {
            database.patientProfileDao().upsert(
                PatientProfileEntity(
                    id = canonicalUuid("second-default"),
                    isDefault = true,
                    isHidden = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        assertEquals(3, database.patientProfileDao().count())
    }

    @Test
    fun repositoryRejectsNonCanonicalIdsAndUnsafeAttachmentPaths() = runTest {
        repository.ensureDefaultPatient()
        val invalidIdFailure = runCatching {
            repository.saveEncounter(
                encounter(id = "valid-label", visitDate = today).copy(id = "not-a-uuid"),
            )
        }.exceptionOrNull()
        assertTrue(invalidIdFailure is IllegalArgumentException)

        val encounter = encounter(id = "encounter-for-path", visitDate = today)
        repository.saveEncounter(encounter)
        val unsafePathFailure = runCatching {
            repository.saveAttachment(
                attachment(id = "unsafe-attachment", encounterId = encounter.id).copy(
                    encryptedRelativePath = "../outside.mra",
                ),
            )
        }.exceptionOrNull()
        assertTrue(unsafePathFailure is IllegalArgumentException)
    }

    @Test
    fun storedPathInventoryIncludesQuarantinedAttachments() = runTest {
        repository.ensureDefaultPatient()
        val encounter = encounter(id = "path-inventory-encounter", visitDate = today)
        repository.saveEncounter(encounter)
        val available = attachment(id = "available-attachment", encounterId = encounter.id)
        val quarantined = attachment(id = "quarantined-attachment", encounterId = encounter.id)
            .copy(
                integrityState = AttachmentIntegrityState.QUARANTINED,
                quarantinedAt = now,
            )
        repository.saveAttachment(available)
        repository.saveAttachment(quarantined)

        val paths = database.encounterDao().getAllStoredAttachmentPaths()
        assertEquals(
            setOf(available.encryptedRelativePath, quarantined.encryptedRelativePath),
            paths.map { it.originalRelativePath }.toSet(),
        )
    }

    @Test
    fun updatingParentPreservesChildrenAndDeletingParentCascades() = runTest {
        repository.ensureDefaultPatient()
        val encounter = encounter(id = "encounter-1", visitDate = today)
        repository.saveEncounter(encounter)
        repository.saveAttachment(attachment(id = "attachment-1", encounterId = encounter.id))

        repository.saveEncounter(encounter.copy(hospital = "Updated Hospital", updatedAt = now.plusSeconds(1)))
        assertEquals(
            listOf(canonicalUuid("attachment-1")),
            database.encounterDao().getAttachments(encounter.patientId, encounter.id).map { it.id },
        )

        assertTrue(repository.deleteEncounter(encounter.patientId, encounter.id))
        assertTrue(database.encounterDao().getAttachments(encounter.patientId, encounter.id).isEmpty())

        val medication = medication(id = "medication-1")
        repository.saveMedicationWithReminders(
            medication,
            listOf(ReminderDraft(480), ReminderDraft(720)),
        )
        repository.saveMedication(
            medication.copy(name = "Updated medication", updatedAt = now.plusSeconds(2)),
        )
        assertEquals(
            listOf(480, 720),
            database.medicationDao().getReminders(medication.patientId, medication.id)
                .map { it.timeMinutesOfDay },
        )
        assertTrue(repository.deleteMedication(medication.patientId, medication.id))
        assertTrue(database.medicationDao().getReminders(medication.patientId, medication.id).isEmpty())
    }

    @Test
    fun compositeForeignKeysRejectCrossPatientAttachmentAndReminder() = runTest {
        repository.ensureDefaultPatient()
        insertPatient("patient-b")
        repository.saveEncounter(encounter(id = "encounter-a", visitDate = today))
        repository.saveMedication(medication(id = "medication-a"))

        assertConstraintFailure {
            database.encounterDao().insertAttachment(
                attachment(
                    id = "cross-patient-attachment",
                    patientId = canonicalUuid("patient-b"),
                    encounterId = "encounter-a",
                ).toEntityForTest(),
            )
        }
        assertConstraintFailure {
            database.medicationDao().insertReminder(
                ReminderEntity(
                    id = "cross-patient-reminder",
                    patientId = canonicalUuid("patient-b"),
                    medicationId = canonicalUuid("medication-a"),
                    timeMinutesOfDay = 600,
                    enabledByUser = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Test
    fun reminderRangeTriggerAndCompositeUniquenessAreEnforced() = runTest {
        repository.ensureDefaultPatient()
        repository.saveMedication(medication(id = "medication-1"))
        val dao = database.medicationDao()
        dao.insertReminder(reminderEntity(id = "valid", medicationId = "medication-1", minute = 540))

        assertConstraintFailure {
            dao.insertReminder(reminderEntity(id = "duplicate", medicationId = "medication-1", minute = 540))
        }

        assertConstraintFailure {
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO reminders(
                    id, patient_id, medication_id, time_minutes, enabled_by_user,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    canonicalUuid("outside-day"),
                    defaultPatientId,
                    canonicalUuid("medication-1"),
                    1_440,
                    1,
                    now.toEpochMilli(),
                    now.toEpochMilli(),
                ),
            )
        }

        assertConstraintFailure {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE reminders SET time_minutes = -1 WHERE id = ?",
                arrayOf<Any>(canonicalUuid("valid")),
            )
        }
    }

    @Test
    fun metadataTriggersRejectBlankRequiredTextInvalidDatesAndAttachmentNumbers() = runTest {
        repository.ensureDefaultPatient()
        val supportDatabase = database.openHelper.writableDatabase

        assertConstraintFailure {
            supportDatabase.execSQL(
                """
                INSERT INTO encounters(
                    id, patient_id, visit_date, visit_time, hospital, department, doctor,
                    chief_complaint, diagnosis, disposition, notes, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    canonicalUuid("blank-hospital"),
                    defaultPatientId,
                    today.toEpochDay(),
                    600,
                    "   ",
                    "Department",
                    "Doctor",
                    "Complaint",
                    "Diagnosis",
                    "Disposition",
                    "Notes",
                    now.toEpochMilli(),
                    now.toEpochMilli(),
                ),
            )
        }

        assertConstraintFailure {
            supportDatabase.execSQL(
                """
                INSERT INTO medications(
                    id, patient_id, name, dose, frequency, start_date, end_date, notes,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    canonicalUuid("invalid-date-medication"),
                    defaultPatientId,
                    "Medication",
                    "10 mg",
                    "Daily",
                    today.toEpochDay(),
                    today.minusDays(1).toEpochDay(),
                    "Notes",
                    now.toEpochMilli(),
                    now.toEpochMilli(),
                ),
            )
        }

        val encounter = encounter(id = "attachment-constraint-parent", visitDate = today)
        repository.saveEncounter(encounter)
        assertConstraintFailure {
            supportDatabase.execSQL(
                """
                INSERT INTO attachments(
                    id, patient_id, encounter_id, kind, display_name, mime_type,
                    encrypted_relative_path, encrypted_thumbnail_relative_path, size_bytes,
                    page_count, crypto_version, integrity_state, quarantined_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, NULL, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    canonicalUuid("invalid-attachment"),
                    defaultPatientId,
                    encounter.id,
                    "PDF",
                    "scan.pdf",
                    "application/pdf",
                    AttachmentRelativePath.original(
                        UUID.fromString(canonicalUuid("invalid-attachment")),
                    ).value,
                    -1,
                    1,
                    1,
                    "AVAILABLE",
                    now.toEpochMilli(),
                    now.toEpochMilli(),
                ),
            )
        }
    }

    @Test
    fun medicationAndReminderReplacementIsAtomicDeduplicatedAndSorted() = runTest {
        repository.ensureDefaultPatient()
        val medication = medication(id = "medication-1", name = "Original")
        val saved = repository.saveMedicationWithReminders(
            medication,
            listOf(
                ReminderDraft(720),
                ReminderDraft(480),
                ReminderDraft(720, enabledByUser = false),
            ),
        )

        assertEquals(listOf(480, 720), saved.reminders.map { it.timeMinutesOfDay })
        assertTrue(saved.reminders.single { it.timeMinutesOfDay == 720 }.enabledByUser)

        repository.saveMedicationWithReminders(
            medication.copy(name = "Stable", updatedAt = now.plusSeconds(1)),
            listOf(ReminderDraft(500)),
        )
        val failingRepository = RoomMedicalRecordRepository(database, clock) {
            canonicalUuid("duplicate-id")
        }
        val failure = runCatching {
            failingRepository.saveMedicationWithReminders(
                medication.copy(name = "Must Roll Back", updatedAt = now.plusSeconds(2)),
                listOf(ReminderDraft(600), ReminderDraft(700)),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("Stable", database.medicationDao().get(medication.patientId, medication.id)?.name)
        assertEquals(
            listOf(500),
            database.medicationDao().getReminders(medication.patientId, medication.id)
                .map { it.timeMinutesOfDay },
        )
    }

    @Test
    fun patientQueriesStatusesHomeAndSearchRemainPatientScoped() = runTest {
        repository.ensureDefaultPatient()
        insertPatient("patient-b")

        (1..4).forEach { offset ->
            repository.saveEncounter(
                encounter(
                    id = "encounter-$offset",
                    visitDate = today.minusDays(offset.toLong()),
                    hospital = "Hospital $offset",
                ),
            )
        }
        repository.saveAttachment(
            attachment(
                id = "attachment-search",
                encounterId = "encounter-2",
                displayName = "CardioGram.PDF",
            ),
        )
        repository.saveEncounter(
            encounter(
                id = "other-patient-encounter",
                patientId = "patient-b",
                visitDate = today,
                hospital = "Other Patient Hospital",
            ),
        )

        repository.saveMedicationWithReminders(
            medication(id = "current", startDate = today.minusDays(1), endDate = today),
            listOf(ReminderDraft(480), ReminderDraft(720)),
        )
        repository.saveMedication(
            medication(id = "upcoming", startDate = today.plusDays(1), endDate = null),
        )
        repository.saveMedication(
            medication(id = "ended", startDate = today.minusDays(10), endDate = today.minusDays(1)),
        )
        repository.saveMedication(
            medication(
                id = "other-patient-medication",
                patientId = "patient-b",
                startDate = today,
                endDate = null,
            ),
        )

        assertEquals(
            listOf(canonicalUuid("current")),
            repository.observeMedications(
                defaultPatientId,
                MedicationFilter.CURRENT,
                today,
            ).first().map { it.id },
        )
        assertEquals(
            listOf(canonicalUuid("upcoming")),
            repository.observeMedications(
                defaultPatientId,
                MedicationFilter.UPCOMING,
                today,
            ).first().map { it.id },
        )
        assertEquals(
            listOf(canonicalUuid("ended")),
            repository.observeMedications(
                defaultPatientId,
                MedicationFilter.ENDED,
                today,
            ).first().map { it.id },
        )

        val search = repository.search(defaultPatientId, "  cardiogram  ").first()
        assertEquals("cardiogram", search.query)
        assertEquals(listOf(canonicalUuid("encounter-2")), search.encounters.map { it.id })
        assertTrue(repository.search(defaultPatientId, "hOsPiTaL 1").first()
            .encounters.any { it.id == canonicalUuid("encounter-1") })
        assertTrue(repository.search(defaultPatientId, "Other Patient").first()
            .encounters.isEmpty())

        val home = repository.observeHome(defaultPatientId, today).first()
        assertEquals(4L, home.counts.encounterCount)
        assertEquals(1L, home.counts.attachmentCount)
        assertEquals(1L, home.counts.currentMedicationCount)
        assertEquals(2L, home.counts.todayReminderCount)
        assertEquals(
            listOf(
                canonicalUuid("encounter-1"),
                canonicalUuid("encounter-2"),
                canonicalUuid("encounter-3"),
            ),
            home.recentEncounters.map { it.id },
        )
        assertEquals(
            listOf(canonicalUuid("current")),
            home.recentCurrentMedications.map { it.id },
        )
        assertEquals(
            listOf(
                canonicalUuid("encounter-1"),
                canonicalUuid("encounter-2"),
                canonicalUuid("encounter-3"),
                canonicalUuid("encounter-4"),
            ),
            repository.observeEncounters(defaultPatientId).first().map { it.id },
        )
    }

    @Test
    fun missingPatientOrMedicationReturnsNullWithoutLeakingOtherPatientsData() = runTest {
        repository.ensureDefaultPatient()
        insertPatient("patient-b")
        repository.saveMedication(medication(id = "medication-a"))

        assertNull(
            repository.observeMedication(
                canonicalUuid("patient-b"),
                canonicalUuid("medication-a"),
            ).first(),
        )
        assertNull(
            repository.observeEncounter(
                canonicalUuid("patient-b"),
                canonicalUuid("missing"),
            ).first(),
        )
    }

    private suspend fun insertPatient(id: String) {
        database.patientProfileDao().upsert(
            PatientProfileEntity(
                id = canonicalUuid(id),
                isDefault = false,
                isHidden = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun encounter(
        id: String,
        patientId: String = defaultPatientId,
        visitDate: LocalDate,
        hospital: String = "Hospital",
    ) = Encounter(
        id = canonicalUuid(id),
        patientId = canonicalUuid(patientId),
        visitDate = visitDate,
        visitTime = LocalTime.of(10, 30),
        hospital = hospital,
        department = "Cardiology",
        doctor = "Doctor",
        chiefComplaint = "Complaint",
        diagnosis = "Diagnosis",
        disposition = "Disposition",
        notes = "Notes",
        createdAt = now,
        updatedAt = now,
    )

    private fun attachment(
        id: String,
        patientId: String = defaultPatientId,
        encounterId: String,
        displayName: String = "scan.pdf",
    ) = Attachment(
        id = canonicalUuid(id),
        patientId = canonicalUuid(patientId),
        encounterId = canonicalUuid(encounterId),
        kind = AttachmentKind.PDF,
        displayName = displayName,
        mimeType = "application/pdf",
        encryptedRelativePath = AttachmentRelativePath.original(
            UUID.fromString(canonicalUuid(id)),
        ).value,
        encryptedThumbnailRelativePath = null,
        sizeBytes = 1_024,
        pageCount = 1,
        cryptoVersion = 1,
        integrityState = AttachmentIntegrityState.AVAILABLE,
        quarantinedAt = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun medication(
        id: String,
        patientId: String = defaultPatientId,
        name: String = "Medicine $id",
        startDate: LocalDate = today,
        endDate: LocalDate? = null,
    ) = Medication(
        id = canonicalUuid(id),
        patientId = canonicalUuid(patientId),
        name = name,
        dose = "10 mg",
        frequency = "Daily",
        startDate = startDate,
        endDate = endDate,
        notes = "Notes",
        createdAt = now,
        updatedAt = now,
    )

    private fun reminderEntity(id: String, medicationId: String, minute: Int) = ReminderEntity(
        id = canonicalUuid(id),
        patientId = defaultPatientId,
        medicationId = canonicalUuid(medicationId),
        timeMinutesOfDay = minute,
        enabledByUser = true,
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun assertConstraintFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("Expected a SQLite constraint failure", failure)
        assertTrue(
            "Expected a constraint failure but was ${failure?.javaClass?.name}: ${failure?.message}",
            failure.isConstraintFailure(),
        )
    }
}

private fun Attachment.toEntityForTest() = AttachmentEntity(
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

private fun Throwable?.isConstraintFailure(): Boolean {
    var current = this
    while (current != null) {
        if (current.javaClass.simpleName.contains("Constraint", ignoreCase = true) ||
            current.message?.contains("constraint", ignoreCase = true) == true ||
            current.message?.contains("must be between", ignoreCase = true) == true
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun canonicalUuid(value: String): String {
    val normalized = value.lowercase()
    return runCatching { UUID.fromString(normalized) }
        .getOrNull()
        ?.takeIf { it.toString() == normalized }
        ?.toString()
        ?: UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}

private fun generatedUuid(index: Int): String =
    "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
