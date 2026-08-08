package com.loveluke.medicalrecord.core.security

import android.content.Context
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-use capability that the recovery UI may issue only after explicit second confirmation.
 */
class SensitiveDataClearAuthorization private constructor() {
    private val consumed = AtomicBoolean(false)

    internal fun consume(): Boolean = consumed.compareAndSet(false, true)

    companion object {
        fun afterExplicitSecondConfirmation(): SensitiveDataClearAuthorization =
            SensitiveDataClearAuthorization()
    }
}

data class SensitiveDataClearReport(
    val authorizationAccepted: Boolean,
    val databaseClosed: Boolean,
    val deletedDatabaseArtifacts: Int,
    val deletedAttachmentFiles: Int,
    val deletedEnvelopeFiles: Int,
    val deletedPlaintextFiles: Int,
    val deletedSharedPreferenceFiles: Int,
    val failedDeletes: Int,
    val wrappingKeyDeleted: Boolean,
    val wrappingKeyDeletionFailed: Boolean,
    val requiresRetry: Boolean,
    val processRestartRequired: Boolean,
    val reminderArtifactsCleared: Boolean = false,
    val reminderArtifactClearFailed: Boolean = false,
)

fun interface SensitiveFileDeleter {
    fun delete(path: Path): Boolean
}

/**
 * Clears only the current installation's explicitly whitelisted sensitive storage.
 *
 * The database is closed before deletion begins. The Keystore alias is removed last so no step can
 * accidentally provision a replacement key or touch signing material outside the app sandbox.
 */
class SensitiveDataClearCoordinator private constructor(
    private val databaseArtifacts: List<File>,
    private val attachmentRoot: File,
    private val envelopeRoot: File,
    private val plaintextRoots: Set<File>,
    private val sharedPreferencesRoot: File,
    private val wrappingKeyProvider: WrappingKeyProvider,
    private val wrappingKeyAlias: String,
    private val clearReminderArtifacts: () -> Boolean,
    private val closeDatabase: () -> Unit,
    private val fileDeleter: SensitiveFileDeleter,
) {
    @Synchronized
    fun clear(authorization: SensitiveDataClearAuthorization): SensitiveDataClearReport {
        if (!authorization.consume()) return rejectedAuthorizationReport()

        val reminderArtifactsCleared = try {
            clearReminderArtifacts()
        } catch (_: RuntimeException) {
            false
        }
        if (!reminderArtifactsCleared) return reminderArtifactFailureReport()

        try {
            closeDatabase()
        } catch (_: RuntimeException) {
            return databaseCloseFailureReport(reminderArtifactsCleared = true)
        }

        val totals = DeletionTotals()
        databaseArtifacts.forEach { artifact ->
            totals.deletedDatabaseArtifacts += deleteExactFile(artifact, totals)
        }
        totals.deletedAttachmentFiles += deleteTree(attachmentRoot, totals)
        totals.deletedEnvelopeFiles += deleteTree(envelopeRoot, totals)
        plaintextRoots.forEach { root ->
            totals.deletedPlaintextFiles += deleteTree(root, totals)
        }
        totals.deletedSharedPreferenceFiles += deleteTree(sharedPreferencesRoot, totals)

        var wrappingKeyDeleted = false
        var wrappingKeyDeletionFailed = false
        try {
            wrappingKeyDeleted = wrappingKeyProvider.delete(wrappingKeyAlias)
        } catch (_: Exception) {
            wrappingKeyDeletionFailed = true
        }

        val requiresRetry = totals.failedDeletes > 0 || wrappingKeyDeletionFailed
        return SensitiveDataClearReport(
            authorizationAccepted = true,
            databaseClosed = true,
            deletedDatabaseArtifacts = totals.deletedDatabaseArtifacts,
            deletedAttachmentFiles = totals.deletedAttachmentFiles,
            deletedEnvelopeFiles = totals.deletedEnvelopeFiles,
            deletedPlaintextFiles = totals.deletedPlaintextFiles,
            deletedSharedPreferenceFiles = totals.deletedSharedPreferenceFiles,
            failedDeletes = totals.failedDeletes,
            wrappingKeyDeleted = wrappingKeyDeleted,
            wrappingKeyDeletionFailed = wrappingKeyDeletionFailed,
            requiresRetry = requiresRetry,
            processRestartRequired = true,
            reminderArtifactsCleared = true,
            reminderArtifactClearFailed = false,
        )
    }

    private fun deleteExactFile(file: File, totals: DeletionTotals): Int {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            totals.failedDeletes += 1
            return 0
        }
        return try {
            if (fileDeleter.delete(path)) {
                1
            } else {
                totals.failedDeletes += 1
                0
            }
        } catch (_: IOException) {
            totals.failedDeletes += 1
            0
        } catch (_: SecurityException) {
            totals.failedDeletes += 1
            0
        } catch (_: RuntimeException) {
            totals.failedDeletes += 1
            0
        }
    }

    private fun deleteTree(root: File, totals: DeletionTotals): Int {
        val rootPath = root.toPath()
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return 0
        var deletedFiles = 0
        try {
            Files.walk(rootPath).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    val isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    try {
                        if (fileDeleter.delete(path)) {
                            if (!isDirectory) deletedFiles += 1
                        } else {
                            totals.failedDeletes += 1
                        }
                    } catch (_: IOException) {
                        totals.failedDeletes += 1
                    } catch (_: SecurityException) {
                        totals.failedDeletes += 1
                    } catch (_: RuntimeException) {
                        totals.failedDeletes += 1
                    }
                }
            }
        } catch (_: IOException) {
            totals.failedDeletes += 1
        } catch (_: UncheckedIOException) {
            totals.failedDeletes += 1
        } catch (_: SecurityException) {
            totals.failedDeletes += 1
        }
        return deletedFiles
    }

    private class DeletionTotals {
        var deletedDatabaseArtifacts: Int = 0
        var deletedAttachmentFiles: Int = 0
        var deletedEnvelopeFiles: Int = 0
        var deletedPlaintextFiles: Int = 0
        var deletedSharedPreferenceFiles: Int = 0
        var failedDeletes: Int = 0
    }

    companion object {
        fun forAndroid(
            context: Context,
            databaseFile: File,
            attachmentStoragePaths: AttachmentStoragePaths,
            plaintextRegistry: TemporaryPlaintextRegistry,
            closeDatabase: () -> Unit,
            clearReminderArtifacts: () -> Boolean = { true },
        ): SensitiveDataClearCoordinator {
            val appContext = context.applicationContext
            val namespace = InstallationNamespace(appContext.packageName)
            val databasesRoot = File(appContext.dataDir, "databases").canonicalFile
            require(databaseFile.canonicalFile.parentFile == databasesRoot) {
                "Database clear target is outside the app database directory."
            }
            require(isDescendant(appContext.filesDir, attachmentStoragePaths.rootDirectory)) {
                "Attachment clear target is outside the app files directory."
            }
            plaintextRegistry.rootDirectories.forEach { root ->
                require(isDescendant(appContext.cacheDir, root)) {
                    "Plaintext clear target is outside the app cache directory."
                }
            }
            val envelopeRoot = SecureMaterialManager.forAndroid(appContext).envelopeDirectory()
            require(isDescendant(appContext.noBackupFilesDir, envelopeRoot)) {
                "Envelope clear target is outside the app no-backup directory."
            }
            return SensitiveDataClearCoordinator(
                databaseArtifacts = databaseArtifacts(databaseFile),
                attachmentRoot = attachmentStoragePaths.rootDirectory,
                envelopeRoot = envelopeRoot,
                plaintextRoots = plaintextRegistry.rootDirectories,
                sharedPreferencesRoot = File(appContext.dataDir, "shared_prefs"),
                wrappingKeyProvider = AndroidKeystoreWrappingKeyProvider(),
                wrappingKeyAlias = namespace.wrappingKeyAlias,
                clearReminderArtifacts = clearReminderArtifacts,
                closeDatabase = closeDatabase,
                fileDeleter = SensitiveFileDeleter { path -> Files.deleteIfExists(path) },
            )
        }

        internal fun forTesting(
            databaseFile: File,
            attachmentRoot: File,
            envelopeRoot: File,
            plaintextRoots: Set<File>,
            sharedPreferencesRoot: File,
            wrappingKeyProvider: WrappingKeyProvider,
            wrappingKeyAlias: String,
            closeDatabase: () -> Unit,
            clearReminderArtifacts: () -> Boolean = { true },
            fileDeleter: SensitiveFileDeleter = SensitiveFileDeleter { path ->
                Files.deleteIfExists(path)
            },
        ): SensitiveDataClearCoordinator = SensitiveDataClearCoordinator(
            databaseArtifacts = databaseArtifacts(databaseFile),
            attachmentRoot = attachmentRoot,
            envelopeRoot = envelopeRoot,
            plaintextRoots = plaintextRoots,
            sharedPreferencesRoot = sharedPreferencesRoot,
            wrappingKeyProvider = wrappingKeyProvider,
            wrappingKeyAlias = wrappingKeyAlias,
            clearReminderArtifacts = clearReminderArtifacts,
            closeDatabase = closeDatabase,
            fileDeleter = fileDeleter,
        )

        private fun databaseArtifacts(databaseFile: File): List<File> = listOf(
            databaseFile,
            File(databaseFile.path + "-wal"),
            File(databaseFile.path + "-shm"),
            File(databaseFile.path + "-journal"),
        )

        private fun isDescendant(parent: File, child: File): Boolean {
            val parentPath = parent.canonicalFile.toPath()
            val childPath = child.canonicalFile.toPath()
            return childPath != parentPath && childPath.startsWith(parentPath)
        }

        private fun rejectedAuthorizationReport(): SensitiveDataClearReport =
            SensitiveDataClearReport(
                authorizationAccepted = false,
                databaseClosed = false,
                deletedDatabaseArtifacts = 0,
                deletedAttachmentFiles = 0,
                deletedEnvelopeFiles = 0,
                deletedPlaintextFiles = 0,
                deletedSharedPreferenceFiles = 0,
                failedDeletes = 0,
                wrappingKeyDeleted = false,
                wrappingKeyDeletionFailed = false,
                requiresRetry = false,
                processRestartRequired = false,
            )

        private fun databaseCloseFailureReport(
            reminderArtifactsCleared: Boolean,
        ): SensitiveDataClearReport =
            SensitiveDataClearReport(
                authorizationAccepted = true,
                databaseClosed = false,
                deletedDatabaseArtifacts = 0,
                deletedAttachmentFiles = 0,
                deletedEnvelopeFiles = 0,
                deletedPlaintextFiles = 0,
                deletedSharedPreferenceFiles = 0,
                failedDeletes = 0,
                wrappingKeyDeleted = false,
                wrappingKeyDeletionFailed = false,
                requiresRetry = true,
                processRestartRequired = false,
                reminderArtifactsCleared = reminderArtifactsCleared,
                reminderArtifactClearFailed = false,
            )

        private fun reminderArtifactFailureReport(): SensitiveDataClearReport =
            SensitiveDataClearReport(
                authorizationAccepted = true,
                databaseClosed = false,
                deletedDatabaseArtifacts = 0,
                deletedAttachmentFiles = 0,
                deletedEnvelopeFiles = 0,
                deletedPlaintextFiles = 0,
                deletedSharedPreferenceFiles = 0,
                failedDeletes = 0,
                wrappingKeyDeleted = false,
                wrappingKeyDeletionFailed = false,
                requiresRetry = true,
                processRestartRequired = false,
                reminderArtifactsCleared = false,
                reminderArtifactClearFailed = true,
            )
    }
}
