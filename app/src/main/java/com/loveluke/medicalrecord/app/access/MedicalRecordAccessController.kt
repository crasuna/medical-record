package com.loveluke.medicalrecord.app.access

import com.loveluke.medicalrecord.app.di.DatabaseFailClosedException
import com.loveluke.medicalrecord.app.di.DatabaseOpenFailure
import com.loveluke.medicalrecord.app.storage.CiphertextMaintenanceResult
import com.loveluke.medicalrecord.app.storage.LocalStorageMaintenance
import com.loveluke.medicalrecord.app.storage.LocalStorageMaintenanceGateway
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.database.AppDatabase
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.security.SecureMaterialFailure
import com.loveluke.medicalrecord.core.security.SecureMaterialManager
import com.loveluke.medicalrecord.core.security.SecureMaterialResolution
import com.loveluke.medicalrecord.core.security.SensitiveDataClearAuthorization
import com.loveluke.medicalrecord.core.security.SensitiveDataClearCoordinator
import com.loveluke.medicalrecord.core.security.SensitiveDataClearReport
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MedicalRecordAccessState {
    data object Initializing : MedicalRecordAccessState
    data class Ready(val patientId: String) : MedicalRecordAccessState
    data class Locked(
        val reason: MedicalRecordLockReason,
        val lastClearReport: SensitiveDataClearReport? = null,
    ) : MedicalRecordAccessState

    data object Clearing : MedicalRecordAccessState
    data class RestartRequired(val report: SensitiveDataClearReport) : MedicalRecordAccessState
}

sealed interface MedicalRecordLockReason {
    data class Database(val failure: DatabaseOpenFailure) : MedicalRecordLockReason
    data class AttachmentKey(val failure: SecureMaterialFailure) : MedicalRecordLockReason
    data object LocalStorageUnavailable : MedicalRecordLockReason
}

sealed interface ReminderAccessResult {
    data object Completed : ReminderAccessResult
    data object TimedOut : ReminderAccessResult
    data object Unavailable : ReminderAccessResult
}

internal fun interface ReminderAccessGate {
    suspend fun run(block: suspend () -> Unit): ReminderAccessResult
}

/**
 * Publishes UI Ready only after the complete local-storage gate. Reminder receivers may wait for
 * the bounded shared security phase, but never execute maintenance themselves or wait for the
 * later encrypted-attachment orphan scan.
 */
@Singleton
class MedicalRecordAccessController internal constructor(
    private val resolveAttachmentKey: () -> SecureMaterialResolution,
    private val openVerifiedDatabase: () -> Unit,
    private val patientRepositoryProvider: Provider<PatientRepository>,
    private val clearSensitiveData: suspend (SensitiveDataClearAuthorization) -> SensitiveDataClearReport,
    private val localStorageMaintenance: LocalStorageMaintenanceGateway,
) {
    @Inject
    constructor(
        secureMaterialManager: SecureMaterialManager,
        attachmentStoragePaths: AttachmentStoragePaths,
        databaseProvider: Provider<AppDatabase>,
        patientRepositoryProvider: Provider<PatientRepository>,
        sensitiveDataClearCoordinator: SensitiveDataClearCoordinator,
        localStorageMaintenance: LocalStorageMaintenance,
    ) : this(
        resolveAttachmentKey = {
            secureMaterialManager.resolveAttachmentMasterKey(attachmentStoragePaths)
        },
        openVerifiedDatabase = {
            databaseProvider.get()
            Unit
        },
        patientRepositoryProvider = patientRepositoryProvider,
        clearSensitiveData = { authorization ->
            withContext(Dispatchers.IO) {
                sensitiveDataClearCoordinator.clear(authorization)
            }
        },
        localStorageMaintenance = localStorageMaintenance,
    )

    private val initializeMutex = Mutex()
    private val databaseAccessMutex = Mutex()
    private val mutableState = MutableStateFlow<MedicalRecordAccessState>(
        MedicalRecordAccessState.Initializing,
    )
    @Volatile
    private var securityUnlockBarrier = CompletableDeferred<SecurityUnlockResult>()
    private var verifiedDatabaseAvailable = false
    private var databaseOpeningForbidden = false

    val state: StateFlow<MedicalRecordAccessState> = mutableState.asStateFlow()

    suspend fun initialize(): MedicalRecordAccessState = initializeMutex.withLock {
        val current = mutableState.value
        if (current is MedicalRecordAccessState.Ready) return@withLock current
        if (current is MedicalRecordAccessState.RestartRequired) return@withLock current
        if (current is MedicalRecordAccessState.Locked) {
            securityUnlockBarrier = CompletableDeferred()
        }
        if (databaseOpeningForbidden) {
            return@withLock failBeforeSecurityUnlock(
                MedicalRecordLockReason.LocalStorageUnavailable,
            )
        }
        mutableState.value = MedicalRecordAccessState.Initializing

        val plaintextReport = try {
            localStorageMaintenance.removeStalePlaintext()
        } catch (cancellation: CancellationException) {
            failBeforeSecurityUnlock(MedicalRecordLockReason.LocalStorageUnavailable)
            throw cancellation
        } catch (_: RuntimeException) {
            return@withLock failBeforeSecurityUnlock(
                MedicalRecordLockReason.LocalStorageUnavailable,
            )
        }
        if (plaintextReport.failedFiles > 0 || plaintextReport.scanFailures > 0) {
            return@withLock failBeforeSecurityUnlock(
                MedicalRecordLockReason.LocalStorageUnavailable,
            )
        }

        val attachmentKey = try {
            resolveAttachmentKey()
        } catch (cancellation: CancellationException) {
            failBeforeSecurityUnlock(MedicalRecordLockReason.LocalStorageUnavailable)
            throw cancellation
        } catch (_: RuntimeException) {
            return@withLock failBeforeSecurityUnlock(
                MedicalRecordLockReason.LocalStorageUnavailable,
            )
        }
        when (attachmentKey) {
            is SecureMaterialResolution.Available -> attachmentKey.secret.close()
            is SecureMaterialResolution.Provisioned -> attachmentKey.secret.close()
            is SecureMaterialResolution.FailClosed -> {
                return@withLock failBeforeSecurityUnlock(
                    MedicalRecordLockReason.AttachmentKey(attachmentKey.reason),
                )
            }
        }

        val databaseFailure = try {
            databaseAccessMutex.withLock {
                ensureVerifiedDatabaseLocked()
            }
        } catch (cancellation: CancellationException) {
            failBeforeSecurityUnlock(MedicalRecordLockReason.LocalStorageUnavailable)
            throw cancellation
        }
        if (databaseFailure != null) return@withLock failBeforeSecurityUnlock(databaseFailure)

        val patient = try {
            patientRepositoryProvider.get().ensureDefaultPatient()
        } catch (failure: CancellationException) {
            failBeforeSecurityUnlock(MedicalRecordLockReason.LocalStorageUnavailable)
            throw failure
        } catch (failure: RuntimeException) {
            val databaseFailureFromCause = failure.findCause<DatabaseFailClosedException>()
            if (databaseFailureFromCause != null) {
                return@withLock failBeforeSecurityUnlock(
                    MedicalRecordLockReason.Database(databaseFailureFromCause.failure),
                )
            } else {
                return@withLock failBeforeSecurityUnlock(
                    MedicalRecordLockReason.LocalStorageUnavailable,
                )
            }
        }
        securityUnlockBarrier.complete(SecurityUnlockResult.UNLOCKED)

        val readyState = try {
            // This may walk the full encrypted attachment tree. It deliberately runs after the
            // bounded security barrier and short database mutex are released, so a cold receiver
            // can use the verified database without waiting for the orphan scan.
            when (localStorageMaintenance.removeUnreferencedCiphertext()) {
                is CiphertextMaintenanceResult.Complete -> MedicalRecordAccessState.Ready(patient.id)
                is CiphertextMaintenanceResult.Incomplete -> {
                    MedicalRecordAccessState.Locked(MedicalRecordLockReason.LocalStorageUnavailable)
                }
            }
        } catch (failure: CancellationException) {
            locked(MedicalRecordLockReason.LocalStorageUnavailable)
            throw failure
        } catch (_: RuntimeException) {
            MedicalRecordAccessState.Locked(MedicalRecordLockReason.LocalStorageUnavailable)
        }

        readyState.also { mutableState.value = it }
    }

    suspend fun retry(): MedicalRecordAccessState = initialize()

    /**
     * Waits at most five seconds for full initialization's shared security phase, then runs one
     * receiver operation under only the short verified-database/clear exclusion gate. It never
     * initiates key resolution, patient creation, maintenance, or a Ready state transition.
     */
    suspend fun runWithReminderAccess(
        block: suspend () -> Unit,
    ): ReminderAccessResult {
        val barrier = when (mutableState.value) {
            is MedicalRecordAccessState.Locked,
            MedicalRecordAccessState.Clearing,
            is MedicalRecordAccessState.RestartRequired,
            -> return ReminderAccessResult.Unavailable

            MedicalRecordAccessState.Initializing,
            is MedicalRecordAccessState.Ready,
            -> securityUnlockBarrier
        }
        val unlockResult = withTimeoutOrNull(REMINDER_SECURITY_GATE_TIMEOUT_MILLIS) {
            barrier.await()
        } ?: return ReminderAccessResult.TimedOut
        if (unlockResult != SecurityUnlockResult.UNLOCKED) {
            return ReminderAccessResult.Unavailable
        }
        return databaseAccessMutex.withLock {
            if (
                barrier !== securityUnlockBarrier ||
                databaseOpeningForbidden ||
                !verifiedDatabaseAvailable ||
                mutableState.value is MedicalRecordAccessState.Locked ||
                mutableState.value is MedicalRecordAccessState.Clearing ||
                mutableState.value is MedicalRecordAccessState.RestartRequired
            ) {
                return@withLock ReminderAccessResult.Unavailable
            }
            try {
                block()
                ReminderAccessResult.Completed
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: RuntimeException) {
                ReminderAccessResult.Unavailable
            }
        }
    }

    /**
     * The recovery UI may call this only after its explicit second confirmation. A null result
     * means the app was not locked, so clearing was not permitted and the authorization was not
     * consumed.
     */
    suspend fun clearLocalData(
        authorization: SensitiveDataClearAuthorization,
    ): SensitiveDataClearReport? = initializeMutex.withLock {
        val lockedState = mutableState.value as? MedicalRecordAccessState.Locked
            ?: return@withLock null
        databaseAccessMutex.withLock {
            val wasOpeningForbidden = databaseOpeningForbidden
            val databaseWasVerified = verifiedDatabaseAvailable
            databaseOpeningForbidden = true
            securityUnlockBarrier.complete(SecurityUnlockResult.UNAVAILABLE)
            mutableState.value = MedicalRecordAccessState.Clearing

            val report = try {
                clearSensitiveData(authorization)
            } catch (cancellation: CancellationException) {
                verifiedDatabaseAvailable = false
                mutableState.value = lockedState
                throw cancellation
            } catch (failure: RuntimeException) {
                verifiedDatabaseAvailable = false
                mutableState.value = lockedState
                throw failure
            }

            if (!report.authorizationAccepted) {
                databaseOpeningForbidden = wasOpeningForbidden
                verifiedDatabaseAvailable = databaseWasVerified
            } else {
                verifiedDatabaseAvailable = false
            }
            mutableState.value = if (report.processRestartRequired) {
                MedicalRecordAccessState.RestartRequired(report)
            } else {
                lockedState.copy(lastClearReport = report)
            }
            report
        }
    }

    private fun ensureVerifiedDatabaseLocked(): MedicalRecordLockReason? {
        if (databaseOpeningForbidden) return MedicalRecordLockReason.LocalStorageUnavailable
        if (verifiedDatabaseAvailable) return null
        return try {
            openVerifiedDatabase()
            verifiedDatabaseAvailable = true
            null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: RuntimeException) {
            val databaseFailure = failure.findCause<DatabaseFailClosedException>()
            if (databaseFailure != null) {
                MedicalRecordLockReason.Database(databaseFailure.failure)
            } else {
                MedicalRecordLockReason.LocalStorageUnavailable
            }
        }
    }

    private fun locked(reason: MedicalRecordLockReason): MedicalRecordAccessState.Locked =
        MedicalRecordAccessState.Locked(reason).also { mutableState.value = it }

    private fun failBeforeSecurityUnlock(
        reason: MedicalRecordLockReason,
    ): MedicalRecordAccessState.Locked {
        securityUnlockBarrier.complete(SecurityUnlockResult.UNAVAILABLE)
        return locked(reason)
    }

    private enum class SecurityUnlockResult {
        UNLOCKED,
        UNAVAILABLE,
    }

    private companion object {
        const val REMINDER_SECURITY_GATE_TIMEOUT_MILLIS = 5_000L
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}
