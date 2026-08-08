package com.loveluke.medicalrecord.app

import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.app.access.MedicalRecordAccessController
import com.loveluke.medicalrecord.app.access.MedicalRecordAccessState
import com.loveluke.medicalrecord.app.di.ApplicationCoroutineScope
import com.loveluke.medicalrecord.app.navigation.MedicalRecordApp
import com.loveluke.medicalrecord.app.reminder.ReminderRuntimeCoordinator
import com.loveluke.medicalrecord.core.designsystem.MedicalRecordTheme
import com.loveluke.medicalrecord.core.designsystem.MedicalRecordThemeTokens
import com.loveluke.medicalrecord.core.designsystem.MaxWidthContent
import com.loveluke.medicalrecord.core.privacy.PrivacyShieldHost
import com.loveluke.medicalrecord.core.privacy.PrivacyWindowController
import com.loveluke.medicalrecord.core.reminder.ReminderNotificationPublisher
import com.loveluke.medicalrecord.core.security.SensitiveDataClearAuthorization
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var accessController: MedicalRecordAccessController

    @Inject
    lateinit var reminderRuntimeCoordinator: ReminderRuntimeCoordinator

    @Inject
    @ApplicationCoroutineScope
    lateinit var applicationScope: CoroutineScope

    private val mutablePendingMedicationId = MutableStateFlow<String?>(null)
    private val pendingMedicationId = mutablePendingMedicationId.asStateFlow()
    private lateinit var privacyWindowController: PrivacyWindowController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privacyWindowController = PrivacyWindowController(this).also { controller ->
            controller.install(this)
        }
        consumeReminderIntent(intent)
        enableEdgeToEdge()
        setContent {
            val accessState by accessController.state.collectAsStateWithLifecycle()
            val pendingMedication by pendingMedicationId.collectAsStateWithLifecycle()
            val shieldVisible by privacyWindowController.isShieldVisible

            MedicalRecordTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PrivacyShieldHost(shieldVisible = shieldVisible) {
                        MedicalRecordAccessGate(
                            accessState = accessState,
                            pendingMedicationId = pendingMedication,
                            onPendingMedicationConsumed = {
                                mutablePendingMedicationId.value = null
                            },
                            onRetry = {
                                applicationScope.launch { accessController.retry() }
                            },
                            onClearConfirmed = {
                                applicationScope.launch {
                                    accessController.clearLocalData(
                                        SensitiveDataClearAuthorization
                                            .afterExplicitSecondConfirmation(),
                                    )
                                }
                            },
                            onMedicationScheduleChanged = ::reconcileReminders,
                            onCloseAfterClear = ::closeClearedProcess,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeReminderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (accessController.state.value is MedicalRecordAccessState.Ready) {
            lifecycleScope.launch { reminderRuntimeCoordinator.reconcileIfStarted() }
        }
    }

    private fun consumeReminderIntent(intent: Intent?) {
        val expectedAction = "$packageName.action.OPEN_MEDICATION"
        val candidate = intent
            ?.takeIf { it.action == expectedAction }
            ?.getStringExtra(ReminderNotificationPublisher.EXTRA_MEDICATION_ID)
            ?.let(::normalizedUuidOrNull)
        mutablePendingMedicationId.value = candidate
    }

    private fun reconcileReminders() {
        applicationScope.launch { reminderRuntimeCoordinator.startAndReconcile() }
    }

    private fun closeClearedProcess() {
        finishAndRemoveTask()
        window.decorView.post { Process.killProcess(Process.myPid()) }
    }
}

@Composable
private fun MedicalRecordAccessGate(
    accessState: MedicalRecordAccessState,
    pendingMedicationId: String?,
    onPendingMedicationConsumed: () -> Unit,
    onRetry: () -> Unit,
    onClearConfirmed: () -> Unit,
    onMedicationScheduleChanged: () -> Unit,
    onCloseAfterClear: () -> Unit,
) {
    LaunchedEffect(accessState) {
        // A successful retry also needs to install and reconcile the reminder runtime. The
        // coordinator is idempotent, so this is safe when Application already initialized it.
        if (accessState is MedicalRecordAccessState.Ready) {
            onMedicationScheduleChanged()
        }
    }

    when (accessState) {
        MedicalRecordAccessState.Initializing -> AccessProgressScreen(
            title = stringResource(R.string.database_unlocking_title),
            body = stringResource(R.string.database_unlocking_body),
        )

        MedicalRecordAccessState.Clearing -> AccessProgressScreen(
            title = stringResource(R.string.database_clearing_title),
            body = stringResource(R.string.database_clearing_body),
        )

        is MedicalRecordAccessState.Ready -> MedicalRecordApp(
            patientId = accessState.patientId,
            pendingMedicationId = pendingMedicationId,
            onPendingMedicationConsumed = onPendingMedicationConsumed,
            onMedicationScheduleChanged = onMedicationScheduleChanged,
        )

        is MedicalRecordAccessState.Locked -> LockedAccessScreen(
            clearPreviouslyFailed = accessState.lastClearReport != null,
            onRetry = onRetry,
            onClearConfirmed = onClearConfirmed,
        )

        is MedicalRecordAccessState.RestartRequired -> RestartRequiredScreen(
            clearNeedsRetry = accessState.report.requiresRetry,
            onClose = onCloseAfterClear,
        )
    }
}

@Composable
private fun AccessProgressScreen(
    title: String,
    body: String,
) {
    MaxWidthContent(maxWidth = MedicalRecordThemeTokens.formMaxWidth) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                text = title,
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun LockedAccessScreen(
    clearPreviouslyFailed: Boolean,
    onRetry: () -> Unit,
    onClearConfirmed: () -> Unit,
) {
    var confirmationStep by rememberSaveable { mutableIntStateOf(CLEAR_CONFIRMATION_NONE) }

    MaxWidthContent(maxWidth = MedicalRecordThemeTokens.formMaxWidth) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.database_locked_title),
                modifier = Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.database_locked_body),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (clearPreviouslyFailed) {
                Text(
                    text = stringResource(R.string.database_clear_failed_body),
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
            ) {
                Text(stringResource(R.string.database_retry))
            }
            OutlinedButton(
                onClick = { confirmationStep = CLEAR_CONFIRMATION_FIRST },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.database_clear_action))
            }
        }
    }

    when (confirmationStep) {
        CLEAR_CONFIRMATION_FIRST -> ClearConfirmationDialog(
            title = stringResource(R.string.database_clear_confirm_title),
            body = stringResource(R.string.database_clear_confirm_body),
            confirmLabel = stringResource(R.string.database_clear_confirm_action),
            onDismiss = { confirmationStep = CLEAR_CONFIRMATION_NONE },
            onConfirm = { confirmationStep = CLEAR_CONFIRMATION_SECOND },
        )

        CLEAR_CONFIRMATION_SECOND -> ClearConfirmationDialog(
            title = stringResource(R.string.database_clear_second_title),
            body = stringResource(R.string.database_clear_second_body),
            confirmLabel = stringResource(R.string.database_clear_second_action),
            onDismiss = { confirmationStep = CLEAR_CONFIRMATION_NONE },
            onConfirm = {
                confirmationStep = CLEAR_CONFIRMATION_NONE
                onClearConfirmed()
            },
        )
    }
}

@Composable
private fun ClearConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun RestartRequiredScreen(
    clearNeedsRetry: Boolean,
    onClose: () -> Unit,
) {
    MaxWidthContent(maxWidth = MedicalRecordThemeTokens.formMaxWidth) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.database_restart_required_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    if (clearNeedsRetry) {
                        R.string.database_restart_required_retry_body
                    } else {
                        R.string.database_restart_required_body
                    },
                ),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
            ) {
                Text(stringResource(R.string.database_close_app))
            }
        }
    }
}

private fun normalizedUuidOrNull(rawValue: String): String? = try {
    UUID.fromString(rawValue).toString()
} catch (_: IllegalArgumentException) {
    null
}

private const val CLEAR_CONFIRMATION_NONE = 0
private const val CLEAR_CONFIRMATION_FIRST = 1
private const val CLEAR_CONFIRMATION_SECOND = 2
