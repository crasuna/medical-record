package com.loveluke.medicalrecord.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.loveluke.medicalrecord.core.attachment.AttachmentDeletingPath
import com.loveluke.medicalrecord.core.attachment.AttachmentDeletionFileOps
import com.loveluke.medicalrecord.core.attachment.AttachmentOrphanCleaner
import com.loveluke.medicalrecord.core.attachment.AttachmentRelativePath
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.attachment.EncryptedFileDeleter
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import com.loveluke.medicalrecord.core.database.AppDatabase
import com.loveluke.medicalrecord.core.database.AttachmentEntity
import com.loveluke.medicalrecord.core.database.EncounterEntity
import com.loveluke.medicalrecord.core.database.PatientProfileEntity
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.AttachmentKind
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalStorageCiphertextMaintenanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private var maintenanceDirectoryIndex = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.inMemoryBuilder(context)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `invalid stored attachment path is explicit failure and prevents an unsafe partial scan`() =
        runTest {
            insertAttachment("../outside.mra")
            val storagePaths = newStoragePaths("invalid-path")
            val unreferenced = AttachmentRelativePath.original(
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
            )
            val unreferencedFile = storagePaths.resolve(unreferenced).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(4))
            }
            val maintenance = newMaintenance(
                AttachmentOrphanCleaner(
                    storagePaths = storagePaths,
                    fileDeleter = EncryptedFileDeleter {
                        throw AssertionError("Cleaner must not run with an incomplete reference snapshot.")
                    },
                ),
            )

            val result = maintenance.removeUnreferencedCiphertext()

            val incomplete = result as CiphertextMaintenanceResult.Incomplete
            assertNull(incomplete.cleanupReport)
            assertEquals(1, incomplete.invalidStoredAttachmentPathCount)
            assertTrue(unreferencedFile.exists())
        }

    @Test
    fun `scan failure returns incomplete result with its diagnostic report`() = runTest {
        val storagePaths = newStoragePaths("scan-failure")
        val orphan = AttachmentRelativePath.original(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
        )
        val orphanFile = storagePaths.resolve(orphan).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5))
        }
        val maintenance = newMaintenance(
            AttachmentOrphanCleaner(
                storagePaths = storagePaths,
                fileDeleter = EncryptedFileDeleter { throw SecurityException("denied") },
            ),
        )

        val result = maintenance.removeUnreferencedCiphertext()

        val incomplete = result as CiphertextMaintenanceResult.Incomplete
        assertTrue(requireNotNull(incomplete.cleanupReport).scanFailed)
        assertTrue(orphanFile.exists())
    }

    @Test
    fun `failed deleting finalization returns incomplete result for retry`() = runTest {
        val storagePaths = newStoragePaths("deleting-failure")
        val original = AttachmentRelativePath.thumbnail(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
        )
        val deleting = AttachmentDeletingPath.create(
            original,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
        )
        val tombstone = storagePaths.resolve(deleting).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(6))
        }
        val maintenance = newMaintenance(
            AttachmentOrphanCleaner(
                storagePaths = storagePaths,
                deletionFileOps = AlwaysFailingDeletionFileOps,
            ),
        )

        val result = maintenance.removeUnreferencedCiphertext()

        val incomplete = result as CiphertextMaintenanceResult.Incomplete
        assertEquals(1, requireNotNull(incomplete.cleanupReport).failedDeletingOperations)
        assertTrue(tombstone.exists())
    }

    @Test
    fun `deleting conflict is retained and blocks completion`() = runTest {
        val original = AttachmentRelativePath.original(
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
        )
        insertAttachment(original.value)
        val storagePaths = newStoragePaths("deleting-conflict")
        val deleting = AttachmentDeletingPath.create(
            original,
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
        )
        val originalFile = storagePaths.resolve(original).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(8))
        }
        val tombstone = storagePaths.resolve(deleting).apply {
            writeBytes(byteArrayOf(9))
        }
        val maintenance = newMaintenance(AttachmentOrphanCleaner(storagePaths))

        val result = maintenance.removeUnreferencedCiphertext()

        val incomplete = result as CiphertextMaintenanceResult.Incomplete
        assertEquals(1, requireNotNull(incomplete.cleanupReport).deletingConflicts)
        assertTrue(originalFile.exists())
        assertTrue(tombstone.exists())
    }

    @Test
    fun `unrecognized files are retained for safety without blocking completed maintenance`() =
        runTest {
            val storagePaths = newStoragePaths("unknown-file")
            val unknown = storagePaths.rootDirectory.resolve("original/not-an-attachment").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val maintenance = newMaintenance(AttachmentOrphanCleaner(storagePaths))

            val result = maintenance.removeUnreferencedCiphertext()

            val complete = result as CiphertextMaintenanceResult.Complete
            assertEquals(1, complete.cleanupReport.ignoredUnrecognizedFiles)
            assertFalse(complete.cleanupReport.scanFailed)
            assertTrue(unknown.exists())
        }

    private fun newStoragePaths(name: String): AttachmentStoragePaths =
        AttachmentStoragePaths(temporaryFolder.newFolder(name))

    private fun newMaintenance(cleaner: AttachmentOrphanCleaner): LocalStorageMaintenance =
        LocalStorageMaintenance(
            temporaryPlaintextRegistry = TemporaryPlaintextRegistry(
                temporaryFolder.newFolder("cache-${maintenanceDirectoryIndex++}"),
            ),
            attachmentOrphanCleaner = cleaner,
            databaseProvider = Provider { database },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private suspend fun insertAttachment(storedOriginalPath: String) {
        database.patientProfileDao().insertIfAbsent(
            PatientProfileEntity(
                id = PATIENT_ID,
                isDefault = true,
                isHidden = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        database.encounterDao().insert(
            EncounterEntity(
                id = ENCOUNTER_ID,
                patientId = PATIENT_ID,
                visitDate = LocalDate.of(2026, 8, 8),
                visitTime = null,
                hospital = "Hospital",
                department = null,
                doctor = null,
                chiefComplaint = null,
                diagnosis = null,
                disposition = null,
                notes = null,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        database.encounterDao().insertAttachment(
            AttachmentEntity(
                id = ATTACHMENT_ID,
                patientId = PATIENT_ID,
                encounterId = ENCOUNTER_ID,
                kind = AttachmentKind.IMAGE,
                displayName = "scan.jpg",
                mimeType = "image/jpeg",
                encryptedRelativePath = storedOriginalPath,
                encryptedThumbnailRelativePath = null,
                sizeBytes = 1,
                pageCount = null,
                cryptoVersion = 1,
                integrityState = AttachmentIntegrityState.AVAILABLE,
                quarantinedAt = null,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
    }

    private object AlwaysFailingDeletionFileOps : AttachmentDeletionFileOps {
        override fun stage(source: File, tombstone: File): Boolean = false

        override fun restore(tombstone: File, source: File): Boolean = false

        override fun finalize(tombstone: File): Boolean = false
    }

    private companion object {
        const val PATIENT_ID = "11111111-1111-4111-8111-111111111111"
        const val ENCOUNTER_ID = "22222222-2222-4222-8222-222222222222"
        const val ATTACHMENT_ID = "33333333-3333-4333-8333-333333333333"
        val NOW: Instant = Instant.parse("2026-08-08T00:00:00Z")
    }
}
