package com.loveluke.medicalrecord.core.attachment

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.loveluke.medicalrecord.core.database.EncounterRepository
import com.loveluke.medicalrecord.core.model.Attachment
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.AttachmentKind
import com.loveluke.medicalrecord.core.model.EncounterDetails
import com.loveluke.medicalrecord.core.security.SecureMaterialFailure
import com.loveluke.medicalrecord.core.security.SecureMaterialManager
import com.loveluke.medicalrecord.core.security.SecureMaterialResolution
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface AttachmentServiceImportResult {
    data class Completed(
        val items: List<AttachmentServiceItemResult>,
    ) : AttachmentServiceImportResult

    data class Rejected(
        val reason: AttachmentServiceBatchRejection,
    ) : AttachmentServiceImportResult

    data class FailClosed(
        val reason: SecureMaterialFailure,
    ) : AttachmentServiceImportResult
}

enum class AttachmentServiceBatchRejection {
    TOO_MANY_ITEMS,
    INVALID_IDENTITY,
}

sealed interface AttachmentServiceItemResult {
    val index: Int

    data class Imported(
        override val index: Int,
        val attachment: Attachment,
    ) : AttachmentServiceItemResult

    data class Failed(
        override val index: Int,
        val reason: AttachmentServiceFailure,
    ) : AttachmentServiceItemResult
}

enum class AttachmentServiceFailure {
    SOURCE_UNAVAILABLE,
    TOO_LARGE,
    MISSING_MIME,
    UNSUPPORTED_MIME,
    MIME_MAGIC_MISMATCH,
    MALFORMED_CONTENT,
    PLATFORM_PARSE_FAILED,
    STORAGE_FAILURE,
    METADATA_WRITE_FAILED,
    METADATA_WRITE_FAILED_CIPHERTEXT_RETAINED,
}

sealed interface CameraCapturePreparation {
    data class Ready(val handle: CameraCaptureHandle) : CameraCapturePreparation
    data class Failed(val reason: CameraCaptureFailure) : CameraCapturePreparation
}

enum class CameraCaptureFailure {
    STORAGE_UNAVAILABLE,
    CONTENT_URI_UNAVAILABLE,
}

sealed interface CameraCaptureCommitResult {
    data class Completed(
        val importResult: AttachmentServiceImportResult,
        val plaintextCleanup: PlaintextCleanupResult,
    ) : CameraCaptureCommitResult

    data object AlreadyFinalized : CameraCaptureCommitResult
}

class CameraCaptureHandle internal constructor(
    val contentUri: Uri,
    private val plaintextHandle: TemporaryPlaintextHandle,
) : AutoCloseable {
    private val state = AtomicReference(CameraCaptureState.OPEN)

    internal val file: File
        get() = plaintextHandle.file

    internal fun claimForCommit(): Boolean =
        state.compareAndSet(CameraCaptureState.OPEN, CameraCaptureState.COMMITTING)

    internal fun finishCommit(success: Boolean): PlaintextCleanupResult {
        check(state.compareAndSet(CameraCaptureState.COMMITTING, CameraCaptureState.CLEANING)) {
            "Camera capture is not being committed."
        }
        val result = if (success) {
            plaintextHandle.cleanupAfterSuccess()
        } else {
            plaintextHandle.cleanupAfterFailure()
        }
        state.set(if (result == PlaintextCleanupResult.FAILED) CameraCaptureState.CLEANUP_PENDING else CameraCaptureState.CLOSED)
        return result
    }

    fun cancel(): PlaintextCleanupResult {
        while (true) {
            when (val current = state.get()) {
                CameraCaptureState.CLOSED -> return PlaintextCleanupResult.ALREADY_ABSENT
                CameraCaptureState.COMMITTING,
                CameraCaptureState.CLEANING,
                -> return PlaintextCleanupResult.IN_USE

                CameraCaptureState.OPEN,
                CameraCaptureState.CLEANUP_PENDING,
                -> if (state.compareAndSet(current, CameraCaptureState.CLEANING)) break
            }
        }
        val result = plaintextHandle.cleanupAfterCancellation()
        state.set(if (result == PlaintextCleanupResult.FAILED) CameraCaptureState.CLEANUP_PENDING else CameraCaptureState.CLOSED)
        return result
    }

    override fun close() {
        cancel()
    }

    private enum class CameraCaptureState {
        OPEN,
        COMMITTING,
        CLEANING,
        CLEANUP_PENDING,
        CLOSED,
    }
}

sealed interface AttachmentPreviewResult {
    data class Ready(val handle: AttachmentPreviewHandle) : AttachmentPreviewResult

    data class Quarantined(
        val reason: AttachmentQuarantineReason,
        val metadataMarked: Boolean,
    ) : AttachmentPreviewResult

    data class FailClosed(val reason: SecureMaterialFailure) : AttachmentPreviewResult
    data class Failed(val reason: AttachmentPreviewFailure) : AttachmentPreviewResult
}

enum class AttachmentPreviewFailure {
    INVALID_METADATA,
    METADATA_NOT_FOUND,
    ALREADY_QUARANTINED,
    TEMPORARY_STORAGE_UNAVAILABLE,
    IO_FAILURE,
    CRYPTOGRAPHY_UNAVAILABLE,
}

class AttachmentPreviewHandle internal constructor(
    val file: File,
    val mimeType: String,
    private val plaintextHandle: TemporaryPlaintextHandle,
) : AutoCloseable {
    fun cleanup(): PlaintextCleanupResult = plaintextHandle.cleanupAfterSuccess()

    override fun close() {
        cleanup()
    }
}

sealed interface AttachmentDeleteResult {
    data class Deleted(
        val ciphertextFilesDeleted: Int,
        val metadataDeleted: Boolean,
        val tombstoneFilesRetained: Int = 0,
    ) : AttachmentDeleteResult

    data class CiphertextDeleteFailed(
        val ciphertextFilesStaged: Int,
        val rollbackState: AttachmentDeletionRollbackState,
        val tombstoneFilesRetained: Int,
        val metadataPreserved: Boolean = true,
    ) : AttachmentDeleteResult

    data class MetadataDeleteFailed(
        val ciphertextFilesStaged: Int,
        val rollbackState: AttachmentDeletionRollbackState,
        val tombstoneFilesRetained: Int,
    ) : AttachmentDeleteResult

    data class Failed(val reason: AttachmentDeleteFailure) : AttachmentDeleteResult
}

enum class AttachmentDeleteFailure {
    INVALID_METADATA,
}

sealed interface EncounterDeleteResult {
    data class Deleted(
        val ciphertextFilesDeleted: Int,
        val encounterMetadataDeleted: Boolean,
        val tombstoneFilesRetained: Int = 0,
    ) : EncounterDeleteResult

    data class CiphertextDeleteFailed(
        val ciphertextFilesStaged: Int,
        val rollbackState: AttachmentDeletionRollbackState,
        val tombstoneFilesRetained: Int,
        val encounterAndAttachmentMetadataPreserved: Boolean = true,
    ) : EncounterDeleteResult

    data class MetadataDeleteFailed(
        val ciphertextFilesStaged: Int,
        val rollbackState: AttachmentDeletionRollbackState,
        val tombstoneFilesRetained: Int,
    ) : EncounterDeleteResult

    data class Failed(val reason: EncounterDeleteFailure) : EncounterDeleteResult
}

enum class EncounterDeleteFailure {
    INVALID_METADATA,
}

interface EncryptedAttachmentService {
    suspend fun importUris(
        patientId: String,
        encounterId: String,
        uris: List<Uri>,
    ): AttachmentServiceImportResult

    suspend fun importSources(
        patientId: String,
        encounterId: String,
        sources: List<AttachmentInputSource>,
    ): AttachmentServiceImportResult

    suspend fun prepareCameraCapture(): CameraCapturePreparation

    suspend fun commitCameraCapture(
        patientId: String,
        encounterId: String,
        handle: CameraCaptureHandle,
    ): CameraCaptureCommitResult

    fun cancelCameraCapture(handle: CameraCaptureHandle): PlaintextCleanupResult

    suspend fun openPreview(attachment: Attachment): AttachmentPreviewResult

    suspend fun delete(attachment: Attachment): AttachmentDeleteResult

    suspend fun deleteEncounter(details: EncounterDetails): EncounterDeleteResult
}

internal fun interface CameraContentUriFactory {
    fun create(file: File): Uri
}

internal fun interface AttachmentUriSourceFactory {
    fun create(uri: Uri): AttachmentInputSource
}

internal fun interface CameraImageParser {
    fun inspect(file: File): AttachmentSourceParseResult
}

@Singleton
class DefaultEncryptedAttachmentService internal constructor(
    private val encounterRepository: EncounterRepository,
    private val secureMaterialManager: SecureMaterialManager,
    private val storagePaths: AttachmentStoragePaths,
    private val temporaryPlaintextRegistry: TemporaryPlaintextRegistry,
    private val attachmentImporter: AttachmentImporter,
    private val cipherContainer: AttachmentCipherContainer,
    private val uriSourceFactory: AttachmentUriSourceFactory,
    private val cameraContentUriFactory: CameraContentUriFactory,
    private val cameraImageParser: CameraImageParser,
    private val encryptedFileDeleter: EncryptedFileDeleter,
    private val deletionTransaction: AttachmentDeletionTransaction =
        AttachmentDeletionTransaction(storagePaths),
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher,
) : EncryptedAttachmentService {
    private val mutationMutex = Mutex()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        encounterRepository: EncounterRepository,
        secureMaterialManager: SecureMaterialManager,
        storagePaths: AttachmentStoragePaths,
        temporaryPlaintextRegistry: TemporaryPlaintextRegistry,
    ) : this(
        encounterRepository = encounterRepository,
        secureMaterialManager = secureMaterialManager,
        storagePaths = storagePaths,
        temporaryPlaintextRegistry = temporaryPlaintextRegistry,
        attachmentImporter = AttachmentImporter(storagePaths),
        cipherContainer = AttachmentCipherContainer(),
        uriSourceFactory = AttachmentUriSourceFactory { uri ->
            AndroidUriAttachmentInputSource(context.contentResolver, uri)
        },
        cameraContentUriFactory = CameraContentUriFactory { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        },
        cameraImageParser = CameraImageParser { file ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth > 0 && options.outHeight > 0 && !options.outMimeType.isNullOrBlank()) {
                AttachmentSourceParseResult.Passed()
            } else {
                AttachmentSourceParseResult.Failed
            }
        },
        encryptedFileDeleter = EncryptedFileDeleter(File::delete),
        deletionTransaction = AttachmentDeletionTransaction(storagePaths),
        clock = Clock.systemUTC(),
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun importUris(
        patientId: String,
        encounterId: String,
        uris: List<Uri>,
    ): AttachmentServiceImportResult {
        preflight(patientId, encounterId, uris.size)?.let { return it }
        val sources = withContext(ioDispatcher) {
            uris.map { uri ->
                try {
                    uriSourceFactory.create(uri)
                } catch (_: RuntimeException) {
                    UnavailableAttachmentInputSource
                }
            }
        }
        return importSources(patientId, encounterId, sources)
    }

    override suspend fun importSources(
        patientId: String,
        encounterId: String,
        sources: List<AttachmentInputSource>,
    ): AttachmentServiceImportResult = withContext(ioDispatcher) {
        mutationMutex.withLock {
            preflight(patientId, encounterId, sources.size)?.let { return@withLock it }
            importSourcesLocked(patientId, encounterId, sources)
        }
    }

    override suspend fun prepareCameraCapture(): CameraCapturePreparation = withContext(ioDispatcher) {
        val plaintext = try {
            temporaryPlaintextRegistry.createCameraCapture(CAMERA_EXTENSION)
        } catch (_: IOException) {
            return@withContext CameraCapturePreparation.Failed(
                CameraCaptureFailure.STORAGE_UNAVAILABLE,
            )
        } catch (_: RuntimeException) {
            return@withContext CameraCapturePreparation.Failed(
                CameraCaptureFailure.STORAGE_UNAVAILABLE,
            )
        }
        try {
            CameraCapturePreparation.Ready(
                CameraCaptureHandle(
                    contentUri = cameraContentUriFactory.create(plaintext.file),
                    plaintextHandle = plaintext,
                ),
            )
        } catch (_: RuntimeException) {
            plaintext.cleanupAfterFailure()
            CameraCapturePreparation.Failed(CameraCaptureFailure.CONTENT_URI_UNAVAILABLE)
        }
    }

    override suspend fun commitCameraCapture(
        patientId: String,
        encounterId: String,
        handle: CameraCaptureHandle,
    ): CameraCaptureCommitResult {
        if (!handle.claimForCommit()) return CameraCaptureCommitResult.AlreadyFinalized
        var succeeded = false
        return try {
            val source = InputStreamAttachmentSource(
                displayName = CAMERA_DISPLAY_NAME,
                declaredMimeType = CAMERA_MIME_TYPE,
                openStream = { FileInputStream(handle.file) },
                parseabilityCheck = { cameraImageParser.inspect(handle.file) },
            )
            val importResult = importSources(patientId, encounterId, listOf(source))
            succeeded = importResult is AttachmentServiceImportResult.Completed &&
                importResult.items.singleOrNull() is AttachmentServiceItemResult.Imported
            CameraCaptureCommitResult.Completed(
                importResult = importResult,
                plaintextCleanup = handle.finishCommit(succeeded),
            )
        } catch (cancellation: CancellationException) {
            handle.finishCommit(success = false)
            throw cancellation
        } catch (_: RuntimeException) {
            CameraCaptureCommitResult.Completed(
                importResult = AttachmentServiceImportResult.Completed(
                    listOf(
                        AttachmentServiceItemResult.Failed(
                            index = 0,
                            reason = AttachmentServiceFailure.SOURCE_UNAVAILABLE,
                        ),
                    ),
                ),
                plaintextCleanup = handle.finishCommit(success = false),
            )
        }
    }

    override fun cancelCameraCapture(handle: CameraCaptureHandle): PlaintextCleanupResult =
        handle.cancel()

    override suspend fun openPreview(attachment: Attachment): AttachmentPreviewResult =
        withContext(ioDispatcher) {
            mutationMutex.withLock { openPreviewLocked(attachment) }
        }

    override suspend fun delete(attachment: Attachment): AttachmentDeleteResult =
        withContext(ioDispatcher) {
            mutationMutex.withLock { deleteAttachmentLocked(attachment) }
        }

    override suspend fun deleteEncounter(details: EncounterDetails): EncounterDeleteResult =
        withContext(ioDispatcher) {
            mutationMutex.withLock { deleteEncounterLocked(details) }
        }

    private suspend fun importSourcesLocked(
        patientId: String,
        encounterId: String,
        sources: List<AttachmentInputSource>,
    ): AttachmentServiceImportResult {
        if (sources.isEmpty()) return AttachmentServiceImportResult.Completed(emptyList())
        val patientUuid = patientId.canonicalUuidOrNull()
            ?: return AttachmentServiceImportResult.Rejected(
                AttachmentServiceBatchRejection.INVALID_IDENTITY,
            )
        val encounterUuid = encounterId.canonicalUuidOrNull()
            ?: return AttachmentServiceImportResult.Rejected(
                AttachmentServiceBatchRejection.INVALID_IDENTITY,
            )
        val material = try {
            secureMaterialManager.resolveAttachmentMasterKey(storagePaths)
        } catch (_: RuntimeException) {
            return AttachmentServiceImportResult.FailClosed(
                SecureMaterialFailure.SECURE_MATERIAL_RESOLUTION_FAILED,
            )
        }
        if (material is SecureMaterialResolution.FailClosed) {
            return AttachmentServiceImportResult.FailClosed(material.reason)
        }
        val secret = when (material) {
            is SecureMaterialResolution.Available -> material.secret
            is SecureMaterialResolution.Provisioned -> material.secret
            is SecureMaterialResolution.FailClosed -> error("Handled above")
        }
        val imported = try {
            try {
                attachmentImporter.importBatch(
                    patientId = patientUuid,
                    encounterId = encounterUuid,
                    sources = sources,
                    attachmentMasterKey = secret,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                return AttachmentServiceImportResult.Completed(
                    sources.indices.map { index ->
                        AttachmentServiceItemResult.Failed(
                            index,
                            AttachmentServiceFailure.STORAGE_FAILURE,
                        )
                    },
                )
            }
        } finally {
            secret.close()
        }
        if (imported is AttachmentBatchImportResult.Rejected) {
            return AttachmentServiceImportResult.Rejected(
                AttachmentServiceBatchRejection.TOO_MANY_ITEMS,
            )
        }
        imported as AttachmentBatchImportResult.Completed

        val uncommitted = imported.items
            .filterIsInstance<AttachmentItemImportResult.Success>()
            .associateByTo(mutableMapOf(), { it.attachment.attachmentId }, { it.attachment.relativePath })
        val results = mutableListOf<AttachmentServiceItemResult>()
        try {
            for (item in imported.items) {
                when (item) {
                    is AttachmentItemImportResult.Failure -> results += AttachmentServiceItemResult.Failed(
                        index = item.index,
                        reason = item.reason.toServiceFailure(),
                    )

                    is AttachmentItemImportResult.Success -> {
                        val now = clock.instant()
                        val model = item.attachment.toModel(patientUuid, encounterUuid, now)
                        try {
                            encounterRepository.saveAttachment(model)
                            uncommitted.remove(item.attachment.attachmentId)
                            results += AttachmentServiceItemResult.Imported(item.index, model)
                        } catch (cancellation: CancellationException) {
                            rollbackCiphertext(item.attachment.relativePath)
                            uncommitted.remove(item.attachment.attachmentId)
                            throw cancellation
                        } catch (_: RuntimeException) {
                            val removed = rollbackCiphertext(item.attachment.relativePath)
                            uncommitted.remove(item.attachment.attachmentId)
                            results += AttachmentServiceItemResult.Failed(
                                index = item.index,
                                reason = if (removed) {
                                    AttachmentServiceFailure.METADATA_WRITE_FAILED
                                } else {
                                    AttachmentServiceFailure.METADATA_WRITE_FAILED_CIPHERTEXT_RETAINED
                                },
                            )
                        }
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            uncommitted.values.forEach(::rollbackCiphertext)
            throw cancellation
        }
        return AttachmentServiceImportResult.Completed(results)
    }

    private suspend fun openPreviewLocked(attachment: Attachment): AttachmentPreviewResult {
        if (!attachment.hasCanonicalIdentity()) {
            return AttachmentPreviewResult.Failed(AttachmentPreviewFailure.INVALID_METADATA)
        }
        val current = try {
            currentAttachment(attachment)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return AttachmentPreviewResult.Failed(AttachmentPreviewFailure.METADATA_NOT_FOUND)
        }
            ?: return AttachmentPreviewResult.Failed(AttachmentPreviewFailure.METADATA_NOT_FOUND)
        if (current.integrityState == AttachmentIntegrityState.QUARANTINED) {
            return AttachmentPreviewResult.Failed(AttachmentPreviewFailure.ALREADY_QUARANTINED)
        }
        val validated = validateAttachment(current)
            ?: return AttachmentPreviewResult.Failed(AttachmentPreviewFailure.INVALID_METADATA)
        val extension = current.previewExtension()
            ?: return AttachmentPreviewResult.Failed(AttachmentPreviewFailure.INVALID_METADATA)
        val plaintext = try {
            temporaryPlaintextRegistry.reservePreview(extension)
        } catch (_: RuntimeException) {
            return AttachmentPreviewResult.Failed(
                AttachmentPreviewFailure.TEMPORARY_STORAGE_UNAVAILABLE,
            )
        }
        val material = try {
            secureMaterialManager.resolveAttachmentMasterKey(storagePaths)
        } catch (_: RuntimeException) {
            plaintext.cleanupAfterFailure()
            return AttachmentPreviewResult.FailClosed(
                SecureMaterialFailure.SECURE_MATERIAL_RESOLUTION_FAILED,
            )
        }
        if (material is SecureMaterialResolution.FailClosed) {
            plaintext.cleanupAfterFailure()
            return AttachmentPreviewResult.FailClosed(material.reason)
        }
        val secret = when (material) {
            is SecureMaterialResolution.Available -> material.secret
            is SecureMaterialResolution.Provisioned -> material.secret
            is SecureMaterialResolution.FailClosed -> error("Handled above")
        }
        val decryption = try {
            try {
                cipherContainer.decrypt(
                    source = validated.originalFile,
                    destination = plaintext.file,
                    masterKey = secret,
                    identity = validated.identity,
                    payloadKind = AttachmentPayloadKind.ORIGINAL,
                )
            } catch (cancellation: CancellationException) {
                plaintext.cleanupAfterCancellation()
                throw cancellation
            } catch (_: RuntimeException) {
                plaintext.cleanupAfterFailure()
                return AttachmentPreviewResult.Failed(AttachmentPreviewFailure.IO_FAILURE)
            }
        } finally {
            secret.close()
        }
        return when (decryption) {
            is AttachmentDecryptionResult.Success -> AttachmentPreviewResult.Ready(
                AttachmentPreviewHandle(
                    file = plaintext.file,
                    mimeType = current.mimeType,
                    plaintextHandle = plaintext,
                ),
            )

            is AttachmentDecryptionResult.Quarantined -> {
                plaintext.cleanupAfterFailure()
                val now = clock.instant()
                val marked = try {
                    encounterRepository.saveAttachment(
                        current.copy(
                            integrityState = AttachmentIntegrityState.QUARANTINED,
                            quarantinedAt = now,
                            updatedAt = now,
                        ),
                    )
                    true
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: RuntimeException) {
                    false
                }
                AttachmentPreviewResult.Quarantined(decryption.reason, marked)
            }

            is AttachmentDecryptionResult.Failure -> {
                plaintext.cleanupAfterFailure()
                AttachmentPreviewResult.Failed(
                    when (decryption.reason) {
                        AttachmentReadFailure.DESTINATION_EXISTS,
                        AttachmentReadFailure.IO_FAILURE,
                        -> AttachmentPreviewFailure.IO_FAILURE

                        AttachmentReadFailure.CRYPTOGRAPHY_UNAVAILABLE ->
                            AttachmentPreviewFailure.CRYPTOGRAPHY_UNAVAILABLE
                    },
                )
            }
        }
    }

    private suspend fun deleteAttachmentLocked(attachment: Attachment): AttachmentDeleteResult {
        if (!attachment.hasCanonicalIdentity()) {
            return AttachmentDeleteResult.Failed(AttachmentDeleteFailure.INVALID_METADATA)
        }
        val current = try {
            currentAttachment(attachment)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return AttachmentDeleteResult.MetadataDeleteFailed(
                ciphertextFilesStaged = 0,
                rollbackState = AttachmentDeletionRollbackState.NOT_REQUIRED,
                tombstoneFilesRetained = 0,
            )
        }
            ?: return AttachmentDeleteResult.Deleted(0, metadataDeleted = false)
        val validated = validateAttachment(current)
            ?: return AttachmentDeleteResult.Failed(AttachmentDeleteFailure.INVALID_METADATA)
        return when (
            val deletion = deletionTransaction.execute(validated.relativePaths) {
                encounterRepository.deleteAttachment(current.patientId, current.id)
            }
        ) {
            is AttachmentDeletionTransactionResult.Committed -> AttachmentDeleteResult.Deleted(
                ciphertextFilesDeleted = deletion.ciphertextFilesFinalized,
                metadataDeleted = true,
                tombstoneFilesRetained = deletion.tombstoneFilesRetained,
            )

            is AttachmentDeletionTransactionResult.StageFailed ->
                AttachmentDeleteResult.CiphertextDeleteFailed(
                    ciphertextFilesStaged = deletion.ciphertextFilesStaged,
                    rollbackState = deletion.rollbackState,
                    tombstoneFilesRetained = deletion.tombstoneFilesRetained,
                )

            is AttachmentDeletionTransactionResult.MetadataFailed ->
                AttachmentDeleteResult.MetadataDeleteFailed(
                    ciphertextFilesStaged = deletion.ciphertextFilesStaged,
                    rollbackState = deletion.rollbackState,
                    tombstoneFilesRetained = deletion.tombstoneFilesRetained,
                )
        }
    }

    private suspend fun deleteEncounterLocked(details: EncounterDetails): EncounterDeleteResult {
        val patientId = details.encounter.patientId.canonicalUuidOrNull()
            ?: return EncounterDeleteResult.Failed(EncounterDeleteFailure.INVALID_METADATA)
        val encounterId = details.encounter.id.canonicalUuidOrNull()
            ?: return EncounterDeleteResult.Failed(EncounterDeleteFailure.INVALID_METADATA)
        val current = try {
            encounterRepository.observeEncounter(
                patientId.toString(),
                encounterId.toString(),
            ).first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return EncounterDeleteResult.MetadataDeleteFailed(
                ciphertextFilesStaged = 0,
                rollbackState = AttachmentDeletionRollbackState.NOT_REQUIRED,
                tombstoneFilesRetained = 0,
            )
        } ?: return EncounterDeleteResult.Deleted(0, encounterMetadataDeleted = false)
        val validated = current.attachments.map { attachment ->
            validateAttachment(attachment)
                ?: return EncounterDeleteResult.Failed(EncounterDeleteFailure.INVALID_METADATA)
        }
        val relativePaths = validated.flatMap(ValidatedAttachment::relativePaths)
        return when (
            val deletion = deletionTransaction.execute(relativePaths) {
                encounterRepository.deleteEncounter(
                    patientId.toString(),
                    encounterId.toString(),
                )
            }
        ) {
            is AttachmentDeletionTransactionResult.Committed -> EncounterDeleteResult.Deleted(
                ciphertextFilesDeleted = deletion.ciphertextFilesFinalized,
                encounterMetadataDeleted = true,
                tombstoneFilesRetained = deletion.tombstoneFilesRetained,
            )

            is AttachmentDeletionTransactionResult.StageFailed ->
                EncounterDeleteResult.CiphertextDeleteFailed(
                    ciphertextFilesStaged = deletion.ciphertextFilesStaged,
                    rollbackState = deletion.rollbackState,
                    tombstoneFilesRetained = deletion.tombstoneFilesRetained,
                )

            is AttachmentDeletionTransactionResult.MetadataFailed ->
                EncounterDeleteResult.MetadataDeleteFailed(
                    ciphertextFilesStaged = deletion.ciphertextFilesStaged,
                    rollbackState = deletion.rollbackState,
                    tombstoneFilesRetained = deletion.tombstoneFilesRetained,
                )
        }
    }

    private suspend fun currentAttachment(requested: Attachment): Attachment? {
        val patientId = requested.patientId.canonicalUuidOrNull() ?: return null
        val encounterId = requested.encounterId.canonicalUuidOrNull() ?: return null
        val attachmentId = requested.id.canonicalUuidOrNull() ?: return null
        return encounterRepository.observeEncounter(
            patientId.toString(),
            encounterId.toString(),
        ).first()?.attachments?.firstOrNull { it.id == attachmentId.toString() }
    }

    private fun validateAttachment(attachment: Attachment): ValidatedAttachment? {
        val patientId = attachment.patientId.canonicalUuidOrNull() ?: return null
        val encounterId = attachment.encounterId.canonicalUuidOrNull() ?: return null
        val attachmentId = attachment.id.canonicalUuidOrNull() ?: return null
        val originalPath = try {
            AttachmentRelativePath.parseStored(attachment.encryptedRelativePath)
        } catch (_: UnsafeAttachmentPathException) {
            return null
        }
        if (
            originalPath.payloadKind != AttachmentPayloadKind.ORIGINAL ||
            originalPath.attachmentId() != attachmentId
        ) {
            return null
        }
        val thumbnailPath = attachment.encryptedThumbnailRelativePath?.let { raw ->
            val parsed = try {
                AttachmentRelativePath.parseStored(raw)
            } catch (_: UnsafeAttachmentPathException) {
                return null
            }
            if (
                parsed.payloadKind != AttachmentPayloadKind.THUMBNAIL ||
                parsed.attachmentId() != attachmentId
            ) {
                return null
            }
            parsed
        }
        val paths = listOfNotNull(originalPath, thumbnailPath)
        val files = try {
            paths.map(storagePaths::resolve)
        } catch (_: RuntimeException) {
            return null
        }
        if (files.any { Files.isSymbolicLink(it.toPath()) }) return null
        return ValidatedAttachment(
            identity = AttachmentIdentity(patientId, encounterId, attachmentId),
            originalFile = files.first(),
            relativePaths = paths,
        )
    }

    private fun rollbackCiphertext(relativePath: AttachmentRelativePath): Boolean {
        val file = try {
            storagePaths.resolve(relativePath)
        } catch (_: RuntimeException) {
            return false
        }
        if (!file.exists()) return true
        return try {
            encryptedFileDeleter.delete(file) || !file.exists()
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun preflight(
        patientId: String,
        encounterId: String,
        itemCount: Int,
    ): AttachmentServiceImportResult.Rejected? = when {
        itemCount > MAX_ATTACHMENTS_PER_BATCH -> AttachmentServiceImportResult.Rejected(
            AttachmentServiceBatchRejection.TOO_MANY_ITEMS,
        )

        patientId.canonicalUuidOrNull() == null || encounterId.canonicalUuidOrNull() == null ->
            AttachmentServiceImportResult.Rejected(AttachmentServiceBatchRejection.INVALID_IDENTITY)

        else -> null
    }

    private data class ValidatedAttachment(
        val identity: AttachmentIdentity,
        val originalFile: File,
        val relativePaths: List<AttachmentRelativePath>,
    )

    private companion object {
        const val CAMERA_EXTENSION = ".jpg"
        const val CAMERA_DISPLAY_NAME = "camera.jpg"
        const val CAMERA_MIME_TYPE = "image/jpeg"
    }
}

private data object UnavailableAttachmentInputSource : AttachmentInputSource {
    override val displayName: String? = null
    override val declaredMimeType: String? = null

    override fun openStream(): java.io.InputStream = throw IOException("Attachment source unavailable.")
}

private fun ImportedAttachment.toModel(
    patientId: UUID,
    encounterId: UUID,
    now: Instant,
): Attachment = Attachment(
    id = attachmentId.toString(),
    patientId = patientId.toString(),
    encounterId = encounterId.toString(),
    kind = if (mediaType == AttachmentMediaType.PDF) AttachmentKind.PDF else AttachmentKind.IMAGE,
    displayName = displayName,
    mimeType = mediaType.canonicalMimeType,
    encryptedRelativePath = relativePath.value,
    encryptedThumbnailRelativePath = null,
    sizeBytes = plaintextBytes,
    pageCount = pageCount,
    cryptoVersion = 1,
    integrityState = AttachmentIntegrityState.AVAILABLE,
    quarantinedAt = null,
    createdAt = now,
    updatedAt = now,
)

private fun AttachmentImportFailure.toServiceFailure(): AttachmentServiceFailure = when (this) {
    AttachmentImportFailure.SOURCE_UNAVAILABLE -> AttachmentServiceFailure.SOURCE_UNAVAILABLE
    AttachmentImportFailure.TOO_LARGE -> AttachmentServiceFailure.TOO_LARGE
    AttachmentImportFailure.MISSING_MIME -> AttachmentServiceFailure.MISSING_MIME
    AttachmentImportFailure.UNSUPPORTED_MIME -> AttachmentServiceFailure.UNSUPPORTED_MIME
    AttachmentImportFailure.MIME_MAGIC_MISMATCH -> AttachmentServiceFailure.MIME_MAGIC_MISMATCH
    AttachmentImportFailure.MALFORMED_CONTENT -> AttachmentServiceFailure.MALFORMED_CONTENT
    AttachmentImportFailure.PLATFORM_PARSE_FAILED -> AttachmentServiceFailure.PLATFORM_PARSE_FAILED
    AttachmentImportFailure.STORAGE_FAILURE -> AttachmentServiceFailure.STORAGE_FAILURE
}

private fun String.canonicalUuidOrNull(): UUID? {
    val parsed = try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return parsed.takeIf { it.toString() == this }
}

private fun AttachmentRelativePath.attachmentId(): UUID =
    UUID.fromString(value.substringAfter('/').substringBeforeLast('.'))

private fun Attachment.previewExtension(): String? = when (mimeType.lowercase()) {
    "application/pdf" -> ".pdf"
    "image/jpeg", "image/jpg" -> ".jpg"
    "image/png" -> ".png"
    "image/webp" -> ".webp"
    "image/heic" -> ".heic"
    "image/heif" -> ".heif"
    else -> null
}

private fun Attachment.hasCanonicalIdentity(): Boolean =
    patientId.canonicalUuidOrNull() != null &&
        encounterId.canonicalUuidOrNull() != null &&
        id.canonicalUuidOrNull() != null
