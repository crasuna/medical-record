package com.loveluke.medicalrecord.core.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKey

@JvmInline
value class InstallationNamespace(val value: String) {
    init {
        require(NAMESPACE_PATTERN.matches(value)) { "Invalid installation namespace." }
    }

    val wrappingKeyAlias: String
        get() = "$value.medical-record.wrapping.v1"

    private companion object {
        val NAMESPACE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]{2,199}")
    }
}

interface WrappingKeyProvider {
    /** Returns null when the alias does not exist. It must never create a key. */
    fun getExisting(alias: String): SecretKey?

    /** Creates a non-exportable AES-256 key when and only when provisioning a blank install. */
    fun create(alias: String): SecretKey

    fun delete(alias: String): Boolean
}

enum class SecureMaterialFailure {
    ENVELOPE_MISSING_WITH_DATA,
    ENVELOPE_INVALID,
    ENVELOPE_UNSUPPORTED,
    ENVELOPE_AUTHENTICATION_FAILED,
    ENVELOPE_IO_FAILURE,
    WRAPPING_KEY_MISSING,
    WRAPPING_KEY_UNAVAILABLE,
    PROVISIONING_FAILED,
    SQLCIPHER_LIBRARY_UNAVAILABLE,
    SQLCIPHER_FACTORY_CREATION_FAILED,
    SECURE_MATERIAL_RESOLUTION_FAILED,
}

sealed interface SecureMaterialResolution {
    data class Provisioned(val secret: SecretBytes) : SecureMaterialResolution
    data class Available(val secret: SecretBytes) : SecureMaterialResolution
    data class FailClosed(val reason: SecureMaterialFailure) : SecureMaterialResolution
}

/**
 * Resolves one of the two installation secrets without conflating a blank install with data loss.
 *
 * Existing data or an existing envelope always takes the fail-closed path when key recovery fails.
 */
class SecureMaterialStore(
    noBackupFilesDir: File,
    private val installationNamespace: InstallationNamespace,
    private val wrappingKeyProvider: WrappingKeyProvider,
    private val envelopeCodec: KeyEnvelopeCodec = KeyEnvelopeCodec(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val envelopeDirectory = File(
        noBackupFilesDir,
        "medical-record-security/${installationNamespace.value}",
    )

    @Synchronized
    fun resolve(
        purpose: SecureMaterialPurpose,
        sensitiveDataExists: Boolean,
    ): SecureMaterialResolution = resolve(purpose) { sensitiveDataExists }

    /**
     * Defers potentially expensive local-data discovery until an envelope is actually missing.
     * Existing installations therefore open their authenticated envelope without walking the
     * database or attachment trees on every process start.
     */
    @Synchronized
    fun resolve(
        purpose: SecureMaterialPurpose,
        sensitiveDataExists: () -> Boolean,
    ): SecureMaterialResolution {
        val envelope = envelopeFile(purpose)
        if (envelope.exists()) {
            return openExistingEnvelope(purpose, envelope)
        }
        if (sensitiveDataExists()) {
            return SecureMaterialResolution.FailClosed(SecureMaterialFailure.ENVELOPE_MISSING_WITH_DATA)
        }
        return provisionBlankInstallation(purpose, envelope)
    }

    fun envelopeFile(purpose: SecureMaterialPurpose): File = File(
        envelopeDirectory,
        "${installationNamespace.value}.${purpose.envelopeFileName}",
    )

    fun envelopeDirectory(): File = envelopeDirectory

    private fun openExistingEnvelope(
        purpose: SecureMaterialPurpose,
        envelope: File,
    ): SecureMaterialResolution {
        val wrappingKey = try {
            wrappingKeyProvider.getExisting(installationNamespace.wrappingKeyAlias)
        } catch (_: GeneralSecurityException) {
            return SecureMaterialResolution.FailClosed(SecureMaterialFailure.WRAPPING_KEY_UNAVAILABLE)
        } catch (_: RuntimeException) {
            return SecureMaterialResolution.FailClosed(SecureMaterialFailure.WRAPPING_KEY_UNAVAILABLE)
        } ?: return SecureMaterialResolution.FailClosed(SecureMaterialFailure.WRAPPING_KEY_MISSING)

        val encoded = try {
            if (!envelope.isFile || envelope.length() !in 1..MAX_ENVELOPE_BYTES) {
                return SecureMaterialResolution.FailClosed(SecureMaterialFailure.ENVELOPE_INVALID)
            }
            envelope.readBytes()
        } catch (_: IOException) {
            return SecureMaterialResolution.FailClosed(SecureMaterialFailure.ENVELOPE_IO_FAILURE)
        } catch (_: SecurityException) {
            return SecureMaterialResolution.FailClosed(SecureMaterialFailure.ENVELOPE_IO_FAILURE)
        }

        return when (val decoded = envelopeCodec.decode(purpose, encoded, wrappingKey)) {
            is EnvelopeDecodeResult.Success -> SecureMaterialResolution.Available(decoded.secret)
            is EnvelopeDecodeResult.Failure -> SecureMaterialResolution.FailClosed(
                when (decoded.reason) {
                    EnvelopeFailureReason.AUTHENTICATION_FAILED -> SecureMaterialFailure.ENVELOPE_AUTHENTICATION_FAILED
                    EnvelopeFailureReason.UNSUPPORTED_VERSION -> SecureMaterialFailure.ENVELOPE_UNSUPPORTED
                    EnvelopeFailureReason.INVALID_FORMAT,
                    EnvelopeFailureReason.WRONG_PURPOSE,
                    -> SecureMaterialFailure.ENVELOPE_INVALID
                },
            )
        }
    }

    private fun provisionBlankInstallation(
        purpose: SecureMaterialPurpose,
        envelope: File,
    ): SecureMaterialResolution {
        val wrappingKey = try {
            wrappingKeyProvider.getExisting(installationNamespace.wrappingKeyAlias)
                ?: wrappingKeyProvider.create(installationNamespace.wrappingKeyAlias)
        } catch (_: GeneralSecurityException) {
            return SecureMaterialResolution.FailClosed(SecureMaterialFailure.PROVISIONING_FAILED)
        } catch (_: RuntimeException) {
            return SecureMaterialResolution.FailClosed(SecureMaterialFailure.PROVISIONING_FAILED)
        }

        val material = ByteArray(purpose.materialSizeBytes).also(secureRandom::nextBytes)
        val ownedMaterial = SecretBytes.takeOwnership(material)
        return try {
            val encoded = envelopeCodec.encode(purpose, ownedMaterial, wrappingKey)
            writeEnvelopeAtomically(envelope, encoded)
            SecureMaterialResolution.Provisioned(ownedMaterial)
        } catch (_: IOException) {
            ownedMaterial.close()
            SecureMaterialResolution.FailClosed(SecureMaterialFailure.PROVISIONING_FAILED)
        } catch (_: GeneralSecurityException) {
            ownedMaterial.close()
            SecureMaterialResolution.FailClosed(SecureMaterialFailure.PROVISIONING_FAILED)
        } catch (_: RuntimeException) {
            ownedMaterial.close()
            SecureMaterialResolution.FailClosed(SecureMaterialFailure.PROVISIONING_FAILED)
        }
    }

    private fun writeEnvelopeAtomically(target: File, encoded: ByteArray) {
        require(!target.exists()) { "Refusing to replace an existing key envelope." }
        if (!envelopeDirectory.mkdirs() && !envelopeDirectory.isDirectory) {
            throw IOException("Secure envelope storage is unavailable.")
        }
        val temporary = File.createTempFile("${installationNamespace.value}.", ".pending", envelopeDirectory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath())
            }
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private companion object {
        const val MAX_ENVELOPE_BYTES = 1_024L
    }
}
