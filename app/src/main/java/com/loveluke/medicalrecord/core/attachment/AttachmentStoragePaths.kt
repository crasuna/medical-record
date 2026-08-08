package com.loveluke.medicalrecord.core.attachment

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

class UnsafeAttachmentPathException : IllegalArgumentException("Unsafe attachment storage path.")

class AttachmentRelativePath private constructor(
    val value: String,
) {
    val payloadKind: AttachmentPayloadKind
        get() = if (value.startsWith("original/")) {
            AttachmentPayloadKind.ORIGINAL
        } else {
            AttachmentPayloadKind.THUMBNAIL
        }

    companion object {
        fun original(attachmentId: UUID): AttachmentRelativePath =
            AttachmentRelativePath("original/$attachmentId.mra")

        fun thumbnail(attachmentId: UUID): AttachmentRelativePath =
            AttachmentRelativePath("thumbnail/$attachmentId.mrt")

        fun parseStored(raw: String): AttachmentRelativePath {
            if (!STORED_PATH_PATTERN.matches(raw)) throw UnsafeAttachmentPathException()
            val idText = raw.substringAfter('/').substringBeforeLast('.')
            val parsed = try {
                UUID.fromString(idText)
            } catch (_: IllegalArgumentException) {
                throw UnsafeAttachmentPathException()
            }
            if (parsed.toString() != idText) throw UnsafeAttachmentPathException()
            return AttachmentRelativePath(raw)
        }

        private val STORED_PATH_PATTERN = Regex(
            "(?:original/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mra)|" +
                "(?:thumbnail/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mrt)",
        )

        internal fun isPendingCiphertext(raw: String): Boolean {
            val parts = raw.split('/')
            if (parts.size != 2) return false
            val expectedExtension = when (parts[0]) {
                "original" -> "mra"
                "thumbnail" -> "mrt"
                else -> return false
            }
            val fileParts = parts[1].split('.')
            if (fileParts.size != 5 || fileParts[0].isNotEmpty()) return false
            if (fileParts[2] != expectedExtension || fileParts[4] != "pending") return false
            return fileParts[1].isCanonicalUuid() && fileParts[3].isCanonicalUuid()
        }

        private fun String.isCanonicalUuid(): Boolean = try {
            UUID.fromString(this).toString() == this
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}

internal class AttachmentDeletingPath private constructor(
    val value: String,
    val originalPath: AttachmentRelativePath,
    val operationId: UUID,
) {
    companion object {
        fun create(
            originalPath: AttachmentRelativePath,
            operationId: UUID,
        ): AttachmentDeletingPath {
            val directory = originalPath.value.substringBefore('/')
            val fileName = originalPath.value.substringAfter('/')
            return AttachmentDeletingPath(
                value = "$directory/.$fileName.$operationId.deleting",
                originalPath = originalPath,
                operationId = operationId,
            )
        }

        fun parseStored(raw: String): AttachmentDeletingPath {
            val parts = raw.split('/')
            if (parts.size != 2) throw UnsafeAttachmentPathException()
            val expectedExtension = when (parts[0]) {
                "original" -> "mra"
                "thumbnail" -> "mrt"
                else -> throw UnsafeAttachmentPathException()
            }
            val fileParts = parts[1].split('.')
            if (
                fileParts.size != 5 ||
                fileParts[0].isNotEmpty() ||
                fileParts[2] != expectedExtension ||
                fileParts[4] != "deleting"
            ) {
                throw UnsafeAttachmentPathException()
            }
            val attachmentId = fileParts[1].canonicalUuidOrUnsafe()
            val operationId = fileParts[3].canonicalUuidOrUnsafe()
            val original = AttachmentRelativePath.parseStored(
                "${parts[0]}/$attachmentId.$expectedExtension",
            )
            return AttachmentDeletingPath(raw, original, operationId)
        }

        private fun String.canonicalUuidOrUnsafe(): UUID {
            val parsed = try {
                UUID.fromString(this)
            } catch (_: IllegalArgumentException) {
                throw UnsafeAttachmentPathException()
            }
            if (parsed.toString() != this) throw UnsafeAttachmentPathException()
            return parsed
        }
    }
}

class AttachmentStoragePaths(
    appFilesDirectory: File,
) {
    val rootDirectory: File = File(appFilesDirectory, "medical-record-attachments/v1")

    fun resolve(relativePath: AttachmentRelativePath): File = resolveSafe(relativePath.value)

    internal fun resolve(deletingPath: AttachmentDeletingPath): File = resolveSafe(deletingPath.value)

    private fun resolveSafe(relativeValue: String): File {
        val canonicalRoot = rootDirectory.canonicalFile
        val candidate = File(canonicalRoot, relativeValue).absoluteFile.normalize()
        val parentPrefix = canonicalRoot.path + File.separator
        if (!candidate.path.startsWith(parentPrefix)) throw UnsafeAttachmentPathException()
        val canonicalParent = candidate.parentFile?.canonicalFile ?: throw UnsafeAttachmentPathException()
        if (!canonicalParent.path.startsWith(parentPrefix)) throw UnsafeAttachmentPathException()
        return candidate
    }

    fun containsSensitiveData(): Boolean {
        if (!rootDirectory.exists()) return false
        return try {
            val rootPath = rootDirectory.toPath()
            Files.walk(rootPath).use { paths ->
                paths.anyMatch { path ->
                    path != rootPath && (
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                            Files.isSymbolicLink(path)
                        )
                }
            }
        } catch (_: IOException) {
            true
        } catch (_: UncheckedIOException) {
            true
        } catch (_: SecurityException) {
            true
        }
    }
}
