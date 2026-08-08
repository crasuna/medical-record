package com.loveluke.medicalrecord.core.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemporaryPlaintextRegistryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val registry by lazy {
        TemporaryPlaintextRegistry(
            cacheDirectory = temporaryFolder.root,
        )
    }

    @Test
    fun `success failure and cancellation all remove registered plaintext`() {
        val completed = registry.createCameraCapture(".jpg").also { it.file.writeText("camera") }
        val failed = registry.reservePreview(".pdf").also { it.file.writeText("preview") }
        val cancelled = registry.createCameraCapture(".jpg").also { it.file.writeText("cancel") }

        assertEquals(PlaintextCleanupResult.DELETED, completed.cleanupAfterSuccess())
        assertEquals(PlaintextCleanupResult.DELETED, failed.cleanupAfterFailure())
        assertEquals(PlaintextCleanupResult.DELETED, cancelled.cleanupAfterCancellation())
        assertFalse(completed.file.exists())
        assertFalse(failed.file.exists())
        assertFalse(cancelled.file.exists())
    }

    @Test
    fun `cold start cleanup removes abandoned camera and preview plaintext`() {
        registry.createCameraCapture(".jpg").file.writeText("abandoned-camera")
        registry.reservePreview(".pdf").file.writeText("abandoned-preview")

        val report = registry.cleanupOnColdStart()

        assertEquals(2, report.deletedFiles)
        assertEquals(0, report.failedFiles)
        assertEquals(0, report.scanFailures)
        assertTrue(registry.rootDirectories.all { root -> root.walkTopDown().none { it.isFile } })
    }

    @Test
    fun `camera and preview use separate static roots`() {
        val camera = registry.createCameraCapture(".jpg")
        val preview = registry.reservePreview(".pdf")

        assertEquals(
            temporaryFolder.root.resolve("medical-record-camera/v1").canonicalFile,
            camera.file.parentFile,
        )
        assertEquals(
            temporaryFolder.root.resolve("medical-record-preview/v1").canonicalFile,
            preview.file.parentFile,
        )
        assertFalse(preview.file.canonicalPath.startsWith(registry.cameraRootDirectory.canonicalPath))
    }

    @Test
    fun `external path cannot be registered`() {
        val outside = temporaryFolder.newFile("outside.pdf")

        assertThrows(UnsafeTemporaryPlaintextPathException::class.java) {
            registry.registerExisting(TemporaryPlaintextKind.PREVIEW, outside)
        }
    }

    @Test
    fun `cold start cleanup reports unexpected nested directory instead of silently succeeding`() {
        val unexpected = registry.cameraRootDirectory.resolve("unexpected-directory")
        unexpected.mkdirs()

        val report = registry.cleanupOnColdStart()

        assertEquals(0, report.deletedFiles)
        assertEquals(1, report.failedFiles)
        assertTrue(unexpected.isDirectory)
    }

    @Test
    fun `cold start cleanup retains and reports root path with unexpected file type`() {
        registry.cameraRootDirectory.parentFile?.mkdirs()
        registry.cameraRootDirectory.writeText("not a directory")

        val report = registry.cleanupOnColdStart()

        assertEquals(0, report.deletedFiles)
        assertEquals(1, report.failedFiles)
        assertEquals("not a directory", registry.cameraRootDirectory.readText())
    }

    @Test
    fun `cold start cleanup never deletes entry classified as symlink or unknown`() {
        val classifiedRegistry = TemporaryPlaintextRegistry(
            cacheDirectory = temporaryFolder.root,
            fileDeleter = PlaintextFileDeleter {
                throw AssertionError("Unsafe entry must never reach the plaintext deleter.")
            },
            pathClassifier = TemporaryPlaintextPathClassifier { path, root ->
                if (path.fileName?.toString() == "linked.jpg") {
                    TemporaryPlaintextPathType.UNSAFE_OR_UNKNOWN
                } else {
                    NioTemporaryPlaintextPathClassifier.classify(path, root)
                }
            },
        )
        classifiedRegistry.cameraRootDirectory.mkdirs()
        val retained = classifiedRegistry.cameraRootDirectory.resolve("linked.jpg").apply {
            writeText("must remain")
        }

        val report = classifiedRegistry.cleanupOnColdStart()

        assertEquals(0, report.deletedFiles)
        assertEquals(1, report.failedFiles)
        assertEquals("must remain", retained.readText())
    }
}
