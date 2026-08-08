package com.loveluke.medicalrecord.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SecureMaterialPurpose(
    internal val wireId: Int,
    internal val envelopeFileName: String,
    val materialSizeBytes: Int = 32,
) {
    DATABASE_PASSPHRASE(1, "database-passphrase.v1.envelope"),
    ATTACHMENT_MASTER_KEY(2, "attachment-master-key.v1.envelope"),
}

enum class EnvelopeFailureReason {
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    WRONG_PURPOSE,
    AUTHENTICATION_FAILED,
}

sealed interface EnvelopeDecodeResult {
    data class Success(val secret: SecretBytes) : EnvelopeDecodeResult
    data class Failure(val reason: EnvelopeFailureReason) : EnvelopeDecodeResult
}

/** A compact authenticated envelope. No secret metadata or user content is encoded. */
class KeyEnvelopeCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encode(
        purpose: SecureMaterialPurpose,
        material: SecretBytes,
        wrappingKey: SecretKey,
    ): ByteArray {
        require(material.size == purpose.materialSizeBytes) { "Unexpected secret material size." }
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(TAG_SIZE_BITS, nonce))
            updateAAD(aadFor(purpose))
        }
        val encrypted = material.use { cipher.doFinal(it) }

        return ByteArrayOutputStream(HEADER_SIZE_BYTES + nonce.size + encrypted.size).use { output ->
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeByte(FORMAT_VERSION)
                data.writeByte(purpose.wireId)
                data.writeByte(nonce.size)
                data.writeByte(0)
                data.writeInt(encrypted.size)
                data.write(nonce)
                data.write(encrypted)
            }
            output.toByteArray()
        }
    }

    fun decode(
        expectedPurpose: SecureMaterialPurpose,
        encoded: ByteArray,
        wrappingKey: SecretKey,
    ): EnvelopeDecodeResult {
        if (encoded.size < HEADER_SIZE_BYTES + NONCE_SIZE_BYTES + TAG_SIZE_BYTES) {
            return EnvelopeDecodeResult.Failure(EnvelopeFailureReason.INVALID_FORMAT)
        }

        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) {
                    return EnvelopeDecodeResult.Failure(EnvelopeFailureReason.INVALID_FORMAT)
                }
                val version = input.readUnsignedByte()
                if (version != FORMAT_VERSION) {
                    return EnvelopeDecodeResult.Failure(EnvelopeFailureReason.UNSUPPORTED_VERSION)
                }
                val purposeId = input.readUnsignedByte()
                if (purposeId != expectedPurpose.wireId) {
                    return EnvelopeDecodeResult.Failure(EnvelopeFailureReason.WRONG_PURPOSE)
                }
                val nonceLength = input.readUnsignedByte()
                input.readUnsignedByte() // reserved
                val encryptedLength = input.readInt()
                if (
                    nonceLength != NONCE_SIZE_BYTES ||
                    encryptedLength != expectedPurpose.materialSizeBytes + TAG_SIZE_BYTES ||
                    encoded.size != HEADER_SIZE_BYTES + nonceLength + encryptedLength
                ) {
                    return EnvelopeDecodeResult.Failure(EnvelopeFailureReason.INVALID_FORMAT)
                }

                val nonce = ByteArray(nonceLength).also(input::readFully)
                val encrypted = ByteArray(encryptedLength).also(input::readFully)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(TAG_SIZE_BITS, nonce))
                    updateAAD(aadFor(expectedPurpose))
                }
                val plaintext = cipher.doFinal(encrypted)
                if (plaintext.size != expectedPurpose.materialSizeBytes) {
                    plaintext.fill(0)
                    EnvelopeDecodeResult.Failure(EnvelopeFailureReason.INVALID_FORMAT)
                } else {
                    EnvelopeDecodeResult.Success(SecretBytes.takeOwnership(plaintext))
                }
            }
        } catch (_: AEADBadTagException) {
            EnvelopeDecodeResult.Failure(EnvelopeFailureReason.AUTHENTICATION_FAILED)
        } catch (_: GeneralSecurityException) {
            EnvelopeDecodeResult.Failure(EnvelopeFailureReason.AUTHENTICATION_FAILED)
        } catch (_: RuntimeException) {
            EnvelopeDecodeResult.Failure(EnvelopeFailureReason.INVALID_FORMAT)
        }
    }

    private fun aadFor(purpose: SecureMaterialPurpose): ByteArray =
        "$AAD_DOMAIN:${purpose.wireId}:$FORMAT_VERSION".encodeToByteArray()

    private companion object {
        val MAGIC = byteArrayOf(0x4D, 0x52, 0x4B, 0x45) // MRKE
        const val FORMAT_VERSION = 1
        const val NONCE_SIZE_BYTES = 12
        const val TAG_SIZE_BYTES = 16
        const val TAG_SIZE_BITS = TAG_SIZE_BYTES * 8
        const val HEADER_SIZE_BYTES = 12
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AAD_DOMAIN = "medical-record/key-envelope"
    }
}
