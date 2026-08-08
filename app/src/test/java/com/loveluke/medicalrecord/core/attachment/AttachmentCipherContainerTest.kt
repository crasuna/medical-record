package com.loveluke.medicalrecord.core.attachment

import com.loveluke.medicalrecord.core.security.SecretBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.UUID

class AttachmentCipherContainerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val identity = AttachmentIdentity(
        patientId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
        encounterId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
        attachmentId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
    )
    private val masterKey = SecretBytes.copyOf(ByteArray(32) { index -> (index + 7).toByte() })
    private val container = AttachmentCipherContainer()

    @Test
    fun `encrypted attachment round trips without a plaintext side file`() {
        val plaintext = "synthetic attachment bytes".encodeToByteArray()
        val encrypted = temporaryFolder.newFile("attachment.mra").apply { delete() }
        val decrypted = temporaryFolder.newFile("preview.bin").apply { delete() }

        val encryptedResult = container.encrypt(
            source = ByteArrayInputStream(plaintext),
            destination = encrypted,
            masterKey = masterKey,
            identity = identity,
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )
        val decryptedResult = container.decrypt(
            source = encrypted,
            destination = decrypted,
            masterKey = masterKey,
            identity = identity,
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )

        assertEquals(AttachmentEncryptionResult.Success(plaintext.size.toLong()), encryptedResult)
        assertEquals(AttachmentDecryptionResult.Success(plaintext.size.toLong()), decryptedResult)
        assertArrayEquals(plaintext, decrypted.readBytes())
    }

    @Test
    fun `tampering quarantines only the selected attachment`() {
        val encrypted = temporaryFolder.newFile("tampered.mra").apply { delete() }
        val destination = temporaryFolder.newFile("tampered-preview.bin").apply { delete() }
        container.encrypt(
            source = ByteArrayInputStream(ByteArray(512) { 0x2A }),
            destination = encrypted,
            masterKey = masterKey,
            identity = identity,
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )
        encrypted.writeBytes(encrypted.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        })

        val result = container.decrypt(
            source = encrypted,
            destination = destination,
            masterKey = masterKey,
            identity = identity,
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )

        assertEquals(
            AttachmentDecryptionResult.Quarantined(AttachmentQuarantineReason.AUTHENTICATION_FAILED),
            result,
        )
        assertTrue(!destination.exists())
    }

    @Test
    fun `wrong patient encounter or attachment AAD is rejected`() {
        val encrypted = temporaryFolder.newFile("wrong-aad.mra").apply { delete() }
        val destination = temporaryFolder.newFile("wrong-aad-preview.bin").apply { delete() }
        container.encrypt(
            source = ByteArrayInputStream(ByteArray(64) { 0x19 }),
            destination = encrypted,
            masterKey = masterKey,
            identity = identity,
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )

        val result = container.decrypt(
            source = encrypted,
            destination = destination,
            masterKey = masterKey,
            identity = identity.copy(encounterId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")),
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )

        assertEquals(
            AttachmentDecryptionResult.Quarantined(AttachmentQuarantineReason.AUTHENTICATION_FAILED),
            result,
        )
        assertTrue(!destination.exists())
    }

    @Test
    fun `truncated container is rejected before plaintext is committed`() {
        val encrypted = temporaryFolder.newFile("truncated.mra").apply { delete() }
        val destination = temporaryFolder.newFile("truncated-preview.bin").apply { delete() }
        container.encrypt(
            source = ByteArrayInputStream(ByteArray(128) { 0x44 }),
            destination = encrypted,
            masterKey = masterKey,
            identity = identity,
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )
        encrypted.writeBytes(encrypted.readBytes().dropLast(5).toByteArray())

        val result = container.decrypt(
            source = encrypted,
            destination = destination,
            masterKey = masterKey,
            identity = identity,
            payloadKind = AttachmentPayloadKind.ORIGINAL,
        )

        assertEquals(
            AttachmentDecryptionResult.Quarantined(AttachmentQuarantineReason.TRUNCATED),
            result,
        )
        assertTrue(!destination.exists())
    }
}
