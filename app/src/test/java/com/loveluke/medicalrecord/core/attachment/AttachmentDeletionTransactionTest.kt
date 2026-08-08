package com.loveluke.medicalrecord.core.attachment

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentDeletionTransactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val operationId = UUID.fromString("99999999-9999-4999-8999-999999999999")

    @Test
    fun `successful deletion stages metadata then finalizes ciphertext`() = runTest {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val relative = AttachmentRelativePath.original(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
        )
        val source = paths.resolve(relative).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        var metadataDeleted = false
        val transaction = AttachmentDeletionTransaction(
            storagePaths = paths,
            operationIdFactory = { operationId },
        )

        val result = transaction.execute(listOf(relative)) {
            metadataDeleted = true
            true
        } as AttachmentDeletionTransactionResult.Committed

        assertTrue(metadataDeleted)
        assertEquals(1, result.ciphertextFilesStaged)
        assertEquals(1, result.ciphertextFilesFinalized)
        assertEquals(0, result.tombstoneFilesRetained)
        assertFalse(source.exists())
        assertFalse(paths.resolve(AttachmentDeletingPath.create(relative, operationId)).exists())
    }

    @Test
    fun `partial stage failure restores earlier ciphertext in reverse order`() = runTest {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val first = AttachmentRelativePath.original(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        val second = AttachmentRelativePath.original(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
        )
        val third = AttachmentRelativePath.original(
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
        )
        listOf(first, second, third).forEach { relative ->
            paths.resolve(relative).apply {
                parentFile?.mkdirs()
                writeBytes(relative.value.encodeToByteArray())
            }
        }
        val fileOps = RecordingDeletionFileOps(failStageCall = 3)
        var metadataCalled = false
        val transaction = AttachmentDeletionTransaction(paths, fileOps) { operationId }

        val result = transaction.execute(listOf(first, second, third)) {
            metadataCalled = true
            true
        } as AttachmentDeletionTransactionResult.StageFailed

        assertFalse(metadataCalled)
        assertEquals(2, result.ciphertextFilesStaged)
        assertEquals(AttachmentDeletionRollbackState.COMPLETE, result.rollbackState)
        assertEquals(0, result.tombstoneFilesRetained)
        assertEquals(
            listOf(second.value, first.value),
            fileOps.restoredOriginals,
        )
        assertTrue(paths.resolve(first).exists())
        assertTrue(paths.resolve(second).exists())
        assertTrue(paths.resolve(third).exists())
    }

    @Test
    fun `metadata rejection restores staged ciphertext`() = runTest {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val relative = AttachmentRelativePath.original(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
        )
        val source = paths.resolve(relative).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4))
        }
        val transaction = AttachmentDeletionTransaction(
            storagePaths = paths,
            operationIdFactory = { operationId },
        )

        val result = transaction.execute(listOf(relative)) { false } as
            AttachmentDeletionTransactionResult.MetadataFailed

        assertEquals(1, result.ciphertextFilesStaged)
        assertEquals(AttachmentDeletionRollbackState.COMPLETE, result.rollbackState)
        assertEquals(0, result.tombstoneFilesRetained)
        assertTrue(source.exists())
    }

    @Test
    fun `metadata failure with restore failure retains recoverable tombstone`() = runTest {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val relative = AttachmentRelativePath.original(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
        )
        val source = paths.resolve(relative).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(5))
        }
        val transaction = AttachmentDeletionTransaction(
            storagePaths = paths,
            fileOps = RecordingDeletionFileOps(failRestore = true),
            operationIdFactory = { operationId },
        )

        val result = transaction.execute(listOf(relative)) {
            throw IllegalStateException("simulated metadata failure")
        } as AttachmentDeletionTransactionResult.MetadataFailed

        assertEquals(AttachmentDeletionRollbackState.INCOMPLETE, result.rollbackState)
        assertEquals(1, result.tombstoneFilesRetained)
        assertFalse(source.exists())
        assertTrue(paths.resolve(AttachmentDeletingPath.create(relative, operationId)).exists())
        assertFalse(result.toString().contains(source.path))
        assertFalse(result.toString().contains("simulated metadata failure"))
    }

    @Test
    fun `metadata success with final delete failure leaves tombstone for cold start`() = runTest {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val relative = AttachmentRelativePath.original(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
        )
        val source = paths.resolve(relative).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(6))
        }
        val transaction = AttachmentDeletionTransaction(
            storagePaths = paths,
            fileOps = RecordingDeletionFileOps(failFinalize = true),
            operationIdFactory = { operationId },
        )

        val result = transaction.execute(listOf(relative)) { true } as
            AttachmentDeletionTransactionResult.Committed

        assertEquals(0, result.ciphertextFilesFinalized)
        assertEquals(1, result.tombstoneFilesRetained)
        assertFalse(source.exists())
        assertTrue(paths.resolve(AttachmentDeletingPath.create(relative, operationId)).exists())
    }
}

private class RecordingDeletionFileOps(
    private val failStageCall: Int? = null,
    private val failRestore: Boolean = false,
    private val failFinalize: Boolean = false,
) : AttachmentDeletionFileOps {
    private var stageCalls = 0
    val restoredOriginals = mutableListOf<String>()

    override fun stage(source: File, tombstone: File): Boolean {
        stageCalls += 1
        if (stageCalls == failStageCall) return false
        tombstone.parentFile?.mkdirs()
        Files.move(source.toPath(), tombstone.toPath())
        return true
    }

    override fun restore(tombstone: File, source: File): Boolean {
        if (failRestore) return false
        Files.move(tombstone.toPath(), source.toPath())
        restoredOriginals += AttachmentRelativePath.parseStored(
            requireNotNull(source.parentFile).name + "/" + source.name,
        ).value
        return true
    }

    override fun finalize(tombstone: File): Boolean =
        if (failFinalize) false else Files.deleteIfExists(tombstone.toPath())
}
