package com.loveluke.medicalrecord.core.attachment

import com.loveluke.medicalrecord.core.security.SecretBytes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val MAX_ATTACHMENT_BYTES: Long = 50L * 1024L * 1024L

data class AttachmentIdentity(
    val patientId: UUID,
    val encounterId: UUID,
    val attachmentId: UUID,
)

enum class AttachmentPayloadKind(internal val wireId: Int) {
    ORIGINAL(1),
    THUMBNAIL(2),
}

enum class AttachmentEncryptionFailure {
    TOO_LARGE,
    DESTINATION_EXISTS,
    CRYPTOGRAPHY_UNAVAILABLE,
    IO_FAILURE,
}

sealed interface AttachmentEncryptionResult {
    data class Success(val plaintextBytes: Long) : AttachmentEncryptionResult
    data class Failure(val reason: AttachmentEncryptionFailure) : AttachmentEncryptionResult
}

enum class AttachmentQuarantineReason {
    AUTHENTICATION_FAILED,
    TRUNCATED,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
}

enum class AttachmentReadFailure {
    DESTINATION_EXISTS,
    IO_FAILURE,
    CRYPTOGRAPHY_UNAVAILABLE,
}

sealed interface AttachmentDecryptionResult {
    data class Success(val plaintextBytes: Long) : AttachmentDecryptionResult
    data class Quarantined(val reason: AttachmentQuarantineReason) : AttachmentDecryptionResult
    data class Failure(val reason: AttachmentReadFailure) : AttachmentDecryptionResult
}

/**
 * Version 1 encrypted attachment container.
 *
 * Each file receives an independent random AES-256 data key. That key and the payload use separate
 * 96-bit nonces. Patient, encounter, attachment, payload kind, and format version are authenticated
 * as AAD and are deliberately not written into the file header.
 */
class AttachmentCipherContainer(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encrypt(
        source: InputStream,
        destination: File,
        masterKey: SecretBytes,
        identity: AttachmentIdentity,
        payloadKind: AttachmentPayloadKind,
        maximumPlaintextBytes: Long = MAX_ATTACHMENT_BYTES,
    ): AttachmentEncryptionResult {
        require(maximumPlaintextBytes >= 0) { "Attachment limit must be non-negative." }
        if (Files.isSymbolicLink(destination.toPath())) {
            return AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.IO_FAILURE)
        }
        if (destination.exists()) {
            return AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.DESTINATION_EXISTS)
        }
        val parent = destination.parentFile
            ?: return AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.IO_FAILURE)
        if (!parent.mkdirs() && !parent.isDirectory) {
            return AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.IO_FAILURE)
        }
        val pending = File(parent, ".${destination.name}.${UUID.randomUUID()}.pending")
        val dataKeyBytes = ByteArray(KEY_SIZE_BYTES).also(secureRandom::nextBytes)

        return try {
            val keyNonce = ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
            val contentNonce = ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes)
            val wrappedDataKey = wrapDataKey(
                dataKeyBytes = dataKeyBytes,
                keyNonce = keyNonce,
                masterKey = masterKey,
                identity = identity,
                payloadKind = payloadKind,
            )
            writeHeader(
                destination = pending,
                payloadKind = payloadKind,
                keyNonce = keyNonce,
                contentNonce = contentNonce,
                wrappedDataKey = wrappedDataKey,
            )

            val dataKey = SecretKeySpec(dataKeyBytes, "AES")
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(TAG_SIZE_BITS, contentNonce))
                updateAAD(aad(identity, payloadKind, AAD_CONTENT_DOMAIN))
            }
            val plaintextBytes = FileOutputStream(pending, true).use { fileOutput ->
                BufferedOutputStream(fileOutput).use { bufferedOutput ->
                    CipherOutputStream(bufferedOutput, cipher).use { encryptedOutput ->
                        copyWithLimit(source, encryptedOutput, maximumPlaintextBytes)
                    }
                }
            }
            val ciphertextBytes = pending.length() - HEADER_SIZE_BYTES
            patchLengths(pending, plaintextBytes, ciphertextBytes)
            moveAtomically(pending, destination)
            AttachmentEncryptionResult.Success(plaintextBytes)
        } catch (_: AttachmentSizeLimitExceededException) {
            AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.TOO_LARGE)
        } catch (_: GeneralSecurityException) {
            AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.CRYPTOGRAPHY_UNAVAILABLE)
        } catch (_: IOException) {
            AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.IO_FAILURE)
        } catch (_: RuntimeException) {
            AttachmentEncryptionResult.Failure(AttachmentEncryptionFailure.IO_FAILURE)
        } finally {
            dataKeyBytes.fill(0)
            pending.delete()
        }
    }

    fun decrypt(
        source: File,
        destination: File,
        masterKey: SecretBytes,
        identity: AttachmentIdentity,
        payloadKind: AttachmentPayloadKind,
    ): AttachmentDecryptionResult {
        if (
            Files.isSymbolicLink(source.toPath()) ||
            Files.isSymbolicLink(destination.toPath())
        ) {
            return AttachmentDecryptionResult.Failure(AttachmentReadFailure.IO_FAILURE)
        }
        if (destination.exists()) {
            return AttachmentDecryptionResult.Failure(AttachmentReadFailure.DESTINATION_EXISTS)
        }
        val parent = destination.parentFile
            ?: return AttachmentDecryptionResult.Failure(AttachmentReadFailure.IO_FAILURE)
        if (!parent.mkdirs() && !parent.isDirectory) {
            return AttachmentDecryptionResult.Failure(AttachmentReadFailure.IO_FAILURE)
        }
        val pending = File(parent, ".${destination.name}.${UUID.randomUUID()}.pending")

        val header = try {
            readHeader(source, payloadKind)
        } catch (_: TruncatedContainerException) {
            return AttachmentDecryptionResult.Quarantined(AttachmentQuarantineReason.TRUNCATED)
        } catch (_: UnsupportedContainerVersionException) {
            return AttachmentDecryptionResult.Quarantined(AttachmentQuarantineReason.UNSUPPORTED_VERSION)
        } catch (_: InvalidContainerException) {
            return AttachmentDecryptionResult.Quarantined(AttachmentQuarantineReason.INVALID_FORMAT)
        } catch (_: IOException) {
            return AttachmentDecryptionResult.Failure(AttachmentReadFailure.IO_FAILURE)
        }

        var dataKeyBytes: ByteArray? = null
        return try {
            dataKeyBytes = unwrapDataKey(
                wrappedDataKey = header.wrappedDataKey,
                keyNonce = header.keyNonce,
                masterKey = masterKey,
                identity = identity,
                payloadKind = payloadKind,
            )
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(dataKeyBytes, "AES"),
                    GCMParameterSpec(TAG_SIZE_BITS, header.contentNonce),
                )
                updateAAD(aad(identity, payloadKind, AAD_CONTENT_DOMAIN))
            }

            val plaintextBytes = FileInputStream(source).use { fileInput ->
                skipExactly(fileInput, HEADER_SIZE_BYTES)
                CipherInputStream(BufferedInputStream(fileInput), cipher).use { decryptedInput ->
                    FileOutputStream(pending).use { fileOutput ->
                        BufferedOutputStream(fileOutput).use { plaintextOutput ->
                            copyWithLimit(decryptedInput, plaintextOutput, header.plaintextBytes)
                        }
                    }
                }
            }
            if (plaintextBytes != header.plaintextBytes) {
                return AttachmentDecryptionResult.Quarantined(AttachmentQuarantineReason.INVALID_FORMAT)
            }
            moveAtomically(pending, destination)
            AttachmentDecryptionResult.Success(plaintextBytes)
        } catch (error: Throwable) {
            when {
                error.isAuthenticationFailure() -> AttachmentDecryptionResult.Quarantined(
                    AttachmentQuarantineReason.AUTHENTICATION_FAILED,
                )
                error is AttachmentSizeLimitExceededException -> AttachmentDecryptionResult.Quarantined(
                    AttachmentQuarantineReason.INVALID_FORMAT,
                )
                error is GeneralSecurityException -> AttachmentDecryptionResult.Failure(
                    AttachmentReadFailure.CRYPTOGRAPHY_UNAVAILABLE,
                )
                else -> AttachmentDecryptionResult.Failure(AttachmentReadFailure.IO_FAILURE)
            }
        } finally {
            dataKeyBytes?.fill(0)
            pending.delete()
        }
    }

    private fun wrapDataKey(
        dataKeyBytes: ByteArray,
        keyNonce: ByteArray,
        masterKey: SecretBytes,
        identity: AttachmentIdentity,
        payloadKind: AttachmentPayloadKind,
    ): ByteArray = masterKey.use { masterBytes ->
        Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(masterBytes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, keyNonce),
            )
            updateAAD(aad(identity, payloadKind, AAD_KEY_DOMAIN))
            doFinal(dataKeyBytes)
        }
    }

    private fun unwrapDataKey(
        wrappedDataKey: ByteArray,
        keyNonce: ByteArray,
        masterKey: SecretBytes,
        identity: AttachmentIdentity,
        payloadKind: AttachmentPayloadKind,
    ): ByteArray = masterKey.use { masterBytes ->
        Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(masterBytes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, keyNonce),
            )
            updateAAD(aad(identity, payloadKind, AAD_KEY_DOMAIN))
            doFinal(wrappedDataKey)
        }
    }

    private fun writeHeader(
        destination: File,
        payloadKind: AttachmentPayloadKind,
        keyNonce: ByteArray,
        contentNonce: ByteArray,
        wrappedDataKey: ByteArray,
    ) {
        require(wrappedDataKey.size == WRAPPED_KEY_SIZE_BYTES)
        DataOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->
            output.write(MAGIC)
            output.writeByte(FORMAT_VERSION)
            output.writeByte(payloadKind.wireId)
            output.writeByte(keyNonce.size)
            output.writeByte(contentNonce.size)
            output.writeShort(wrappedDataKey.size)
            output.writeShort(0)
            output.writeLong(0L)
            output.writeLong(0L)
            output.write(keyNonce)
            output.write(contentNonce)
            output.write(wrappedDataKey)
        }
        check(destination.length() == HEADER_SIZE_BYTES)
    }

    private fun patchLengths(file: File, plaintextBytes: Long, ciphertextBytes: Long) {
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.seek(PLAINTEXT_LENGTH_OFFSET)
            randomAccess.writeLong(plaintextBytes)
            randomAccess.writeLong(ciphertextBytes)
            randomAccess.fd.sync()
        }
    }

    private fun readHeader(source: File, expectedKind: AttachmentPayloadKind): Header {
        if (!source.isFile || source.length() < HEADER_SIZE_BYTES + TAG_SIZE_BYTES) {
            throw TruncatedContainerException()
        }
        return DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC)) throw InvalidContainerException()
            val version = input.readUnsignedByte()
            if (version != FORMAT_VERSION) throw UnsupportedContainerVersionException()
            if (input.readUnsignedByte() != expectedKind.wireId) throw InvalidContainerException()
            val keyNonceLength = input.readUnsignedByte()
            val contentNonceLength = input.readUnsignedByte()
            val wrappedKeyLength = input.readUnsignedShort()
            input.readUnsignedShort()
            val plaintextBytes = input.readLong()
            val ciphertextBytes = input.readLong()
            if (
                keyNonceLength != NONCE_SIZE_BYTES ||
                contentNonceLength != NONCE_SIZE_BYTES ||
                wrappedKeyLength != WRAPPED_KEY_SIZE_BYTES ||
                plaintextBytes !in 0..MAX_ATTACHMENT_BYTES ||
                ciphertextBytes < TAG_SIZE_BYTES
            ) {
                throw InvalidContainerException()
            }
            val expectedFileLength = HEADER_SIZE_BYTES + ciphertextBytes
            if (source.length() < expectedFileLength) throw TruncatedContainerException()
            if (source.length() != expectedFileLength) throw InvalidContainerException()
            Header(
                plaintextBytes = plaintextBytes,
                keyNonce = ByteArray(keyNonceLength).also(input::readFully),
                contentNonce = ByteArray(contentNonceLength).also(input::readFully),
                wrappedDataKey = ByteArray(wrappedKeyLength).also(input::readFully),
            )
        }
    }

    private fun aad(
        identity: AttachmentIdentity,
        payloadKind: AttachmentPayloadKind,
        domain: String,
    ): ByteArray = buildString {
        append(domain)
        append(':')
        append(FORMAT_VERSION)
        append(':')
        append(payloadKind.wireId)
        append(':')
        append(identity.patientId)
        append(':')
        append(identity.encounterId)
        append(':')
        append(identity.attachmentId)
    }.encodeToByteArray()

    private data class Header(
        val plaintextBytes: Long,
        val keyNonce: ByteArray,
        val contentNonce: ByteArray,
        val wrappedDataKey: ByteArray,
    )

    private class InvalidContainerException : IOException()
    private class TruncatedContainerException : EOFException()
    private class UnsupportedContainerVersionException : IOException()

    private companion object {
        val MAGIC = "MRATTACH".encodeToByteArray()
        const val FORMAT_VERSION = 1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BYTES = 32
        const val NONCE_SIZE_BYTES = 12
        const val TAG_SIZE_BYTES = 16
        const val TAG_SIZE_BITS = TAG_SIZE_BYTES * 8
        const val WRAPPED_KEY_SIZE_BYTES = KEY_SIZE_BYTES + TAG_SIZE_BYTES
        const val HEADER_SIZE_BYTES = 104L
        const val PLAINTEXT_LENGTH_OFFSET = 16L
        const val AAD_KEY_DOMAIN = "medical-record/attachment-key"
        const val AAD_CONTENT_DOMAIN = "medical-record/attachment-content"
    }
}

internal class AttachmentSizeLimitExceededException : IOException()

internal fun copyWithLimit(
    source: InputStream,
    destination: java.io.OutputStream,
    maximumBytes: Long,
): Long {
    require(maximumBytes >= 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (total > maximumBytes - read) throw AttachmentSizeLimitExceededException()
        destination.write(buffer, 0, read)
        total += read
    }
    return total
}

private fun skipExactly(input: InputStream, bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (input.read() >= 0) {
            remaining -= 1
        } else {
            throw EOFException()
        }
    }
}

private fun moveAtomically(source: File, destination: File) {
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath())
    }
}

private fun Throwable.isAuthenticationFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is AEADBadTagException) return true
        current = current.cause
    }
    return false
}
