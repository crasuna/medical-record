package com.loveluke.medicalrecord.app.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.loveluke.medicalrecord.app.storage.LocalStorageMaintenance
import com.loveluke.medicalrecord.core.attachment.AttachmentOrphanCleaner
import com.loveluke.medicalrecord.core.attachment.AttachmentRelativePath
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.attachment.EncryptedFileDeleter
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import com.loveluke.medicalrecord.core.database.AppDatabase
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.model.PatientProfile
import com.loveluke.medicalrecord.core.security.SecretBytes
import com.loveluke.medicalrecord.core.security.SecureMaterialResolution
import java.time.Instant
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MedicalRecordAccessControllerStorageIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.inMemoryBuilder(context)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `orphan delete failure from real maintenance locks access and retry completes cleanup`() =
        runTest {
            val storagePaths = AttachmentStoragePaths(temporaryFolder.newFolder("attachments"))
            val orphanPath = AttachmentRelativePath.original(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
            )
            val orphanFile = storagePaths.resolve(orphanPath).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            var deletionAllowed = false
            val maintenance = LocalStorageMaintenance(
                temporaryPlaintextRegistry = TemporaryPlaintextRegistry(
                    temporaryFolder.newFolder("cache"),
                ),
                attachmentOrphanCleaner = AttachmentOrphanCleaner(
                    storagePaths = storagePaths,
                    fileDeleter = EncryptedFileDeleter { file ->
                        deletionAllowed && file.delete()
                    },
                ),
                databaseProvider = Provider { database },
                ioDispatcher = Dispatchers.Unconfined,
            )
            val controller = newController(maintenance)

            val result = controller.initialize()

            assertEquals(
                MedicalRecordAccessState.Locked(MedicalRecordLockReason.LocalStorageUnavailable),
                result,
            )
            assertFalse(controller.state.value is MedicalRecordAccessState.Ready)
            assertTrue(orphanFile.exists())

            deletionAllowed = true
            val retried = controller.retry()

            assertEquals(MedicalRecordAccessState.Ready(PATIENT_ID), retried)
            assertFalse(orphanFile.exists())
        }

    private fun newController(
        maintenance: LocalStorageMaintenance,
    ): MedicalRecordAccessController {
        val patient = PatientProfile(
            id = PATIENT_ID,
            isDefault = true,
            isHidden = true,
            createdAt = Instant.parse("2026-08-08T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-08T00:00:00Z"),
        )
        return MedicalRecordAccessController(
            resolveAttachmentKey = {
                SecureMaterialResolution.Available(SecretBytes.copyOf(ByteArray(32) { 7 }))
            },
            openVerifiedDatabase = {},
            patientRepositoryProvider = Provider {
                object : PatientRepository {
                    override suspend fun ensureDefaultPatient(): PatientProfile = patient

                    override fun observeDefaultPatient(): Flow<PatientProfile?> = flowOf(patient)

                    override fun observePatient(patientId: String): Flow<PatientProfile?> =
                        flowOf(patient)
                }
            },
            clearSensitiveData = { throw AssertionError("Clear must not run during initialization.") },
            localStorageMaintenance = maintenance,
        )
    }

    private companion object {
        const val PATIENT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
