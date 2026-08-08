package com.loveluke.medicalrecord.feature.encounter

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceFailure

const val AttachmentBatchLimit = 10

sealed interface AttachmentSelection {
    data class CapturedImage(val uri: Uri) : AttachmentSelection
    data class PickedImages(val uris: List<Uri>) : AttachmentSelection
    data class PickedPdfs(val uris: List<Uri>) : AttachmentSelection
}

/**
 * System-picker boundary. Selected Uris are handed immediately to the encrypted attachment
 * coordinator; this composable never persists Uri permissions or reports success before the
 * service has copied, validated, encrypted, and committed metadata for each item.
 */
@Composable
fun AttachmentPickerRow(
    uiState: AttachmentTransferUiState,
    onSelection: (AttachmentSelection) -> Unit,
    onPrepareCameraCapture: () -> Unit,
    onCameraLaunchStarted: (String) -> Unit,
    onCameraLaunchFailed: (String) -> Unit,
    onCameraCaptureResult: (Boolean) -> Unit,
    onDismissReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerMessage by remember { mutableStateOf<Int?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {
        onCameraCaptureResult(it)
    }
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = AttachmentBatchLimit),
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size > AttachmentBatchLimit) {
                pickerMessage = R.string.attachment_too_many_selected
            } else {
                pickerMessage = null
                onSelection(AttachmentSelection.PickedImages(uris))
            }
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size > AttachmentBatchLimit) {
                pickerMessage = R.string.attachment_too_many_selected
            } else {
                pickerMessage = null
                onSelection(AttachmentSelection.PickedPdfs(uris))
            }
        }
    }

    val cameraRequest = uiState.cameraLaunchRequest
    LaunchedEffect(cameraRequest?.requestId) {
        cameraRequest ?: return@LaunchedEffect
        try {
            cameraLauncher.launch(cameraRequest.uri.toUri())
            onCameraLaunchStarted(cameraRequest.requestId)
        } catch (_: RuntimeException) {
            pickerMessage = R.string.camera_unavailable
            onCameraLaunchFailed(cameraRequest.requestId)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 520.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CameraButton(
                        enabled = uiState.controlsEnabled,
                        onClick = onPrepareCameraCapture,
                        modifier = Modifier.weight(1f),
                    )
                    ImagePickerButton(
                        enabled = uiState.controlsEnabled,
                        onClick = {
                            imageLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CameraButton(
                        enabled = uiState.controlsEnabled,
                        onClick = onPrepareCameraCapture,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ImagePickerButton(
                        enabled = uiState.controlsEnabled,
                        onClick = {
                            imageLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.controlsEnabled,
        ) {
            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null)
            Text(stringResource(R.string.attachment_import_pdfs), Modifier.padding(start = 6.dp))
        }
        if (uiState.isPreparingCamera) {
            AttachmentOperationProgress(
                label = stringResource(R.string.attachment_preparing_camera),
            )
        } else if (uiState.isImporting) {
            AttachmentOperationProgress(
                label = pluralStringResource(
                    R.plurals.attachment_importing_count,
                    uiState.pendingItemCount,
                    uiState.pendingItemCount,
                ),
            )
        }
        pickerMessage?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        uiState.report?.let { report ->
            AttachmentImportReport(report = report, onDismiss = onDismissReport)
        }
    }
}

@Composable
private fun CameraButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(Icons.Outlined.AddAPhoto, contentDescription = null)
        Text(stringResource(R.string.attachment_add_photo), Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ImagePickerButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(Icons.Outlined.Image, contentDescription = null)
        Text(stringResource(R.string.attachment_import_images), Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun AttachmentOperationProgress(
    label: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AttachmentImportReport(
    report: AttachmentImportReportUi,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.attachment_import_results),
            style = MaterialTheme.typography.titleMedium,
        )
        report.batchError?.let { error ->
            Text(
                text = stringResource(error.messageRes()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        report.items.sortedBy(AttachmentImportItemUi::index).forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (item.imported) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (item.imported) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = if (item.imported) {
                        stringResource(
                            R.string.attachment_item_imported,
                            item.index + 1,
                            item.displayName.orEmpty(),
                        )
                    } else {
                        stringResource(
                            R.string.attachment_item_failed,
                            item.index + 1,
                            stringResource(checkNotNull(item.failure).messageRes()),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.close))
        }
    }
}

private fun AttachmentImportBatchError.messageRes(): Int = when (this) {
    AttachmentImportBatchError.TOO_MANY_ITEMS -> R.string.attachment_too_many_selected
    AttachmentImportBatchError.INVALID_IDENTITY,
    AttachmentImportBatchError.UNAVAILABLE,
    -> R.string.attachment_import_unavailable

    AttachmentImportBatchError.FAIL_CLOSED -> R.string.attachment_import_fail_closed
}

private fun AttachmentServiceFailure.messageRes(): Int = when (this) {
    AttachmentServiceFailure.SOURCE_UNAVAILABLE -> R.string.attachment_failure_source_unavailable
    AttachmentServiceFailure.TOO_LARGE -> R.string.attachment_failure_too_large
    AttachmentServiceFailure.MISSING_MIME -> R.string.attachment_failure_missing_type
    AttachmentServiceFailure.UNSUPPORTED_MIME -> R.string.attachment_failure_unsupported_type
    AttachmentServiceFailure.MIME_MAGIC_MISMATCH -> R.string.attachment_failure_type_mismatch
    AttachmentServiceFailure.MALFORMED_CONTENT,
    AttachmentServiceFailure.PLATFORM_PARSE_FAILED,
    -> R.string.attachment_failure_malformed

    AttachmentServiceFailure.STORAGE_FAILURE,
    AttachmentServiceFailure.METADATA_WRITE_FAILED,
    AttachmentServiceFailure.METADATA_WRITE_FAILED_CIPHERTEXT_RETAINED,
    -> R.string.attachment_failure_storage
}
