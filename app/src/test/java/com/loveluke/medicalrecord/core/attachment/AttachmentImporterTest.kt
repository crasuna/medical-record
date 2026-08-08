package com.loveluke.medicalrecord.core.attachment

import com.loveluke.medicalrecord.core.security.SecretBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.UUID

class AttachmentImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val patientId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val encounterId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val firstAttachmentId = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val secondAttachmentId = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val masterKey = SecretBytes.copyOf(ByteArray(32) { 0x27 })

    @Test
    fun `batch over ten is rejected before any source is opened`() {
        var opens = 0
        val source = InputStreamAttachmentSource(
            displayName = "report.pdf",
            declaredMimeType = "application/pdf",
            openStream = {
                opens += 1
                ByteArrayInputStream(validPdf())
            },
        )
        val importer = newImporter(listOf(firstAttachmentId))

        val result = importer.importBatch(
            patientId = patientId,
            encounterId = encounterId,
            sources = List(11) { source },
            attachmentMasterKey = masterKey,
        )

        assertEquals(
            AttachmentBatchImportResult.Rejected(AttachmentBatchFailure.TOO_MANY_ITEMS),
            result,
        )
        assertEquals(0, opens)
        assertFalse(AttachmentStoragePaths(temporaryFolder.root).rootDirectory.exists())
    }

    @Test
    fun `batch reports per item success and MIME magic failure while cleaning rejected ciphertext`() {
        val importer = newImporter(listOf(firstAttachmentId, secondAttachmentId))
        val sources = listOf(
            InputStreamAttachmentSource(
                displayName = "report.pdf",
                declaredMimeType = "application/pdf",
                openStream = { ByteArrayInputStream(validPdf()) },
                parseabilityCheck = { AttachmentSourceParseResult.Passed(pageCount = 3) },
            ),
            InputStreamAttachmentSource(
                displayName = "not-really-an-image.png",
                declaredMimeType = "image/png",
                openStream = { ByteArrayInputStream(validPdf()) },
            ),
        )

        val result = importer.importBatch(
            patientId = patientId,
            encounterId = encounterId,
            sources = sources,
            attachmentMasterKey = masterKey,
        ) as AttachmentBatchImportResult.Completed

        val success = result.items[0] as AttachmentItemImportResult.Success
        assertEquals(firstAttachmentId, success.attachment.attachmentId)
        assertEquals(AttachmentMediaType.PDF, success.attachment.mediaType)
        assertEquals(3, success.attachment.pageCount)
        assertTrue(AttachmentStoragePaths(temporaryFolder.root).resolve(success.attachment.relativePath).isFile)
        assertEquals(
            AttachmentItemImportResult.Failure(
                index = 1,
                reason = AttachmentImportFailure.MIME_MAGIC_MISMATCH,
            ),
            result.items[1],
        )
        assertFalse(
            AttachmentStoragePaths(temporaryFolder.root)
                .resolve(AttachmentRelativePath.original(secondAttachmentId))
                .exists(),
        )
    }

    @Test
    fun `platform parser failure rejects only that item and removes its ciphertext`() {
        val importer = newImporter(listOf(firstAttachmentId))
        val source = InputStreamAttachmentSource(
            displayName = "malformed-for-platform.pdf",
            declaredMimeType = "application/pdf",
            openStream = { ByteArrayInputStream(validPdf()) },
            parseabilityCheck = { AttachmentSourceParseResult.Failed },
        )

        val result = importer.importBatch(
            patientId = patientId,
            encounterId = encounterId,
            sources = listOf(source),
            attachmentMasterKey = masterKey,
        ) as AttachmentBatchImportResult.Completed

        assertEquals(
            AttachmentItemImportResult.Failure(0, AttachmentImportFailure.PLATFORM_PARSE_FAILED),
            result.items.single(),
        )
        assertFalse(
            AttachmentStoragePaths(temporaryFolder.root)
                .resolve(AttachmentRelativePath.original(firstAttachmentId))
                .exists(),
        )
    }

    @Test
    fun `PDF import requires a positive platform page count`() {
        val importer = newImporter(listOf(firstAttachmentId))
        val source = InputStreamAttachmentSource(
            displayName = "report.pdf",
            declaredMimeType = "application/pdf",
            openStream = { ByteArrayInputStream(validPdf()) },
            parseabilityCheck = { AttachmentSourceParseResult.NotAvailable },
        )

        val result = importer.importBatch(
            patientId = patientId,
            encounterId = encounterId,
            sources = listOf(source),
            attachmentMasterKey = masterKey,
        ) as AttachmentBatchImportResult.Completed

        assertEquals(
            AttachmentItemImportResult.Failure(0, AttachmentImportFailure.PLATFORM_PARSE_FAILED),
            result.items.single(),
        )
        assertFalse(
            AttachmentStoragePaths(temporaryFolder.root)
                .resolve(AttachmentRelativePath.original(firstAttachmentId))
                .exists(),
        )
    }

    private fun newImporter(ids: List<UUID>): AttachmentImporter {
        val iterator = ids.iterator()
        return AttachmentImporter(
            storagePaths = AttachmentStoragePaths(temporaryFolder.root),
            attachmentIdGenerator = { iterator.next() },
        )
    }

    private fun validPdf(): ByteArray = "%PDF-1.7\n1 0 obj\n%%EOF\n".encodeToByteArray()
}
