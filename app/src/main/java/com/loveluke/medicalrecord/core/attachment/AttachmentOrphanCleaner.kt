package com.loveluke.medicalrecord.core.attachment

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class AttachmentOrphanCleanupReport(
    val retainedReferenced: Int,
    val deletedOrphans: Int,
    val deletedPendingFiles: Int,
    val restoredDeletingFiles: Int,
    val deletedDeletingFiles: Int,
    val deletingConflicts: Int,
    val failedDeletingOperations: Int,
    val failedDeletes: Int,
    val ignoredUnrecognizedFiles: Int,
    val scanFailed: Boolean,
)

fun interface EncryptedFileDeleter {
    fun delete(file: File): Boolean
}

/**
 * Reconciles encrypted attachment files against the database snapshot supplied by the caller.
 *
 * Normal unreferenced ciphertext and interrupted `.pending` writes are deleted. A `.deleting`
 * tombstone is restored only when its original path is still referenced and absent; after a
 * committed metadata deletion it is finalized instead. Unknown files and conflicts are retained.
 */
class AttachmentOrphanCleaner(
    private val storagePaths: AttachmentStoragePaths,
    private val fileDeleter: EncryptedFileDeleter = EncryptedFileDeleter(File::delete),
    private val deletionFileOps: AttachmentDeletionFileOps = DefaultAttachmentDeletionFileOps,
) {
    fun clean(referencedRelativePaths: Set<AttachmentRelativePath>): AttachmentOrphanCleanupReport {
        val root = storagePaths.rootDirectory
        if (!root.exists()) return Counters().toReport(scanFailed = false)
        val referencedValues = referencedRelativePaths.mapTo(mutableSetOf(), AttachmentRelativePath::value)
        val counters = Counters()

        return try {
            val rootPath = root.canonicalFile.toPath()
            val entries = mutableListOf<Path>()
            Files.walk(rootPath).use { paths ->
                paths.filter { it != rootPath }.forEach(entries::add)
            }
            val regularFiles = mutableListOf<ScannedFile>()
            entries.forEach { path ->
                when {
                    Files.isSymbolicLink(path) -> counters.ignored += 1
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        val relative = rootPath.relativize(path).joinToString("/")
                        regularFiles += ScannedFile(path.toFile(), relative)
                    }

                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Unit
                    else -> counters.ignored += 1
                }
            }

            val deletingFiles = mutableListOf<Pair<ScannedFile, AttachmentDeletingPath>>()
            val remainingFiles = mutableListOf<ScannedFile>()
            regularFiles.forEach { scanned ->
                val deleting = try {
                    AttachmentDeletingPath.parseStored(scanned.relative)
                } catch (_: UnsafeAttachmentPathException) {
                    null
                }
                if (deleting == null) {
                    remainingFiles += scanned
                } else {
                    deletingFiles += scanned to deleting
                }
            }

            deletingFiles.forEach { (scanned, deleting) ->
                reconcileDeleting(scanned.file, deleting, referencedValues, counters)
            }
            remainingFiles.forEach { scanned ->
                reconcileRegular(scanned, referencedValues, counters)
            }
            counters.toReport(scanFailed = false)
        } catch (_: IOException) {
            counters.toReport(scanFailed = true)
        } catch (_: UncheckedIOException) {
            counters.toReport(scanFailed = true)
        } catch (_: SecurityException) {
            counters.toReport(scanFailed = true)
        } catch (_: RuntimeException) {
            counters.toReport(scanFailed = true)
        }
    }

    private fun reconcileDeleting(
        scannedFile: File,
        deletingPath: AttachmentDeletingPath,
        referencedValues: Set<String>,
        counters: Counters,
    ) {
        val tombstone = storagePaths.resolve(deletingPath)
        if (scannedFile.canonicalFile != tombstone.canonicalFile || Files.isSymbolicLink(tombstone.toPath())) {
            counters.ignored += 1
            return
        }
        val original = storagePaths.resolve(deletingPath.originalPath)
        if (deletingPath.originalPath.value in referencedValues) {
            if (Files.exists(original.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                counters.deletingConflicts += 1
                return
            }
            val restored = deletionFileOps.restore(tombstone, original)
            if (
                restored &&
                Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(original.toPath()) &&
                !Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                counters.restoredDeleting += 1
            } else {
                counters.failedDeleting += 1
            }
        } else {
            val finalized = deletionFileOps.finalize(tombstone)
            if (finalized && !Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                counters.deletedDeleting += 1
            } else {
                counters.failedDeleting += 1
            }
        }
    }

    private fun reconcileRegular(
        scanned: ScannedFile,
        referencedValues: Set<String>,
        counters: Counters,
    ) {
        val pending = AttachmentRelativePath.isPendingCiphertext(scanned.relative)
        val parsed = if (pending) {
            null
        } else {
            try {
                AttachmentRelativePath.parseStored(scanned.relative)
            } catch (_: UnsafeAttachmentPathException) {
                counters.ignored += 1
                null
            }
        }
        if (pending) {
            if (fileDeleter.delete(scanned.file)) {
                counters.deletedPending += 1
            } else {
                counters.failedDeletes += 1
            }
        } else if (parsed != null) {
            if (parsed.value in referencedValues) {
                counters.retained += 1
            } else if (fileDeleter.delete(scanned.file)) {
                counters.deleted += 1
            } else {
                counters.failedDeletes += 1
            }
        }
    }

    private data class ScannedFile(
        val file: File,
        val relative: String,
    )

    private class Counters {
        var retained = 0
        var deleted = 0
        var deletedPending = 0
        var restoredDeleting = 0
        var deletedDeleting = 0
        var deletingConflicts = 0
        var failedDeleting = 0
        var failedDeletes = 0
        var ignored = 0

        fun toReport(scanFailed: Boolean) = AttachmentOrphanCleanupReport(
            retainedReferenced = retained,
            deletedOrphans = deleted,
            deletedPendingFiles = deletedPending,
            restoredDeletingFiles = restoredDeleting,
            deletedDeletingFiles = deletedDeleting,
            deletingConflicts = deletingConflicts,
            failedDeletingOperations = failedDeleting,
            failedDeletes = failedDeletes,
            ignoredUnrecognizedFiles = ignored,
            scanFailed = scanFailed,
        )
    }
}
