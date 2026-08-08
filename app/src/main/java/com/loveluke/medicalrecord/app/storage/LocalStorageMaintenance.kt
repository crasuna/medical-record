package com.loveluke.medicalrecord.app.storage

import com.loveluke.medicalrecord.core.attachment.AttachmentOrphanCleaner
import com.loveluke.medicalrecord.core.attachment.AttachmentOrphanCleanupReport
import com.loveluke.medicalrecord.core.attachment.AttachmentRelativePath
import com.loveluke.medicalrecord.core.attachment.PlaintextColdStartCleanupReport
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import com.loveluke.medicalrecord.core.attachment.UnsafeAttachmentPathException
import com.loveluke.medicalrecord.core.database.AppDatabase
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface LocalStorageMaintenanceGateway {
    suspend fun removeStalePlaintext(): PlaintextColdStartCleanupReport

    suspend fun removeUnreferencedCiphertext(): CiphertextMaintenanceResult
}

sealed interface CiphertextMaintenanceResult {
    data class Complete(
        val cleanupReport: AttachmentOrphanCleanupReport,
    ) : CiphertextMaintenanceResult

    data class Incomplete(
        val cleanupReport: AttachmentOrphanCleanupReport?,
        val invalidStoredAttachmentPathCount: Int = 0,
    ) : CiphertextMaintenanceResult {
        init {
            require(cleanupReport != null || invalidStoredAttachmentPathCount > 0)
        }
    }
}

@Singleton
class LocalStorageMaintenance internal constructor(
    private val temporaryPlaintextRegistry: TemporaryPlaintextRegistry,
    private val attachmentOrphanCleaner: AttachmentOrphanCleaner,
    private val databaseProvider: Provider<AppDatabase>,
    private val ioDispatcher: CoroutineDispatcher,
) : LocalStorageMaintenanceGateway {
    @Inject
    constructor(
        temporaryPlaintextRegistry: TemporaryPlaintextRegistry,
        attachmentOrphanCleaner: AttachmentOrphanCleaner,
        databaseProvider: Provider<AppDatabase>,
    ) : this(
        temporaryPlaintextRegistry,
        attachmentOrphanCleaner,
        databaseProvider,
        Dispatchers.IO,
    )

    override suspend fun removeStalePlaintext(): PlaintextColdStartCleanupReport =
        withContext(ioDispatcher) {
            temporaryPlaintextRegistry.cleanupOnColdStart()
        }

    override suspend fun removeUnreferencedCiphertext(): CiphertextMaintenanceResult =
        withContext(ioDispatcher) {
            val rows = databaseProvider.get().encounterDao().getAllStoredAttachmentPaths()
            val parsedPaths = mutableSetOf<AttachmentRelativePath>()
            var invalidStoredPathCount = 0
            for (row in rows) {
                val original = try {
                    AttachmentRelativePath.parseStored(row.originalRelativePath)
                } catch (_: UnsafeAttachmentPathException) {
                    invalidStoredPathCount += 1
                    null
                }
                if (original != null) parsedPaths += original
                row.thumbnailRelativePath?.let { rawThumbnail ->
                    val thumbnail = try {
                        AttachmentRelativePath.parseStored(rawThumbnail)
                    } catch (_: UnsafeAttachmentPathException) {
                        invalidStoredPathCount += 1
                        null
                    }
                    if (thumbnail != null) parsedPaths += thumbnail
                }
            }
            if (invalidStoredPathCount > 0) {
                return@withContext CiphertextMaintenanceResult.Incomplete(
                    cleanupReport = null,
                    invalidStoredAttachmentPathCount = invalidStoredPathCount,
                )
            }

            val cleanupReport = attachmentOrphanCleaner.clean(parsedPaths)
            if (cleanupReport.blocksReady()) {
                CiphertextMaintenanceResult.Incomplete(cleanupReport)
            } else {
                CiphertextMaintenanceResult.Complete(cleanupReport)
            }
        }

    private fun AttachmentOrphanCleanupReport.blocksReady(): Boolean =
        scanFailed ||
            failedDeletes > 0 ||
            failedDeletingOperations > 0 ||
            deletingConflicts > 0
}
