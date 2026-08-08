package com.loveluke.medicalrecord.core.security

import android.content.Context
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import java.io.File

class SecureMaterialManager(
    private val store: SecureMaterialStore,
) {
    fun resolveDatabasePassphrase(databaseFile: File): SecureMaterialResolution = store.resolve(
        purpose = SecureMaterialPurpose.DATABASE_PASSPHRASE,
        sensitiveDataExists = { databaseArtifactsExist(databaseFile) },
    )

    fun resolveAttachmentMasterKey(storagePaths: AttachmentStoragePaths): SecureMaterialResolution = store.resolve(
        purpose = SecureMaterialPurpose.ATTACHMENT_MASTER_KEY,
        sensitiveDataExists = storagePaths::containsSensitiveData,
    )

    fun envelopeDirectory(): File = store.envelopeDirectory()

    companion object {
        /** Convenient construction seam for a Hilt `@Provides` method. */
        fun forAndroid(context: Context): SecureMaterialManager {
            val appContext = context.applicationContext
            val namespace = InstallationNamespace(appContext.packageName)
            return SecureMaterialManager(
                SecureMaterialStore(
                    noBackupFilesDir = appContext.noBackupFilesDir,
                    installationNamespace = namespace,
                    wrappingKeyProvider = AndroidKeystoreWrappingKeyProvider(),
                ),
            )
        }
    }
}

internal fun databaseArtifactsExist(databaseFile: File): Boolean {
    val candidates = listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal"),
    )
    return candidates.any { it.exists() && (it.isDirectory || it.length() > 0L) }
}
