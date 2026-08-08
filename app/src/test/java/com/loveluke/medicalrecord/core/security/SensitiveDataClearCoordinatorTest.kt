package com.loveluke.medicalrecord.core.security

import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import java.io.File
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SensitiveDataClearCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `authorized clear closes database first deletes only whitelist and removes alias last`() {
        val appRoot = temporaryFolder.newFolder("app")
        val database = appRoot.resolve("databases/medical-record.db")
        val databaseArtifacts = databaseArtifactsForTest(database)
        databaseArtifacts.forEachIndexed { index, file ->
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(index.toByte()))
        }
        val attachments = AttachmentStoragePaths(appRoot.resolve("files"))
        attachments.rootDirectory.resolve("original/cipher.mra").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val envelopes = appRoot.resolve("no_backup/medical-record-security/package")
        envelopes.resolve("database.envelope").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
        }
        val plaintext = TemporaryPlaintextRegistry(appRoot.resolve("cache"))
        plaintext.createCameraCapture(".jpg").file.writeBytes(byteArrayOf(3))
        plaintext.reservePreview(".pdf").file.writeBytes(byteArrayOf(4))
        val sharedPreferences = appRoot.resolve("shared_prefs")
        sharedPreferences.resolve("settings.xml").apply {
            parentFile?.mkdirs()
            writeText("sensitive")
        }
        val externalSigningKey = temporaryFolder.newFile("release.jks").apply { writeText("keep") }
        val events = mutableListOf<String>()
        val wrappingKeys = RecordingWrappingKeyProvider(events)
        val coordinator = SensitiveDataClearCoordinator.forTesting(
            databaseFile = database,
            attachmentRoot = attachments.rootDirectory,
            envelopeRoot = envelopes,
            plaintextRoots = plaintext.rootDirectories,
            sharedPreferencesRoot = sharedPreferences,
            wrappingKeyProvider = wrappingKeys,
            wrappingKeyAlias = "com.loveluke.medicalrecord.wrapping.v1",
            clearReminderArtifacts = {
                events += "reminder-artifacts-cleared"
                true
            },
            closeDatabase = { events += "database-closed" },
        )

        val report = coordinator.clear(
            SensitiveDataClearAuthorization.afterExplicitSecondConfirmation(),
        )

        assertTrue(report.authorizationAccepted)
        assertTrue(report.databaseClosed)
        assertEquals(4, report.deletedDatabaseArtifacts)
        assertEquals(1, report.deletedAttachmentFiles)
        assertEquals(1, report.deletedEnvelopeFiles)
        assertEquals(2, report.deletedPlaintextFiles)
        assertEquals(1, report.deletedSharedPreferenceFiles)
        assertTrue(report.wrappingKeyDeleted)
        assertFalse(report.requiresRetry)
        assertTrue(report.processRestartRequired)
        assertTrue(report.reminderArtifactsCleared)
        assertFalse(report.reminderArtifactClearFailed)
        assertTrue(databaseArtifacts.none(File::exists))
        assertFalse(attachments.rootDirectory.exists())
        assertFalse(envelopes.exists())
        assertTrue(externalSigningKey.exists())
        assertEquals("reminder-artifacts-cleared", events.first())
        assertEquals("database-closed", events[1])
        assertEquals("alias-deleted", events.last())
    }

    @Test
    fun `authorization token is single use`() {
        val root = temporaryFolder.newFolder("single-use")
        val events = mutableListOf<String>()
        val coordinator = minimalCoordinator(root, events)
        val authorization = SensitiveDataClearAuthorization.afterExplicitSecondConfirmation()

        val first = coordinator.clear(authorization)
        val second = coordinator.clear(authorization)

        assertTrue(first.authorizationAccepted)
        assertFalse(second.authorizationAccepted)
        assertEquals(1, events.count { it == "database-closed" })
    }

    @Test
    fun `database close failure aborts deletion and preserves wrapping key`() {
        val root = temporaryFolder.newFolder("close-failure")
        val database = root.resolve("medical-record.db").apply { writeBytes(byteArrayOf(1)) }
        val events = mutableListOf<String>()
        val wrappingKeys = RecordingWrappingKeyProvider(events)
        val coordinator = SensitiveDataClearCoordinator.forTesting(
            databaseFile = database,
            attachmentRoot = root.resolve("attachments"),
            envelopeRoot = root.resolve("envelopes"),
            plaintextRoots = setOf(root.resolve("camera"), root.resolve("preview")),
            sharedPreferencesRoot = root.resolve("shared_prefs"),
            wrappingKeyProvider = wrappingKeys,
            wrappingKeyAlias = "com.loveluke.medicalrecord.wrapping.v1",
            closeDatabase = { throw IllegalStateException("still open") },
        )

        val report = coordinator.clear(
            SensitiveDataClearAuthorization.afterExplicitSecondConfirmation(),
        )

        assertTrue(report.authorizationAccepted)
        assertFalse(report.databaseClosed)
        assertTrue(report.requiresRetry)
        assertFalse(report.processRestartRequired)
        assertTrue(database.exists())
        assertFalse(report.wrappingKeyDeleted)
        assertFalse(events.contains("alias-deleted"))
    }

    @Test
    fun `reminder artifact failure aborts before database or key deletion`() {
        val root = temporaryFolder.newFolder("reminder-artifact-failure")
        val database = root.resolve("medical-record.db").apply { writeBytes(byteArrayOf(1)) }
        val events = mutableListOf<String>()
        val coordinator = SensitiveDataClearCoordinator.forTesting(
            databaseFile = database,
            attachmentRoot = root.resolve("attachments"),
            envelopeRoot = root.resolve("envelopes"),
            plaintextRoots = setOf(root.resolve("camera"), root.resolve("preview")),
            sharedPreferencesRoot = root.resolve("shared_prefs"),
            wrappingKeyProvider = RecordingWrappingKeyProvider(events),
            wrappingKeyAlias = "com.loveluke.medicalrecord.wrapping.v1",
            clearReminderArtifacts = {
                events += "reminder-artifact-failure"
                false
            },
            closeDatabase = { events += "database-closed" },
        )

        val report = coordinator.clear(
            SensitiveDataClearAuthorization.afterExplicitSecondConfirmation(),
        )

        assertTrue(report.authorizationAccepted)
        assertFalse(report.reminderArtifactsCleared)
        assertTrue(report.reminderArtifactClearFailed)
        assertFalse(report.databaseClosed)
        assertTrue(report.requiresRetry)
        assertFalse(report.processRestartRequired)
        assertTrue(database.exists())
        assertEquals(listOf("reminder-artifact-failure"), events)
    }

    private fun minimalCoordinator(
        root: File,
        events: MutableList<String>,
    ): SensitiveDataClearCoordinator = SensitiveDataClearCoordinator.forTesting(
        databaseFile = root.resolve("medical-record.db"),
        attachmentRoot = root.resolve("attachments"),
        envelopeRoot = root.resolve("envelopes"),
        plaintextRoots = setOf(root.resolve("camera"), root.resolve("preview")),
        sharedPreferencesRoot = root.resolve("shared_prefs"),
        wrappingKeyProvider = RecordingWrappingKeyProvider(events),
        wrappingKeyAlias = "com.loveluke.medicalrecord.wrapping.v1",
        closeDatabase = { events += "database-closed" },
    )

    private fun databaseArtifactsForTest(databaseFile: File): List<File> = listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal"),
    )
}

private class RecordingWrappingKeyProvider(
    private val events: MutableList<String>,
) : WrappingKeyProvider {
    override fun getExisting(alias: String): SecretKey? = null

    override fun create(alias: String): SecretKey = error("Not used by data clearing.")

    override fun delete(alias: String): Boolean {
        events += "alias-deleted"
        return true
    }
}
