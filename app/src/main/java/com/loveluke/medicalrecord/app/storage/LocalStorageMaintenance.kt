package com.loveluke.medicalrecord.app.storage

import com.loveluke.medicalrecord.core.attachment.AttachmentOrphanCleaner
import com.loveluke.medicalrecord.core.attachment.AttachmentRelativePath
import com.loveluke.medicalrecord.core.attachment.PlaintextColdStartCleanupReport
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import com.loveluke.medicalrecord.core.database.AppDatabase
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface LocalStorageMaintenanceGateway {
    suspend fun removeStalePlaintext(): PlaintextColdStartCleanupReport

    suspend fun removeUnreferencedCiphertext()
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

    override suspend fun removeUnreferencedCiphertext() {
        withContext(ioDispatcher) {
            val rows = databaseProvider.get().encounterDao().getAllStoredAttachmentPaths()
            val parsedPaths = mutableSetOf<AttachmentRelativePath>()
            for (row in rows) {
                val original = runCatching {
                    AttachmentRelativePath.parseStored(row.originalRelativePath)
                }.getOrNull() ?: return@withContext
                parsedPaths += original
                row.thumbnailRelativePath?.let { rawThumbnail ->
                    val thumbnail = runCatching {
                        AttachmentRelativePath.parseStored(rawThumbnail)
                    }.getOrNull() ?: return@withContext
                    parsedPaths += thumbnail
                }
            }
            attachmentOrphanCleaner.clean(parsedPaths)
        }
    }
}
