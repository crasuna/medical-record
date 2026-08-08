package com.loveluke.medicalrecord.core.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class AttachmentStoragePathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val paths by lazy { AttachmentStoragePaths(temporaryFolder.root) }

    @Test
    fun `generated storage path is relative and contains only the attachment UUID`() {
        val id = UUID.fromString("33333333-3333-4333-8333-333333333333")

        val relative = AttachmentRelativePath.original(id)
        val resolved = paths.resolve(relative)

        assertEquals("original/33333333-3333-4333-8333-333333333333.mra", relative.value)
        assertFalse(relative.value.contains(temporaryFolder.root.absolutePath))
        assertTrue(resolved.canonicalPath.startsWith(paths.rootDirectory.canonicalPath))
    }

    @Test
    fun `stored path parser rejects traversal absolute paths and non UUID names`() {
        val rejected = listOf(
            "../outside.mra",
            "original/../../outside.mra",
            "C:\\outside.mra",
            "/outside.mra",
            "original/not-a-uuid.mra",
            "original/33333333-3333-4333-8333-333333333333.pdf",
        )

        rejected.forEach { raw ->
            assertThrows(UnsafeAttachmentPathException::class.java) {
                AttachmentRelativePath.parseStored(raw)
            }
        }
    }

    @Test
    fun `stored path accepts canonical UUIDs without constraining UUID version`() {
        val path = AttachmentRelativePath.parseStored(
            "original/00000000-0000-0000-0000-000000000000.mra",
        )

        assertEquals("original/00000000-0000-0000-0000-000000000000.mra", path.value)
    }

    @Test
    fun `deleting path round trips to its exact original without exposing arbitrary paths`() {
        val original = AttachmentRelativePath.thumbnail(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
        )
        val operationId = UUID.fromString("55555555-5555-4555-8555-555555555555")

        val deleting = AttachmentDeletingPath.create(original, operationId)
        val parsed = AttachmentDeletingPath.parseStored(deleting.value)

        assertEquals(
            "thumbnail/.44444444-4444-4444-8444-444444444444.mrt." +
                "55555555-5555-4555-8555-555555555555.deleting",
            deleting.value,
        )
        assertEquals(original.value, parsed.originalPath.value)
        assertEquals(operationId, parsed.operationId)
        assertTrue(paths.resolve(deleting).canonicalPath.startsWith(paths.rootDirectory.canonicalPath))
    }

    @Test
    fun `deleting path parser rejects traversal mismatched extensions and malformed UUIDs`() {
        val rejected = listOf(
            "../original/.44444444-4444-4444-8444-444444444444.mra." +
                "55555555-5555-4555-8555-555555555555.deleting",
            "original/.44444444-4444-4444-8444-444444444444.mrt." +
                "55555555-5555-4555-8555-555555555555.deleting",
            "original/.not-a-uuid.mra.55555555-5555-4555-8555-555555555555.deleting",
            "original/.44444444-4444-4444-8444-444444444444.mra.not-a-uuid.deleting",
            "original/.44444444-4444-4444-8444-444444444444.mra." +
                "55555555-5555-4555-8555-555555555555.deleting/extra",
        )

        rejected.forEach { raw ->
            assertThrows(UnsafeAttachmentPathException::class.java) {
                AttachmentDeletingPath.parseStored(raw)
            }
        }
    }
}
