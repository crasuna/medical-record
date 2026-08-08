package com.loveluke.medicalrecord.core.attachment

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class TemporaryPlaintextKind(internal val directoryName: String) {
    CAMERA("medical-record-camera/v1"),
    PREVIEW("medical-record-preview/v1"),
}

enum class PlaintextCleanupResult {
    DELETED,
    ALREADY_ABSENT,
    FAILED,
    IN_USE,
}

data class PlaintextColdStartCleanupReport(
    val deletedFiles: Int,
    val failedFiles: Int,
    val scanFailures: Int = 0,
)

class UnsafeTemporaryPlaintextPathException : IllegalArgumentException("Unsafe temporary plaintext path.")

fun interface PlaintextFileDeleter {
    fun delete(file: File): Boolean
}

internal enum class TemporaryPlaintextPathType {
    ROOT,
    REGULAR_FILE,
    DIRECTORY,
    UNSAFE_OR_UNKNOWN,
}

internal fun interface TemporaryPlaintextPathClassifier {
    fun classify(path: java.nio.file.Path, root: java.nio.file.Path): TemporaryPlaintextPathType
}

internal object NioTemporaryPlaintextPathClassifier : TemporaryPlaintextPathClassifier {
    override fun classify(
        path: java.nio.file.Path,
        root: java.nio.file.Path,
    ): TemporaryPlaintextPathType = when {
        Files.isSymbolicLink(path) -> TemporaryPlaintextPathType.UNSAFE_OR_UNKNOWN
        path == root && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ->
            TemporaryPlaintextPathType.ROOT

        path == root -> TemporaryPlaintextPathType.UNSAFE_OR_UNKNOWN
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
            TemporaryPlaintextPathType.REGULAR_FILE

        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> TemporaryPlaintextPathType.DIRECTORY
        else -> TemporaryPlaintextPathType.UNSAFE_OR_UNKNOWN
    }
}

class TemporaryPlaintextHandle internal constructor(
    val file: File,
    private val fileDeleter: PlaintextFileDeleter,
) : AutoCloseable {
    private val cleaned = AtomicBoolean(false)

    fun cleanupAfterSuccess(): PlaintextCleanupResult = cleanup()

    fun cleanupAfterFailure(): PlaintextCleanupResult = cleanup()

    fun cleanupAfterCancellation(): PlaintextCleanupResult = cleanup()

    override fun close() {
        cleanup()
    }

    private fun cleanup(): PlaintextCleanupResult {
        if (!cleaned.compareAndSet(false, true)) return PlaintextCleanupResult.ALREADY_ABSENT
        if (!file.exists()) return PlaintextCleanupResult.ALREADY_ABSENT
        return if (fileDeleter.delete(file)) {
            PlaintextCleanupResult.DELETED
        } else {
            cleaned.set(false)
            PlaintextCleanupResult.FAILED
        }
    }
}

/** Owns the only cache subdirectories in which temporary plaintext may exist. */
class TemporaryPlaintextRegistry internal constructor(
    cacheDirectory: File,
    private val fileDeleter: PlaintextFileDeleter,
    private val pathClassifier: TemporaryPlaintextPathClassifier,
) {
    constructor(
        cacheDirectory: File,
        fileDeleter: PlaintextFileDeleter = PlaintextFileDeleter(File::delete),
    ) : this(
        cacheDirectory,
        fileDeleter,
        NioTemporaryPlaintextPathClassifier,
    )

    val cameraRootDirectory: File = File(cacheDirectory, TemporaryPlaintextKind.CAMERA.directoryName)
    val previewRootDirectory: File = File(cacheDirectory, TemporaryPlaintextKind.PREVIEW.directoryName)

    val rootDirectories: Set<File> = setOf(cameraRootDirectory, previewRootDirectory)

    fun createCameraCapture(extension: String): TemporaryPlaintextHandle {
        val file = reserve(TemporaryPlaintextKind.CAMERA, extension)
        if (!file.createNewFile()) throw IOException("Unable to reserve camera plaintext.")
        return TemporaryPlaintextHandle(file, fileDeleter)
    }

    fun reservePreview(extension: String): TemporaryPlaintextHandle =
        TemporaryPlaintextHandle(reserve(TemporaryPlaintextKind.PREVIEW, extension), fileDeleter)

    fun registerExisting(
        kind: TemporaryPlaintextKind,
        file: File,
    ): TemporaryPlaintextHandle {
        val expectedDirectory = kindDirectory(kind).canonicalFile
        val canonicalFile = file.canonicalFile
        if (canonicalFile.parentFile != expectedDirectory) throw UnsafeTemporaryPlaintextPathException()
        return TemporaryPlaintextHandle(canonicalFile, fileDeleter)
    }

    fun cleanupOnColdStart(): PlaintextColdStartCleanupReport {
        var deleted = 0
        var failed = 0
        var scanFailures = 0
        rootDirectories.forEach { rootDirectory ->
            val rootPath = rootDirectory.toPath()
            try {
                if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) return@forEach
                Files.walk(rootPath).use { paths ->
                    paths.forEach { path ->
                        when (pathClassifier.classify(path, rootPath)) {
                            TemporaryPlaintextPathType.REGULAR_FILE -> {
                                try {
                                    if (fileDeleter.delete(path.toFile())) deleted += 1 else failed += 1
                                } catch (_: RuntimeException) {
                                    failed += 1
                                }
                            }

                            TemporaryPlaintextPathType.ROOT -> Unit
                            TemporaryPlaintextPathType.DIRECTORY,
                            TemporaryPlaintextPathType.UNSAFE_OR_UNKNOWN,
                            -> failed += 1
                        }
                    }
                }
            } catch (_: IOException) {
                scanFailures += 1
            } catch (_: UncheckedIOException) {
                scanFailures += 1
            } catch (_: SecurityException) {
                scanFailures += 1
            }
        }
        return PlaintextColdStartCleanupReport(deleted, failed, scanFailures)
    }

    private fun reserve(kind: TemporaryPlaintextKind, extension: String): File {
        require(EXTENSION_PATTERN.matches(extension)) { "Unsupported temporary file extension." }
        val directory = kindDirectory(kind)
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Temporary plaintext storage is unavailable.")
        }
        val file = File(directory, "${UUID.randomUUID()}$extension").canonicalFile
        if (file.parentFile != directory.canonicalFile || file.exists()) {
            throw UnsafeTemporaryPlaintextPathException()
        }
        return file
    }

    private fun kindDirectory(kind: TemporaryPlaintextKind): File =
        when (kind) {
            TemporaryPlaintextKind.CAMERA -> cameraRootDirectory
            TemporaryPlaintextKind.PREVIEW -> previewRootDirectory
        }

    private companion object {
        val EXTENSION_PATTERN = Regex("\\.[A-Za-z0-9]{1,8}")
    }
}
