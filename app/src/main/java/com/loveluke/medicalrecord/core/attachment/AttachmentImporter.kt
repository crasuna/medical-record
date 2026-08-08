package com.loveluke.medicalrecord.core.attachment

import com.loveluke.medicalrecord.core.security.SecretBytes
import java.io.InputStream
import java.util.UUID

const val MAX_ATTACHMENTS_PER_BATCH: Int = 10

sealed interface AttachmentSourceParseResult {
    data class Passed(val pageCount: Int? = null) : AttachmentSourceParseResult {
        init {
            require(pageCount == null || pageCount > 0) { "PDF page count must be positive." }
        }
    }

    data object Failed : AttachmentSourceParseResult
    data object NotAvailable : AttachmentSourceParseResult
}

interface AttachmentInputSource {
    val displayName: String?
    val declaredMimeType: String?

    fun openStream(): InputStream

    /** Optional platform parser check in addition to bounded structural validation. */
    fun checkParseability(mediaType: AttachmentMediaType): AttachmentSourceParseResult =
        AttachmentSourceParseResult.NotAvailable
}

class InputStreamAttachmentSource(
    override val displayName: String?,
    override val declaredMimeType: String?,
    private val openStream: () -> InputStream,
    private val parseabilityCheck: (AttachmentMediaType) -> AttachmentSourceParseResult = {
        AttachmentSourceParseResult.NotAvailable
    },
) : AttachmentInputSource {
    override fun openStream(): InputStream = openStream.invoke()

    override fun checkParseability(mediaType: AttachmentMediaType): AttachmentSourceParseResult =
        parseabilityCheck(mediaType)
}

data class ImportedAttachment(
    val attachmentId: UUID,
    val relativePath: AttachmentRelativePath,
    val displayName: String,
    val mediaType: AttachmentMediaType,
    val plaintextBytes: Long,
    val pageCount: Int?,
)

enum class AttachmentBatchFailure {
    TOO_MANY_ITEMS,
}

enum class AttachmentImportFailure {
    SOURCE_UNAVAILABLE,
    TOO_LARGE,
    MISSING_MIME,
    UNSUPPORTED_MIME,
    MIME_MAGIC_MISMATCH,
    MALFORMED_CONTENT,
    PLATFORM_PARSE_FAILED,
    STORAGE_FAILURE,
}

sealed interface AttachmentItemImportResult {
    data class Success(
        val index: Int,
        val attachment: ImportedAttachment,
    ) : AttachmentItemImportResult

    data class Failure(
        val index: Int,
        val reason: AttachmentImportFailure,
    ) : AttachmentItemImportResult
}

sealed interface AttachmentBatchImportResult {
    data class Completed(val items: List<AttachmentItemImportResult>) : AttachmentBatchImportResult
    data class Rejected(val reason: AttachmentBatchFailure) : AttachmentBatchImportResult
}

fun interface AttachmentIdGenerator {
    fun nextId(): UUID
}

/** Blocking import primitive. Repositories should invoke it on their I/O dispatcher. */
class AttachmentImporter(
    private val storagePaths: AttachmentStoragePaths,
    private val cipherContainer: AttachmentCipherContainer = AttachmentCipherContainer(),
    private val attachmentIdGenerator: AttachmentIdGenerator = AttachmentIdGenerator(UUID::randomUUID),
) {
    fun importBatch(
        patientId: UUID,
        encounterId: UUID,
        sources: List<AttachmentInputSource>,
        attachmentMasterKey: SecretBytes,
    ): AttachmentBatchImportResult {
        if (sources.size > MAX_ATTACHMENTS_PER_BATCH) {
            return AttachmentBatchImportResult.Rejected(AttachmentBatchFailure.TOO_MANY_ITEMS)
        }
        return AttachmentBatchImportResult.Completed(
            sources.mapIndexed { index, source ->
                importOne(index, patientId, encounterId, source, attachmentMasterKey)
            },
        )
    }

    private fun importOne(
        index: Int,
        patientId: UUID,
        encounterId: UUID,
        source: AttachmentInputSource,
        attachmentMasterKey: SecretBytes,
    ): AttachmentItemImportResult {
        val attachmentId = attachmentIdGenerator.nextId()
        val identity = AttachmentIdentity(patientId, encounterId, attachmentId)
        val relativePath = AttachmentRelativePath.original(attachmentId)
        val encryptedFile = storagePaths.resolve(relativePath)
        val inspectingInput = try {
            InspectingInputStream(source.openStream())
        } catch (_: Exception) {
            return AttachmentItemImportResult.Failure(index, AttachmentImportFailure.SOURCE_UNAVAILABLE)
        }

        val encryptionResult = try {
            inspectingInput.use { input ->
                cipherContainer.encrypt(
                    source = input,
                    destination = encryptedFile,
                    masterKey = attachmentMasterKey,
                    identity = identity,
                    payloadKind = AttachmentPayloadKind.ORIGINAL,
                    maximumPlaintextBytes = MAX_ATTACHMENT_BYTES,
                )
            }
        } catch (_: Exception) {
            encryptedFile.delete()
            return AttachmentItemImportResult.Failure(index, AttachmentImportFailure.SOURCE_UNAVAILABLE)
        }
        if (encryptionResult is AttachmentEncryptionResult.Failure) {
            return AttachmentItemImportResult.Failure(
                index = index,
                reason = when (encryptionResult.reason) {
                    AttachmentEncryptionFailure.TOO_LARGE -> AttachmentImportFailure.TOO_LARGE
                    AttachmentEncryptionFailure.DESTINATION_EXISTS,
                    AttachmentEncryptionFailure.CRYPTOGRAPHY_UNAVAILABLE,
                    AttachmentEncryptionFailure.IO_FAILURE,
                    -> AttachmentImportFailure.STORAGE_FAILURE
                },
            )
        }
        encryptionResult as AttachmentEncryptionResult.Success

        val validation = AttachmentContentValidator.validate(
            declaredMimeType = source.declaredMimeType,
            inspection = inspectingInput.snapshot(),
        )
        if (validation is AttachmentValidationResult.Rejected) {
            return rejectAndDelete(index, encryptedFile, validation.reason.toImportFailure())
        }
        validation as AttachmentValidationResult.Accepted

        val parseResult = try {
            source.checkParseability(validation.mediaType)
        } catch (_: Exception) {
            AttachmentSourceParseResult.Failed
        }
        if (parseResult == AttachmentSourceParseResult.Failed) {
            return rejectAndDelete(index, encryptedFile, AttachmentImportFailure.PLATFORM_PARSE_FAILED)
        }
        val reportedPageCount = (parseResult as? AttachmentSourceParseResult.Passed)?.pageCount
        val pageCount = when (validation.mediaType) {
            AttachmentMediaType.PDF -> reportedPageCount
                ?: return rejectAndDelete(
                    index,
                    encryptedFile,
                    AttachmentImportFailure.PLATFORM_PARSE_FAILED,
                )

            AttachmentMediaType.JPEG,
            AttachmentMediaType.PNG,
            AttachmentMediaType.WEBP,
            AttachmentMediaType.HEIC,
            AttachmentMediaType.HEIF,
            -> {
                if (reportedPageCount != null) {
                    return rejectAndDelete(
                        index,
                        encryptedFile,
                        AttachmentImportFailure.PLATFORM_PARSE_FAILED,
                    )
                }
                null
            }
        }

        return AttachmentItemImportResult.Success(
            index = index,
            attachment = ImportedAttachment(
                attachmentId = attachmentId,
                relativePath = relativePath,
                displayName = sanitizeDisplayName(source.displayName, attachmentId, validation.mediaType),
                mediaType = validation.mediaType,
                plaintextBytes = encryptionResult.plaintextBytes,
                pageCount = pageCount,
            ),
        )
    }

    private fun rejectAndDelete(
        index: Int,
        encryptedFile: java.io.File,
        failure: AttachmentImportFailure,
    ): AttachmentItemImportResult.Failure {
        if (encryptedFile.exists() && !encryptedFile.delete()) {
            return AttachmentItemImportResult.Failure(index, AttachmentImportFailure.STORAGE_FAILURE)
        }
        return AttachmentItemImportResult.Failure(index, failure)
    }
}

private fun AttachmentValidationFailure.toImportFailure(): AttachmentImportFailure = when (this) {
    AttachmentValidationFailure.MISSING_MIME -> AttachmentImportFailure.MISSING_MIME
    AttachmentValidationFailure.UNSUPPORTED_MIME -> AttachmentImportFailure.UNSUPPORTED_MIME
    AttachmentValidationFailure.MIME_MAGIC_MISMATCH -> AttachmentImportFailure.MIME_MAGIC_MISMATCH
    AttachmentValidationFailure.MALFORMED_CONTENT -> AttachmentImportFailure.MALFORMED_CONTENT
}

private fun sanitizeDisplayName(
    sourceName: String?,
    attachmentId: UUID,
    mediaType: AttachmentMediaType,
): String {
    val safe = sourceName
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(MAX_DISPLAY_NAME_CHARS)
        .orEmpty()
    if (safe.isNotBlank()) return safe
    val extension = when (mediaType) {
        AttachmentMediaType.PDF -> "pdf"
        AttachmentMediaType.JPEG -> "jpg"
        AttachmentMediaType.PNG -> "png"
        AttachmentMediaType.WEBP -> "webp"
        AttachmentMediaType.HEIC -> "heic"
        AttachmentMediaType.HEIF -> "heif"
    }
    return "$attachmentId.$extension"
}

private const val MAX_DISPLAY_NAME_CHARS = 255
