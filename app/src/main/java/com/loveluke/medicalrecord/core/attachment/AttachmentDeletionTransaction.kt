package com.loveluke.medicalrecord.core.attachment

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class AttachmentDeletionRollbackState {
    NOT_REQUIRED,
    COMPLETE,
    INCOMPLETE,
}

interface AttachmentDeletionFileOps {
    fun stage(source: File, tombstone: File): Boolean

    fun restore(tombstone: File, source: File): Boolean

    fun finalize(tombstone: File): Boolean
}

internal object DefaultAttachmentDeletionFileOps : AttachmentDeletionFileOps {
    override fun stage(source: File, tombstone: File): Boolean = moveWithoutOverwrite(source, tombstone)

    override fun restore(tombstone: File, source: File): Boolean = moveWithoutOverwrite(tombstone, source)

    override fun finalize(tombstone: File): Boolean = try {
        val path = tombstone.toPath()
        when {
            Files.isSymbolicLink(path) -> false
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> true
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> false
            else -> {
                Files.delete(path)
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            }
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun moveWithoutOverwrite(source: File, target: File): Boolean {
        return try {
            val sourcePath = source.toPath()
            val targetPath = target.toPath()
            val sourceParent = source.parentFile ?: return false
            val targetParent = target.parentFile ?: return false
            if (
                sourceParent.canonicalFile != targetParent.canonicalFile ||
                Files.isSymbolicLink(sourceParent.toPath()) ||
                Files.isSymbolicLink(targetParent.toPath()) ||
                Files.isSymbolicLink(sourcePath) ||
                !Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
            ) {
                false
            } else {
                try {
                    Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(sourcePath, targetPath)
                }
                !Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS) &&
                    Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(targetPath)
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}

internal sealed interface AttachmentDeletionTransactionResult {
    data class Committed(
        val ciphertextFilesStaged: Int,
        val ciphertextFilesFinalized: Int,
        val tombstoneFilesRetained: Int,
    ) : AttachmentDeletionTransactionResult

    data class StageFailed(
        val ciphertextFilesStaged: Int,
        val rollbackState: AttachmentDeletionRollbackState,
        val tombstoneFilesRetained: Int,
    ) : AttachmentDeletionTransactionResult

    data class MetadataFailed(
        val ciphertextFilesStaged: Int,
        val rollbackState: AttachmentDeletionRollbackState,
        val tombstoneFilesRetained: Int,
    ) : AttachmentDeletionTransactionResult
}

internal class AttachmentDeletionTransaction(
    private val storagePaths: AttachmentStoragePaths,
    private val fileOps: AttachmentDeletionFileOps = DefaultAttachmentDeletionFileOps,
    private val operationIdFactory: () -> UUID = UUID::randomUUID,
) {
    suspend fun execute(
        relativePaths: List<AttachmentRelativePath>,
        deleteMetadata: suspend () -> Boolean,
    ): AttachmentDeletionTransactionResult {
        val targets = prepareTargets(relativePaths)
            ?: return AttachmentDeletionTransactionResult.StageFailed(
                ciphertextFilesStaged = 0,
                rollbackState = AttachmentDeletionRollbackState.NOT_REQUIRED,
                tombstoneFilesRetained = 0,
            )
        val staged = mutableListOf<DeletionTarget>()
        for (target in targets) {
            if (target.state() == DeletionTargetState.BOTH_ABSENT) continue
            val stageSucceeded = try {
                fileOps.stage(target.source, target.tombstone)
            } catch (_: Exception) {
                false
            }
            val stateAfterStage = target.state()
            if (stateAfterStage.hasTombstone && target !in staged) staged += target
            if (!stageSucceeded || stateAfterStage != DeletionTargetState.TOMBSTONE_ONLY) {
                val rollback = rollback(staged)
                return AttachmentDeletionTransactionResult.StageFailed(
                    ciphertextFilesStaged = staged.size,
                    rollbackState = rollback.state,
                    tombstoneFilesRetained = rollback.tombstoneFilesRetained,
                )
            }
        }

        val metadataDeleted = try {
            deleteMetadata()
        } catch (cancellation: CancellationException) {
            rollback(staged)
            throw cancellation
        } catch (_: Exception) {
            false
        }
        if (!metadataDeleted) {
            val rollback = rollback(staged)
            return AttachmentDeletionTransactionResult.MetadataFailed(
                ciphertextFilesStaged = staged.size,
                rollbackState = rollback.state,
                tombstoneFilesRetained = rollback.tombstoneFilesRetained,
            )
        }

        var finalized = 0
        var retained = 0
        staged.forEach { target ->
            val finalizedByOperation = try {
                fileOps.finalize(target.tombstone)
            } catch (_: Exception) {
                false
            }
            val state = target.state()
            if (
                (finalizedByOperation || !state.hasTombstone) &&
                state != DeletionTargetState.ORIGINAL_ONLY &&
                state != DeletionTargetState.BOTH_PRESENT &&
                state != DeletionTargetState.INVALID
            ) {
                finalized += 1
            } else {
                if (state.hasTombstone) retained += 1
            }
        }
        return AttachmentDeletionTransactionResult.Committed(
            ciphertextFilesStaged = staged.size,
            ciphertextFilesFinalized = finalized,
            tombstoneFilesRetained = retained,
        )
    }

    private fun prepareTargets(relativePaths: List<AttachmentRelativePath>): List<DeletionTarget>? {
        if (relativePaths.map(AttachmentRelativePath::value).distinct().size != relativePaths.size) {
            return null
        }
        val operationId = try {
            operationIdFactory()
        } catch (_: RuntimeException) {
            return null
        }
        val targets = mutableListOf<DeletionTarget>()
        for (relativePath in relativePaths) {
            val deletingPath = AttachmentDeletingPath.create(relativePath, operationId)
            val target = try {
                DeletionTarget(
                    source = storagePaths.resolve(relativePath),
                    tombstone = storagePaths.resolve(deletingPath),
                )
            } catch (_: RuntimeException) {
                return null
            }
            val valid = try {
                val sourceParent = target.source.parentFile ?: return null
                val tombstoneParent = target.tombstone.parentFile ?: return null
                sourceParent.canonicalFile == tombstoneParent.canonicalFile &&
                    !Files.isSymbolicLink(sourceParent.toPath()) &&
                    !Files.isSymbolicLink(tombstoneParent.toPath()) &&
                    target.state() in setOf(
                        DeletionTargetState.ORIGINAL_ONLY,
                        DeletionTargetState.BOTH_ABSENT,
                    )
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: RuntimeException) {
                false
            }
            if (!valid) {
                return null
            }
            targets += target
        }
        return targets
    }

    private fun rollback(staged: List<DeletionTarget>): RollbackSummary {
        if (staged.isEmpty()) {
            return RollbackSummary(AttachmentDeletionRollbackState.NOT_REQUIRED, 0)
        }
        var complete = true
        staged.asReversed().forEach { target ->
            when (target.state()) {
                DeletionTargetState.ORIGINAL_ONLY -> Unit
                DeletionTargetState.TOMBSTONE_ONLY -> {
                    val restored = try {
                        fileOps.restore(target.tombstone, target.source)
                    } catch (_: Exception) {
                        false
                    }
                    if (!restored || target.state() != DeletionTargetState.ORIGINAL_ONLY) {
                        complete = false
                    }
                }

                DeletionTargetState.BOTH_ABSENT,
                DeletionTargetState.BOTH_PRESENT,
                DeletionTargetState.INVALID,
                -> complete = false
            }
        }
        val retained = staged.count { it.state().hasTombstone }
        return RollbackSummary(
            state = if (complete) {
                AttachmentDeletionRollbackState.COMPLETE
            } else {
                AttachmentDeletionRollbackState.INCOMPLETE
            },
            tombstoneFilesRetained = retained,
        )
    }

    private data class DeletionTarget(
        val source: File,
        val tombstone: File,
    ) {
        fun state(): DeletionTargetState {
            val sourceState = source.deletionFileState()
            val tombstoneState = tombstone.deletionFileState()
            if (sourceState == FileState.INVALID || tombstoneState == FileState.INVALID) {
                return DeletionTargetState.INVALID
            }
            return when {
                sourceState == FileState.REGULAR && tombstoneState == FileState.ABSENT ->
                    DeletionTargetState.ORIGINAL_ONLY

                sourceState == FileState.ABSENT && tombstoneState == FileState.REGULAR ->
                    DeletionTargetState.TOMBSTONE_ONLY

                sourceState == FileState.ABSENT && tombstoneState == FileState.ABSENT ->
                    DeletionTargetState.BOTH_ABSENT

                else -> DeletionTargetState.BOTH_PRESENT
            }
        }
    }

    private data class RollbackSummary(
        val state: AttachmentDeletionRollbackState,
        val tombstoneFilesRetained: Int,
    )

    private enum class DeletionTargetState(val hasTombstone: Boolean) {
        ORIGINAL_ONLY(false),
        TOMBSTONE_ONLY(true),
        BOTH_ABSENT(false),
        BOTH_PRESENT(true),
        INVALID(true),
    }

}

private enum class FileState {
    ABSENT,
    REGULAR,
    INVALID,
}

private fun File.deletionFileState(): FileState = try {
    val path = toPath()
    when {
        Files.isSymbolicLink(path) -> FileState.INVALID
        !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> FileState.ABSENT
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> FileState.REGULAR
        else -> FileState.INVALID
    }
} catch (_: RuntimeException) {
    FileState.INVALID
}
