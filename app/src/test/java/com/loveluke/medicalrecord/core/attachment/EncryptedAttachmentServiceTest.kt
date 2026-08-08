package com.loveluke.medicalrecord.core.attachment

import android.net.Uri
import com.loveluke.medicalrecord.core.database.EncounterRepository
import com.loveluke.medicalrecord.core.model.Attachment
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.Encounter
import com.loveluke.medicalrecord.core.model.EncounterDetails
import com.loveluke.medicalrecord.core.security.InstallationNamespace
import com.loveluke.medicalrecord.core.security.SecureMaterialManager
import com.loveluke.medicalrecord.core.security.SecureMaterialStore
import com.loveluke.medicalrecord.core.security.WrappingKeyProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class EncryptedAttachmentServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val patientId = "11111111-1111-4111-8111-111111111111"
    private val encounterId = "22222222-2222-4222-8222-222222222222"
    private val now = Instant.parse("2026-08-08T05:00:00Z")
    private lateinit var storagePaths: AttachmentStoragePaths
    private lateinit var plaintextRegistry: TemporaryPlaintextRegistry
    private lateinit var secureMaterialManager: SecureMaterialManager
    private lateinit var repository: FakeAttachmentEncounterRepository

    @Before
    fun setUp() {
        storagePaths = AttachmentStoragePaths(temporaryFolder.newFolder("files"))
        plaintextRegistry = TemporaryPlaintextRegistry(temporaryFolder.newFolder("cache"))
        secureMaterialManager = SecureMaterialManager(
            SecureMaterialStore(
                noBackupFilesDir = temporaryFolder.newFolder("no-backup"),
                installationNamespace = InstallationNamespace("com.loveluke.medicalrecord.test"),
                wrappingKeyProvider = TestWrappingKeyProvider(),
            ),
        )
        repository = FakeAttachmentEncounterRepository(encounter(patientId, encounterId))
    }

    @Test
    fun `PDF import persists metadata preview close removes plaintext and delete commits in two phases`() =
        runTest {
            val service = newService()
            val sourceBytes = validPdf()

            val imported = service.importSources(
                patientId,
                encounterId,
                listOf(pdfSource(sourceBytes, pageCount = 2)),
            ) as AttachmentServiceImportResult.Completed
            val attachment = (imported.items.single() as AttachmentServiceItemResult.Imported).attachment

            assertEquals(2, attachment.pageCount)
            assertEquals(AttachmentIntegrityState.AVAILABLE, attachment.integrityState)
            assertEquals(listOf(attachment), repository.attachments)
            assertTrue(storagePaths.resolve(AttachmentRelativePath.parseStored(attachment.encryptedRelativePath)).isFile)

            val preview = service.openPreview(attachment) as AttachmentPreviewResult.Ready
            assertArrayEquals(sourceBytes, preview.handle.file.readBytes())
            val previewFile = preview.handle.file
            preview.handle.close()
            assertFalse(previewFile.exists())

            val deleted = service.delete(attachment) as AttachmentDeleteResult.Deleted
            assertTrue(deleted.metadataDeleted)
            assertEquals(1, deleted.ciphertextFilesDeleted)
            assertTrue(repository.attachments.isEmpty())
            assertFalse(storagePaths.resolve(AttachmentRelativePath.parseStored(attachment.encryptedRelativePath)).exists())
        }

    @Test
    fun `metadata write failure reports whether encrypted rollback remains`() = runTest {
        repository.failAttachmentSave = true
        val retainedService = newService(encryptedFileDeleter = EncryptedFileDeleter { false })

        val retained = retainedService.importSources(
            patientId,
            encounterId,
            listOf(pdfSource(validPdf(), pageCount = 1)),
        ) as AttachmentServiceImportResult.Completed

        assertEquals(
            AttachmentServiceFailure.METADATA_WRITE_FAILED_CIPHERTEXT_RETAINED,
            (retained.items.single() as AttachmentServiceItemResult.Failed).reason,
        )
        assertTrue(storagePaths.rootDirectory.walkTopDown().any { it.isFile })

        AttachmentOrphanCleaner(storagePaths).clean(emptySet())
        assertFalse(storagePaths.rootDirectory.walkTopDown().any { it.isFile })

        val cleaned = newService().importSources(
            patientId,
            encounterId,
            listOf(pdfSource(validPdf(), pageCount = 1)),
        ) as AttachmentServiceImportResult.Completed
        assertEquals(
            AttachmentServiceFailure.METADATA_WRITE_FAILED,
            (cleaned.items.single() as AttachmentServiceItemResult.Failed).reason,
        )
        assertFalse(storagePaths.rootDirectory.walkTopDown().any { it.isFile })
    }

    @Test
    fun `tamper quarantines only that attachment and records metadata state`() = runTest {
        val service = newService()
        val imported = service.importSources(
            patientId,
            encounterId,
            listOf(pdfSource(validPdf(), pageCount = 1)),
        ) as AttachmentServiceImportResult.Completed
        val attachment = (imported.items.single() as AttachmentServiceItemResult.Imported).attachment
        val encrypted = storagePaths.resolve(
            AttachmentRelativePath.parseStored(attachment.encryptedRelativePath),
        )
        val bytes = encrypted.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        encrypted.writeBytes(bytes)

        val result = service.openPreview(attachment) as AttachmentPreviewResult.Quarantined

        assertEquals(AttachmentQuarantineReason.AUTHENTICATION_FAILED, result.reason)
        assertTrue(result.metadataMarked)
        assertEquals(AttachmentIntegrityState.QUARANTINED, repository.attachments.single().integrityState)
        assertTrue(encrypted.exists())
        assertTrue(plaintextRegistry.previewRootDirectory.walkTopDown().none { it.isFile })
    }

    @Test
    fun `preview abandoned by caller is removed by cold start cleanup`() = runTest {
        val service = newService()
        val imported = service.importSources(
            patientId,
            encounterId,
            listOf(pdfSource(validPdf(), pageCount = 1)),
        ) as AttachmentServiceImportResult.Completed
        val attachment = (imported.items.single() as AttachmentServiceItemResult.Imported).attachment
        val preview = service.openPreview(attachment) as AttachmentPreviewResult.Ready
        assertTrue(preview.handle.file.exists())

        val report = plaintextRegistry.cleanupOnColdStart()

        assertEquals(1, report.deletedFiles)
        assertFalse(preview.handle.file.exists())
        assertEquals(PlaintextCleanupResult.ALREADY_ABSENT, preview.handle.cleanup())
    }

    @Test
    fun `camera handle commit and cancel both finalize plaintext exactly once`() = runTest {
        val service = newService()
        val prepared = service.prepareCameraCapture() as CameraCapturePreparation.Ready
        assertEquals("com.loveluke.medicalrecord.debug.fileprovider", prepared.handle.contentUri.authority)
        prepared.handle.file.writeBytes(validJpeg())

        val committed = service.commitCameraCapture(
            patientId,
            encounterId,
            prepared.handle,
        ) as CameraCaptureCommitResult.Completed

        assertTrue(
            (committed.importResult as AttachmentServiceImportResult.Completed)
                .items.single() is AttachmentServiceItemResult.Imported,
        )
        assertEquals(PlaintextCleanupResult.DELETED, committed.plaintextCleanup)
        assertFalse(prepared.handle.file.exists())
        assertTrue(
            service.commitCameraCapture(patientId, encounterId, prepared.handle) is
                CameraCaptureCommitResult.AlreadyFinalized,
        )

        val cancelled = (service.prepareCameraCapture() as CameraCapturePreparation.Ready).handle
        assertEquals(PlaintextCleanupResult.DELETED, service.cancelCameraCapture(cancelled))
        assertEquals(PlaintextCleanupResult.ALREADY_ABSENT, service.cancelCameraCapture(cancelled))
    }

    @Test
    fun `camera cleanup failure remains retryable without reopening commit`() {
        val plaintext = temporaryFolder.newFile("camera-retry.jpg").apply { writeBytes(byteArrayOf(1)) }
        var allowDelete = false
        val handle = CameraCaptureHandle(
            contentUri = Uri.parse("content://com.loveluke.medicalrecord.debug.fileprovider/retry"),
            plaintextHandle = TemporaryPlaintextHandle(
                plaintext,
                PlaintextFileDeleter { file -> allowDelete && file.delete() },
            ),
        )

        assertEquals(PlaintextCleanupResult.FAILED, handle.cancel())
        assertTrue(plaintext.exists())
        assertFalse(handle.claimForCommit())

        allowDelete = true
        assertEquals(PlaintextCleanupResult.DELETED, handle.cancel())
        assertFalse(plaintext.exists())
        assertEquals(PlaintextCleanupResult.ALREADY_ABSENT, handle.cancel())
    }

    @Test
    fun `single attachment delete failure preserves metadata and ciphertext`() = runTest {
        val importedBy = newService()
        val imported = importedBy.importSources(
            patientId,
            encounterId,
            listOf(pdfSource(validPdf(), pageCount = 1)),
        ) as AttachmentServiceImportResult.Completed
        val attachment = (imported.items.single() as AttachmentServiceItemResult.Imported).attachment
        val service = newService(
            deletionFileOps = ServiceDeletionFileOps(failStageCall = 1),
        )

        val result = service.delete(attachment) as AttachmentDeleteResult.CiphertextDeleteFailed

        assertTrue(result.metadataPreserved)
        assertEquals(0, result.ciphertextFilesStaged)
        assertEquals(AttachmentDeletionRollbackState.NOT_REQUIRED, result.rollbackState)
        assertEquals(listOf(attachment), repository.attachments)
        assertTrue(storagePaths.resolve(AttachmentRelativePath.parseStored(attachment.encryptedRelativePath)).exists())
    }

    @Test
    fun `encounter delete partial ciphertext failure preserves encounter and every metadata row`() =
        runTest {
            val importingService = newService()
            val imported = importingService.importSources(
                patientId,
                encounterId,
                listOf(
                    pdfSource(validPdf(), pageCount = 1),
                    pdfSource(validPdf(), pageCount = 3),
                ),
            ) as AttachmentServiceImportResult.Completed
            val importedAttachments = imported.items.map {
                (it as AttachmentServiceItemResult.Imported).attachment
            }
            val service = newService(
                deletionFileOps = ServiceDeletionFileOps(failStageCall = 2),
            )
            val details = EncounterDetails(repository.encounter, importedAttachments)

            val result = service.deleteEncounter(details) as EncounterDeleteResult.CiphertextDeleteFailed

            assertTrue(result.encounterAndAttachmentMetadataPreserved)
            assertEquals(1, result.ciphertextFilesStaged)
            assertEquals(AttachmentDeletionRollbackState.COMPLETE, result.rollbackState)
            assertEquals(0, result.tombstoneFilesRetained)
            assertFalse(repository.encounterDeleted)
            assertEquals(importedAttachments, repository.attachments)
            assertTrue(
                storagePaths.resolve(
                    AttachmentRelativePath.parseStored(importedAttachments[0].encryptedRelativePath),
                ).exists(),
            )
            assertTrue(
                storagePaths.resolve(
                    AttachmentRelativePath.parseStored(importedAttachments[1].encryptedRelativePath),
                ).exists(),
            )
        }

    @Test
    fun `single attachment metadata delete failure restores staged ciphertext`() = runTest {
        val importingService = newService()
        val imported = importingService.importSources(
            patientId,
            encounterId,
            listOf(pdfSource(validPdf(), pageCount = 1)),
        ) as AttachmentServiceImportResult.Completed
        val attachment = (imported.items.single() as AttachmentServiceItemResult.Imported).attachment
        val ciphertext = storagePaths.resolve(
            AttachmentRelativePath.parseStored(attachment.encryptedRelativePath),
        )
        repository.failAttachmentDelete = true

        val result = newService().delete(attachment) as AttachmentDeleteResult.MetadataDeleteFailed

        assertEquals(1, result.ciphertextFilesStaged)
        assertEquals(AttachmentDeletionRollbackState.COMPLETE, result.rollbackState)
        assertEquals(0, result.tombstoneFilesRetained)
        assertEquals(listOf(attachment), repository.attachments)
        assertTrue(ciphertext.exists())
    }

    @Test
    fun `encounter metadata delete failure restores every staged ciphertext`() = runTest {
        val importingService = newService()
        val imported = importingService.importSources(
            patientId,
            encounterId,
            listOf(
                pdfSource(validPdf(), pageCount = 1),
                pdfSource(validPdf(), pageCount = 2),
            ),
        ) as AttachmentServiceImportResult.Completed
        val attachments = imported.items.map {
            (it as AttachmentServiceItemResult.Imported).attachment
        }
        repository.failEncounterDelete = true

        val result = newService().deleteEncounter(
            EncounterDetails(repository.encounter, attachments),
        ) as EncounterDeleteResult.MetadataDeleteFailed

        assertEquals(2, result.ciphertextFilesStaged)
        assertEquals(AttachmentDeletionRollbackState.COMPLETE, result.rollbackState)
        assertFalse(repository.encounterDeleted)
        assertEquals(attachments, repository.attachments)
        attachments.forEach { attachment ->
            assertTrue(
                storagePaths.resolve(
                    AttachmentRelativePath.parseStored(attachment.encryptedRelativePath),
                ).exists(),
            )
        }
    }

    @Test
    fun `metadata commit with tombstone finalize failure is completed by cold start cleanup`() =
        runTest {
            val importingService = newService()
            val imported = importingService.importSources(
                patientId,
                encounterId,
                listOf(pdfSource(validPdf(), pageCount = 1)),
            ) as AttachmentServiceImportResult.Completed
            val attachment = (imported.items.single() as AttachmentServiceItemResult.Imported).attachment
            val service = newService(
                deletionFileOps = ServiceDeletionFileOps(failFinalize = true),
            )

            val result = service.delete(attachment) as AttachmentDeleteResult.Deleted

            assertTrue(result.metadataDeleted)
            assertEquals(0, result.ciphertextFilesDeleted)
            assertEquals(1, result.tombstoneFilesRetained)
            assertTrue(repository.attachments.isEmpty())
            assertTrue(
                storagePaths.rootDirectory.walkTopDown().any {
                    it.isFile && it.name.endsWith(".deleting")
                },
            )

            val cleanup = AttachmentOrphanCleaner(storagePaths).clean(emptySet())

            assertEquals(1, cleanup.deletedDeletingFiles)
            assertFalse(storagePaths.rootDirectory.walkTopDown().any { it.isFile })
        }

    private fun newService(
        encryptedFileDeleter: EncryptedFileDeleter = EncryptedFileDeleter(File::delete),
        deletionFileOps: AttachmentDeletionFileOps = DefaultAttachmentDeletionFileOps,
    ): DefaultEncryptedAttachmentService = DefaultEncryptedAttachmentService(
        encounterRepository = repository,
        secureMaterialManager = secureMaterialManager,
        storagePaths = storagePaths,
        temporaryPlaintextRegistry = plaintextRegistry,
        attachmentImporter = AttachmentImporter(storagePaths),
        cipherContainer = AttachmentCipherContainer(),
        uriSourceFactory = AttachmentUriSourceFactory { UnavailableTestSource },
        cameraContentUriFactory = CameraContentUriFactory { file ->
            Uri.parse("content://com.loveluke.medicalrecord.debug.fileprovider/${file.name}")
        },
        cameraImageParser = CameraImageParser { AttachmentSourceParseResult.Passed() },
        encryptedFileDeleter = encryptedFileDeleter,
        deletionTransaction = AttachmentDeletionTransaction(storagePaths, deletionFileOps),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun pdfSource(bytes: ByteArray, pageCount: Int): AttachmentInputSource =
        InputStreamAttachmentSource(
            displayName = "report.pdf",
            declaredMimeType = "application/pdf",
            openStream = { ByteArrayInputStream(bytes) },
            parseabilityCheck = { AttachmentSourceParseResult.Passed(pageCount) },
        )

    private fun encounter(patientId: String, encounterId: String): Encounter = Encounter(
        id = encounterId,
        patientId = patientId,
        visitDate = LocalDate.of(2026, 8, 8),
        visitTime = null,
        hospital = "Hospital",
        department = null,
        doctor = null,
        chiefComplaint = null,
        diagnosis = null,
        disposition = null,
        notes = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun validPdf(): ByteArray = "%PDF-1.7\n1 0 obj\n%%EOF\n".encodeToByteArray()

    private fun validJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(),
        0x00, 0x11, 0x08,
        0x00, 0x01,
        0x00, 0x01,
        0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )
}

private data object UnavailableTestSource : AttachmentInputSource {
    override val displayName: String? = null
    override val declaredMimeType: String? = null
    override fun openStream() = throw IllegalStateException("Not used")
}

private class TestWrappingKeyProvider : WrappingKeyProvider {
    private var key: SecretKey? = null

    override fun getExisting(alias: String): SecretKey? = key

    override fun create(alias: String): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

    override fun delete(alias: String): Boolean {
        val existed = key != null
        key = null
        return existed
    }
}

private class FakeAttachmentEncounterRepository(
    val encounter: Encounter,
) : EncounterRepository {
    val attachments = mutableListOf<Attachment>()
    var failAttachmentSave: Boolean = false
    var failAttachmentDelete: Boolean = false
    var failEncounterDelete: Boolean = false
    var encounterDeleted: Boolean = false

    override fun observeEncounters(patientId: String): Flow<List<Encounter>> =
        flowOf(if (encounterDeleted) emptyList() else listOf(encounter))

    override fun observeEncounter(patientId: String, encounterId: String): Flow<EncounterDetails?> =
        flowOf(
            if (
                encounterDeleted ||
                patientId != encounter.patientId ||
                encounterId != encounter.id
            ) {
                null
            } else {
                EncounterDetails(encounter, attachments.toList())
            },
        )

    override suspend fun saveEncounter(encounter: Encounter) = Unit

    override suspend fun deleteEncounter(patientId: String, encounterId: String): Boolean {
        if (failEncounterDelete) throw IllegalStateException("simulated encounter delete failure")
        if (encounterDeleted || patientId != encounter.patientId || encounterId != encounter.id) {
            return false
        }
        encounterDeleted = true
        attachments.clear()
        return true
    }

    override suspend fun saveAttachment(attachment: Attachment) {
        if (failAttachmentSave) throw IllegalStateException("simulated metadata failure")
        attachments.removeAll { it.id == attachment.id }
        attachments += attachment
    }

    override suspend fun deleteAttachment(patientId: String, attachmentId: String): Boolean {
        if (failAttachmentDelete) throw IllegalStateException("simulated attachment delete failure")
        return attachments.removeAll { it.patientId == patientId && it.id == attachmentId }
    }
}

private class ServiceDeletionFileOps(
    private val failStageCall: Int? = null,
    private val failRestore: Boolean = false,
    private val failFinalize: Boolean = false,
) : AttachmentDeletionFileOps {
    private var stageCalls = 0

    override fun stage(source: File, tombstone: File): Boolean {
        stageCalls += 1
        return if (stageCalls == failStageCall) {
            false
        } else {
            DefaultAttachmentDeletionFileOps.stage(source, tombstone)
        }
    }

    override fun restore(tombstone: File, source: File): Boolean =
        !failRestore && DefaultAttachmentDeletionFileOps.restore(tombstone, source)

    override fun finalize(tombstone: File): Boolean =
        !failFinalize && DefaultAttachmentDeletionFileOps.finalize(tombstone)
}
