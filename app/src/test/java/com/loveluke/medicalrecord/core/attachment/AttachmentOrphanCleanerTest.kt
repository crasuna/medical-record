package com.loveluke.medicalrecord.core.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.util.UUID

class AttachmentOrphanCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `referenced ciphertext is retained while an unreferenced UUID ciphertext is removed`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val referenced = AttachmentRelativePath.original(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
        )
        val orphan = AttachmentRelativePath.original(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
        )
        paths.resolve(referenced).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        paths.resolve(orphan).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2)) }

        val report = AttachmentOrphanCleaner(paths).clean(setOf(referenced))

        assertTrue(paths.resolve(referenced).exists())
        assertFalse(paths.resolve(orphan).exists())
        assertEquals(1, report.deletedOrphans)
        assertEquals(0, report.failedDeletes)
        assertEquals(1, report.retainedReferenced)
    }

    @Test
    fun `strictly named interrupted ciphertext is removed and counted separately`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val pending = paths.rootDirectory.resolve(
            "original/.33333333-3333-4333-8333-333333333333.mra." +
                "55555555-5555-4555-8555-555555555555.pending",
        )
        pending.parentFile?.mkdirs()
        pending.writeBytes(byteArrayOf(1, 2, 3))
        val unknown = paths.rootDirectory.resolve("original/.not-a-cipher.pending")
        unknown.writeBytes(byteArrayOf(4))

        val report = AttachmentOrphanCleaner(paths).clean(emptySet())

        assertFalse(pending.exists())
        assertTrue(unknown.exists())
        assertEquals(1, report.deletedPendingFiles)
        assertEquals(0, report.deletedOrphans)
        assertEquals(1, report.ignoredUnrecognizedFiles)
    }

    @Test
    fun `delete failure is retained and reported for retry`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val orphan = AttachmentRelativePath.thumbnail(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
        )
        paths.resolve(orphan).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        val cleaner = AttachmentOrphanCleaner(paths, EncryptedFileDeleter { false })

        val report = cleaner.clean(emptySet())

        assertTrue(paths.resolve(orphan).exists())
        assertEquals(1, report.failedDeletes)
        assertEquals(0, report.deletedOrphans)
    }

    @Test
    fun `unchecked scan IO failure becomes diagnostic report instead of escaping`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val orphan = AttachmentRelativePath.original(
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
        )
        paths.resolve(orphan).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        val cleaner = AttachmentOrphanCleaner(
            paths,
            EncryptedFileDeleter { throw UncheckedIOException(IOException("scan failed")) },
        )

        val report = cleaner.clean(emptySet())

        assertTrue(report.scanFailed)
        assertTrue(paths.resolve(orphan).exists())
    }

    @Test
    fun `referenced deleting ciphertext is restored when the original is missing`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val original = AttachmentRelativePath.original(
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
        )
        val deleting = AttachmentDeletingPath.create(
            original,
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
        )
        paths.resolve(deleting).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(8))
        }

        val report = AttachmentOrphanCleaner(paths).clean(setOf(original))

        assertTrue(paths.resolve(original).exists())
        assertFalse(paths.resolve(deleting).exists())
        assertEquals(1, report.restoredDeletingFiles)
        assertEquals(0, report.deletedDeletingFiles)
        assertEquals(0, report.deletingConflicts)
        assertEquals(0, report.failedDeletingOperations)
    }

    @Test
    fun `unreferenced deleting ciphertext is finalized after metadata commit crash`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val original = AttachmentRelativePath.thumbnail(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        )
        val deleting = AttachmentDeletingPath.create(
            original,
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        )
        paths.resolve(deleting).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }

        val report = AttachmentOrphanCleaner(paths).clean(emptySet())

        assertFalse(paths.resolve(deleting).exists())
        assertFalse(paths.resolve(original).exists())
        assertEquals(0, report.restoredDeletingFiles)
        assertEquals(1, report.deletedDeletingFiles)
    }

    @Test
    fun `existing original and deleting ciphertext conflict retains both without overwrite`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val original = AttachmentRelativePath.original(
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        )
        val deleting = AttachmentDeletingPath.create(
            original,
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
        )
        paths.resolve(original).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        paths.resolve(deleting).writeBytes(byteArrayOf(2))

        val report = AttachmentOrphanCleaner(paths).clean(setOf(original))

        assertEquals(listOf(1.toByte()), paths.resolve(original).readBytes().toList())
        assertEquals(listOf(2.toByte()), paths.resolve(deleting).readBytes().toList())
        assertEquals(1, report.deletingConflicts)
        assertEquals(0, report.restoredDeletingFiles)
    }

    @Test
    fun `failed deleting recovery is retained for a later cold start`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val original = AttachmentRelativePath.original(
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        )
        val deleting = AttachmentDeletingPath.create(
            original,
            UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        )
        paths.resolve(deleting).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(3))
        }
        val cleaner = AttachmentOrphanCleaner(
            storagePaths = paths,
            deletionFileOps = object : AttachmentDeletionFileOps {
                override fun stage(source: java.io.File, tombstone: java.io.File): Boolean = false
                override fun restore(tombstone: java.io.File, source: java.io.File): Boolean = false
                override fun finalize(tombstone: java.io.File): Boolean = false
            },
        )

        val report = cleaner.clean(setOf(original))

        assertTrue(paths.resolve(deleting).exists())
        assertFalse(paths.resolve(original).exists())
        assertEquals(1, report.failedDeletingOperations)
    }

    @Test
    fun `illegal deleting name and deleting symlink are never followed or removed`() {
        val paths = AttachmentStoragePaths(temporaryFolder.root)
        val original = AttachmentRelativePath.original(
            UUID.fromString("12121212-1212-4212-8212-121212121212"),
        )
        val illegal = paths.rootDirectory.resolve(
            "original/.not-a-uuid.mra.34343434-3434-4434-8434-343434343434.deleting",
        )
        illegal.parentFile?.mkdirs()
        illegal.writeBytes(byteArrayOf(4))
        val outside = temporaryFolder.newFile("outside-ciphertext").apply {
            writeBytes(byteArrayOf(5))
        }
        val symlink = paths.resolve(
            AttachmentDeletingPath.create(
                original,
                UUID.fromString("56565656-5656-4656-8656-565656565656"),
            ),
        )
        try {
            Files.createSymbolicLink(symlink.toPath(), outside.toPath())
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: IOException) {
            return
        } catch (_: SecurityException) {
            return
        }

        val report = AttachmentOrphanCleaner(paths).clean(setOf(original))

        assertTrue(illegal.exists())
        assertTrue(Files.isSymbolicLink(symlink.toPath()))
        assertEquals(listOf(5.toByte()), outside.readBytes().toList())
        assertEquals(2, report.ignoredUnrecognizedFiles)
        assertEquals(0, report.restoredDeletingFiles)
        assertEquals(0, report.deletedDeletingFiles)
    }
}
