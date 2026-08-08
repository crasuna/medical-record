package com.loveluke.medicalrecord.app.storage

import com.loveluke.medicalrecord.core.attachment.AttachmentOrphanCleaner
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.attachment.PlaintextFileDeleter
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import com.loveluke.medicalrecord.core.database.AppDatabase
import java.util.concurrent.Executors
import javax.inject.Provider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalStorageMaintenanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `plaintext cleanup runs on owned IO dispatcher and returns failures to caller`() = runTest {
        val cache = temporaryFolder.newFolder("cache")
        var deletionThread = ""
        val registry = TemporaryPlaintextRegistry(
            cacheDirectory = cache,
            fileDeleter = PlaintextFileDeleter {
                deletionThread = Thread.currentThread().name
                false
            },
        )
        registry.cameraRootDirectory.mkdirs()
        registry.cameraRootDirectory.resolve("stale.jpg").writeBytes(byteArrayOf(1))
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "medical-record-maintenance-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val maintenance = LocalStorageMaintenance(
            temporaryPlaintextRegistry = registry,
            attachmentOrphanCleaner = AttachmentOrphanCleaner(
                AttachmentStoragePaths(temporaryFolder.newFolder("files")),
            ),
            databaseProvider = Provider<AppDatabase> {
                throw AssertionError("Database must not open during plaintext cleanup.")
            },
            ioDispatcher = dispatcher,
        )

        try {
            val report = maintenance.removeStalePlaintext()

            assertEquals(0, report.deletedFiles)
            assertEquals(1, report.failedFiles)
            assertEquals(0, report.scanFailures)
            assertTrue(deletionThread.startsWith("medical-record-maintenance-test"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
