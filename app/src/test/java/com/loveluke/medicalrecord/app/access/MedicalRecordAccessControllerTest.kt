package com.loveluke.medicalrecord.app.access

import com.loveluke.medicalrecord.app.di.DatabaseFailClosedException
import com.loveluke.medicalrecord.app.di.DatabaseOpenFailure
import com.loveluke.medicalrecord.app.storage.LocalStorageMaintenanceGateway
import com.loveluke.medicalrecord.core.attachment.PlaintextColdStartCleanupReport
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.model.PatientProfile
import com.loveluke.medicalrecord.core.security.SecretBytes
import com.loveluke.medicalrecord.core.security.SecureMaterialResolution
import com.loveluke.medicalrecord.core.security.SensitiveDataClearAuthorization
import com.loveluke.medicalrecord.core.security.SensitiveDataClearReport
import java.time.Instant
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MedicalRecordAccessControllerTest {
    private val patient = PatientProfile(
        id = "11111111-1111-4111-8111-111111111111",
        isDefault = true,
        isHidden = true,
        createdAt = Instant.parse("2026-08-08T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-08T00:00:00Z"),
    )

    @Test
    fun `plaintext cleanup failure locks before key database patient or orphan work and retry reruns it`() =
        runTest {
            val events = mutableListOf<String>()
            val maintenance = FakeStorageMaintenance(
                plaintextReports = ArrayDeque(
                    listOf(
                        PlaintextColdStartCleanupReport(0, failedFiles = 1, scanFailures = 0),
                        PlaintextColdStartCleanupReport(1, failedFiles = 0, scanFailures = 0),
                    ),
                ),
                events = events,
            )
            val controller = newController(
                maintenance = maintenance,
                events = events,
            )

            val locked = controller.initialize()

            assertEquals(
                MedicalRecordAccessState.Locked(MedicalRecordLockReason.LocalStorageUnavailable),
                locked,
            )
            assertEquals(listOf("plaintext"), events)

            val ready = controller.retry()

            assertEquals(MedicalRecordAccessState.Ready(patient.id), ready)
            assertEquals(
                listOf("plaintext", "plaintext", "attachment-key", "database", "patient", "orphan"),
                events,
            )
            assertEquals(2, maintenance.plaintextCalls)
        }

    @Test
    fun `scan failure is retryable Locked and never publishes Ready`() = runTest {
        val maintenance = FakeStorageMaintenance(
            plaintextReports = ArrayDeque(
                listOf(PlaintextColdStartCleanupReport(0, failedFiles = 0, scanFailures = 1)),
            ),
        )
        val controller = newController(maintenance = maintenance)

        val result = controller.initialize()

        assertTrue(result is MedicalRecordAccessState.Locked)
        assertFalse(controller.state.value is MedicalRecordAccessState.Ready)
        assertEquals(0, maintenance.orphanCalls)
    }

    @Test
    fun `reminder access reuses security barrier without rerunning maintenance patient or key`() =
        runTest {
            val events = mutableListOf<String>()
            val maintenance = FakeStorageMaintenance(events = events)
            var blockRan = false
            val controller = newController(maintenance = maintenance, events = events)
            assertTrue(controller.initialize() is MedicalRecordAccessState.Ready)
            events.clear()
            val plaintextCallsBefore = maintenance.plaintextCalls
            val orphanCallsBefore = maintenance.orphanCalls

            val result = controller.runWithReminderAccess {
                events += "reminder-block"
                blockRan = true
            }

            assertEquals(ReminderAccessResult.Completed, result)
            assertTrue(blockRan)
            assertEquals(listOf("reminder-block"), events)
            assertEquals(plaintextCallsBefore, maintenance.plaintextCalls)
            assertEquals(orphanCallsBefore, maintenance.orphanCalls)
        }

    @Test
    fun `reminder access can use opened database while full initialization waits in orphan scan`() =
        runTest {
            val orphanStarted = CompletableDeferred<Unit>()
            val releaseOrphan = CompletableDeferred<Unit>()
            var databaseOpenCalls = 0
            var reminderRan = false
            val maintenance = FakeStorageMaintenance(
                onOrphan = {
                    orphanStarted.complete(Unit)
                    releaseOrphan.await()
                },
            )
            val controller = newController(
                maintenance = maintenance,
                onDatabaseOpen = { databaseOpenCalls += 1 },
            )
            val fullInitialization = async { controller.initialize() }
            orphanStarted.await()

            val reminderResult = controller.runWithReminderAccess {
                reminderRan = true
            }

            assertEquals(ReminderAccessResult.Completed, reminderResult)
            assertTrue(reminderRan)
            assertEquals(1, databaseOpenCalls)
            assertFalse(controller.state.value is MedicalRecordAccessState.Ready)

            releaseOrphan.complete(Unit)
            assertEquals(MedicalRecordAccessState.Ready(patient.id), fullInitialization.await())
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `reminder security wait is bounded and never starts full maintenance itself`() = runTest {
        val events = mutableListOf<String>()
        val maintenance = FakeStorageMaintenance(events = events)
        val controller = newController(maintenance = maintenance, events = events)
        var blockRan = false

        val result = controller.runWithReminderAccess { blockRan = true }

        assertEquals(ReminderAccessResult.TimedOut, result)
        assertFalse(blockRan)
        assertEquals(5_000L, testScheduler.currentTime)
        assertTrue(events.isEmpty())
        assertEquals(0, maintenance.plaintextCalls)
        assertEquals(0, maintenance.orphanCalls)
    }

    @Test
    fun `reminder access while UI is Locked is unavailable and does not run maintenance`() = runTest {
        val maintenance = FakeStorageMaintenance(
            plaintextReports = ArrayDeque(
                listOf(PlaintextColdStartCleanupReport(0, failedFiles = 1, scanFailures = 0)),
            ),
        )
        val controller = newController(maintenance = maintenance)
        assertTrue(controller.initialize() is MedicalRecordAccessState.Locked)
        var reminderRan = false

        val result = controller.runWithReminderAccess { reminderRan = true }

        assertEquals(ReminderAccessResult.Unavailable, result)
        assertFalse(reminderRan)
        assertTrue(controller.state.value is MedicalRecordAccessState.Locked)
        assertEquals(1, maintenance.plaintextCalls)
        assertEquals(0, maintenance.orphanCalls)
    }

    @Test
    fun `database failure is fail closed does not run reminder block and maps official failure`() =
        runTest {
            var blockRan = false
            val controller = newController(
                onDatabaseOpen = {
                    throw DatabaseFailClosedException(DatabaseOpenFailure.OpenFailed)
                },
            )
            assertTrue(controller.initialize() is MedicalRecordAccessState.Locked)

            val result = controller.runWithReminderAccess { blockRan = true }

            assertEquals(ReminderAccessResult.Unavailable, result)
            assertFalse(blockRan)
            assertEquals(
                MedicalRecordAccessState.Locked(
                    MedicalRecordLockReason.Database(DatabaseOpenFailure.OpenFailed),
                ),
                controller.state.value,
            )
        }

    @Test
    fun `database initialization cancellation is rethrown and closes security barrier`() = runTest {
        val controller = newController(
            onDatabaseOpen = { throw CancellationException("database cancellation") },
        )
        var cancellationObserved = false

        try {
            controller.initialize()
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertTrue(controller.state.value is MedicalRecordAccessState.Locked)
        assertEquals(
            ReminderAccessResult.Unavailable,
            controller.runWithReminderAccess {
                fail("Closed security barrier must not run reminder work.")
            },
        )
    }

    @Test
    fun `accepted clear excludes concurrent reminder access and permanently forbids reopen`() =
        runTest {
            val clearStarted = CompletableDeferred<Unit>()
            val releaseClear = CompletableDeferred<Unit>()
            var databaseOpenCalls = 0
            val maintenance = FakeStorageMaintenance(
                plaintextReports = ArrayDeque(
                    listOf(PlaintextColdStartCleanupReport(0, failedFiles = 1, scanFailures = 0)),
                ),
            )
            val controller = newController(
                maintenance = maintenance,
                onDatabaseOpen = { databaseOpenCalls += 1 },
                onClear = {
                    clearStarted.complete(Unit)
                    releaseClear.await()
                    successfulClearReport()
                },
            )
            assertTrue(controller.initialize() is MedicalRecordAccessState.Locked)
            val clear = async {
                controller.clearLocalData(
                    SensitiveDataClearAuthorization.afterExplicitSecondConfirmation(),
                )
            }
            clearStarted.await()
            val reminder = async {
                controller.runWithReminderAccess {
                    fail("Reminder block must not run once clear has started.")
                }
            }
            assertFalse(reminder.isCompleted)

            releaseClear.complete(Unit)

            assertTrue(clear.await()?.authorizationAccepted == true)
            assertEquals(ReminderAccessResult.Unavailable, reminder.await())
            assertEquals(0, databaseOpenCalls)
            assertTrue(controller.state.value is MedicalRecordAccessState.RestartRequired)
        }

    @Test
    fun `reminder cancellation is rethrown without changing access state`() = runTest {
        val controller = newController()
        assertTrue(controller.initialize() is MedicalRecordAccessState.Ready)
        var cancellationObserved = false

        try {
            controller.runWithReminderAccess {
                throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        assertTrue(cancellationObserved)
        assertEquals(MedicalRecordAccessState.Ready(patient.id), controller.state.value)
    }

    private fun newController(
        maintenance: FakeStorageMaintenance = FakeStorageMaintenance(),
        events: MutableList<String> = mutableListOf(),
        onDatabaseOpen: () -> Unit = { events += "database" },
        onClear: suspend (SensitiveDataClearAuthorization) -> SensitiveDataClearReport = {
            successfulClearReport()
        },
    ): MedicalRecordAccessController = MedicalRecordAccessController(
        resolveAttachmentKey = {
            events += "attachment-key"
            SecureMaterialResolution.Available(SecretBytes.copyOf(ByteArray(32) { 7 }))
        },
        openVerifiedDatabase = onDatabaseOpen,
        patientRepositoryProvider = Provider {
            object : PatientRepository {
                override suspend fun ensureDefaultPatient(): PatientProfile {
                    events += "patient"
                    return patient
                }

                override fun observeDefaultPatient(): Flow<PatientProfile?> = flowOf(patient)

                override fun observePatient(patientId: String): Flow<PatientProfile?> = flowOf(patient)
            }
        },
        clearSensitiveData = onClear,
        localStorageMaintenance = maintenance,
    )
}

private class FakeStorageMaintenance(
    private val plaintextReports: ArrayDeque<PlaintextColdStartCleanupReport> = ArrayDeque(
        listOf(PlaintextColdStartCleanupReport(0, failedFiles = 0, scanFailures = 0)),
    ),
    private val events: MutableList<String> = mutableListOf(),
    private val onOrphan: suspend () -> Unit = {},
) : LocalStorageMaintenanceGateway {
    var plaintextCalls = 0
    var orphanCalls = 0

    override suspend fun removeStalePlaintext(): PlaintextColdStartCleanupReport {
        plaintextCalls += 1
        events += "plaintext"
        return if (plaintextReports.size > 1) {
            plaintextReports.removeFirst()
        } else {
            plaintextReports.first()
        }
    }

    override suspend fun removeUnreferencedCiphertext() {
        orphanCalls += 1
        events += "orphan"
        onOrphan()
    }
}

private fun successfulClearReport(): SensitiveDataClearReport = SensitiveDataClearReport(
    authorizationAccepted = true,
    databaseClosed = true,
    deletedDatabaseArtifacts = 1,
    deletedAttachmentFiles = 0,
    deletedEnvelopeFiles = 1,
    deletedPlaintextFiles = 0,
    deletedSharedPreferenceFiles = 0,
    failedDeletes = 0,
    wrappingKeyDeleted = true,
    wrappingKeyDeletionFailed = false,
    requiresRetry = false,
    processRestartRequired = true,
    reminderArtifactsCleared = true,
    reminderArtifactClearFailed = false,
)
