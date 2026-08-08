package com.loveluke.medicalrecord.core.attachment

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

class AttachmentContentValidatorTest {
    @Test
    fun `declared MIME must match content magic`() {
        val inspection = inspect("%PDF-1.7\n1 0 obj\n%%EOF\n".encodeToByteArray())

        val result = AttachmentContentValidator.validate("image/png", inspection)

        assertEquals(
            AttachmentValidationResult.Rejected(AttachmentValidationFailure.MIME_MAGIC_MISMATCH),
            result,
        )
    }

    @Test
    fun `supported PDF requires a header and EOF marker`() {
        val valid = inspect("%PDF-1.7\n1 0 obj\n%%EOF\n".encodeToByteArray())
        val truncated = inspect("%PDF-1.7\n1 0 obj\n".encodeToByteArray())

        assertEquals(
            AttachmentValidationResult.Accepted(AttachmentMediaType.PDF),
            AttachmentContentValidator.validate("application/pdf", valid),
        )
        assertEquals(
            AttachmentValidationResult.Rejected(AttachmentValidationFailure.MALFORMED_CONTENT),
            AttachmentContentValidator.validate("application/pdf", truncated),
        )
    }

    @Test
    fun `unsupported declared MIME is rejected even when bytes resemble an allowed file`() {
        val inspection = inspect("%PDF-1.7\n%%EOF".encodeToByteArray())

        assertEquals(
            AttachmentValidationResult.Rejected(AttachmentValidationFailure.UNSUPPORTED_MIME),
            AttachmentContentValidator.validate("application/octet-stream", inspection),
        )
    }

    @Test
    fun `allowed image containers pass bounded magic and structure validation`() {
        val samples = listOf(
            Triple("image/jpeg", validJpeg(), AttachmentMediaType.JPEG),
            Triple("image/png", validPng(), AttachmentMediaType.PNG),
            Triple("image/webp", validWebp(), AttachmentMediaType.WEBP),
            Triple("image/heic", validIsoBmff("heic"), AttachmentMediaType.HEIC),
            Triple("image/heif", validIsoBmff("mif1"), AttachmentMediaType.HEIF),
        )

        samples.forEach { (mimeType, bytes, expectedType) ->
            assertEquals(
                AttachmentValidationResult.Accepted(expectedType),
                AttachmentContentValidator.validate(mimeType, inspect(bytes)),
            )
        }
    }

    private fun inspect(bytes: ByteArray): AttachmentContentInspection {
        val input = InspectingInputStream(ByteArrayInputStream(bytes))
        input.use { copyWithLimit(it, OutputStream.nullOutputStream(), MAX_ATTACHMENT_BYTES) }
        return input.snapshot()
    }

    private fun validJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(),
        0x00, 0x11, 0x08,
        0x00, 0x01,
        0x00, 0x01,
        0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
        0xFF.toByte(), 0xD9.toByte(),
    )

    private fun validPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00,
        0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0x00, 0x00, 0x00, 0x00,
        0x49, 0x45, 0x4E, 0x44,
        0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    private fun validWebp(): ByteArray = byteArrayOf(
        0x52, 0x49, 0x46, 0x46,
        0x08, 0x00, 0x00, 0x00,
        0x57, 0x45, 0x42, 0x50,
        0x56, 0x50, 0x38, 0x58,
    )

    private fun validIsoBmff(majorBrand: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(20)
                output.writeBytes("ftyp")
                output.writeBytes(majorBrand)
                output.writeInt(0)
                output.writeBytes("mif1")
            }
            bytes.toByteArray()
        }
}
