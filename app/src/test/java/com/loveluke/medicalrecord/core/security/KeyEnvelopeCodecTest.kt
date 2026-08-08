package com.loveluke.medicalrecord.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class KeyEnvelopeCodecTest {
    private val wrappingKey = SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    private val codec = KeyEnvelopeCodec(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })

    @Test
    fun `database passphrase round trips and decoded secret can be destroyed`() {
        val expected = ByteArray(32) { index -> (index * 3).toByte() }
        val envelope = encode(SecureMaterialPurpose.DATABASE_PASSPHRASE, expected)

        val decoded = codec.decode(
            expectedPurpose = SecureMaterialPurpose.DATABASE_PASSPHRASE,
            encoded = envelope,
            wrappingKey = wrappingKey,
        ) as EnvelopeDecodeResult.Success

        decoded.secret.use { actual -> assertArrayEquals(expected, actual) }
        decoded.secret.close()
        assertThrows(IllegalStateException::class.java) {
            decoded.secret.use { }
        }
    }

    @Test
    fun `tampering fails authentication without returning plaintext`() {
        val envelope = encode(SecureMaterialPurpose.ATTACHMENT_MASTER_KEY, ByteArray(32) { 0x5A })
        envelope[envelope.lastIndex] = (envelope.last() xor 0x01)

        val result = codec.decode(
            expectedPurpose = SecureMaterialPurpose.ATTACHMENT_MASTER_KEY,
            encoded = envelope,
            wrappingKey = wrappingKey,
        )

        assertEquals(
            EnvelopeDecodeResult.Failure(EnvelopeFailureReason.AUTHENTICATION_FAILED),
            result,
        )
    }

    @Test
    fun `an envelope cannot be decoded for a different purpose`() {
        val envelope = encode(SecureMaterialPurpose.DATABASE_PASSPHRASE, ByteArray(32) { 0x33 })

        val result = codec.decode(
            expectedPurpose = SecureMaterialPurpose.ATTACHMENT_MASTER_KEY,
            encoded = envelope,
            wrappingKey = wrappingKey,
        )

        assertEquals(
            EnvelopeDecodeResult.Failure(EnvelopeFailureReason.WRONG_PURPOSE),
            result,
        )
    }

    private infix fun Byte.xor(other: Int): Byte = (toInt() xor other).toByte()

    private fun encode(purpose: SecureMaterialPurpose, bytes: ByteArray): ByteArray {
        val secret = SecretBytes.copyOf(bytes)
        return try {
            codec.encode(purpose, secret, wrappingKey)
        } finally {
            secret.close()
        }
    }
}
