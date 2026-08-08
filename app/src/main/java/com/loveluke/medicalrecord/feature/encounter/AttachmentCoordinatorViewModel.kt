package com.loveluke.medicalrecord.feature.encounter

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceBatchRejection
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceFailure
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceImportResult
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceItemResult
import com.loveluke.medicalrecord.core.attachment.CameraCaptureCommitResult
import com.loveluke.medicalrecord.core.attachment.CameraCaptureHandle
import com.loveluke.medicalrecord.core.attachment.CameraCapturePreparation
import com.loveluke.medicalrecord.core.attachment.EncryptedAttachmentService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CameraLaunchRequest(
    val requestId: String,
    val uri: String,
)

@Immutable
data class AttachmentImportItemUi(
    val index: Int,
    val displayName: String?,
    val failure: AttachmentServiceFailure?,
) {
    val imported: Boolean get() = failure == null
}

enum class AttachmentImportBatchError {
    TOO_MANY_ITEMS,
    INVALID_IDENTITY,
    FAIL_CLOSED,
    UNAVAILABLE,
}

@Immutable
data class AttachmentImportReportUi(
    val items: List<AttachmentImportItemUi> = emptyList(),
    val batchError: AttachmentImportBatchError? = null,
)

@Immutable
data class AttachmentTransferUiState(
    val encounterId: String? = null,
    val isPreparingCamera: Boolean = false,
    val isCameraInFlight: Boolean = false,
    val isImporting: Boolean = false,
    val pendingItemCount: Int = 0,
    val cameraLaunchRequest: CameraLaunchRequest? = null,
    val report: AttachmentImportReportUi? = null,
) {
    val controlsEnabled: Boolean
        get() = !isPreparingCamera && !isCameraInFlight && !isImporting
}

@HiltViewModel
class AttachmentCoordinatorViewModel @Inject constructor(
    private val attachmentServiceProvider: Provider<EncryptedAttachmentService>,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AttachmentTransferUiState())
    val uiState: StateFlow<AttachmentTransferUiState> = mutableUiState.asStateFlow()

    private var cameraContext: CameraContext? = null

    fun importSelection(
        patientId: String,
        encounterId: String,
        selection: AttachmentSelection,
    ) {
        val uris = when (selection) {
            is AttachmentSelection.CapturedImage -> listOf(selection.uri)
            is AttachmentSelection.PickedImages -> selection.uris
            is AttachmentSelection.PickedPdfs -> selection.uris
        }
        if (uris.isEmpty() || !mutableUiState.value.controlsEnabled) return
        viewModelScope.launch {
            mutableUiState.value = AttachmentTransferUiState(
                encounterId = encounterId,
                isImporting = true,
                pendingItemCount = uris.size,
            )
            val report = try {
                attachmentServiceProvider.get()
                    .importUris(patientId, encounterId, uris)
                    .toUiReport()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                AttachmentImportReportUi(batchError = AttachmentImportBatchError.UNAVAILABLE)
            }
            mutableUiState.value = AttachmentTransferUiState(
                encounterId = encounterId,
                report = report,
            )
        }
    }

    fun prepareCameraCapture(
        patientId: String,
        encounterId: String,
    ) {
        if (!mutableUiState.value.controlsEnabled || cameraContext != null) return
        viewModelScope.launch {
            mutableUiState.value = AttachmentTransferUiState(
                encounterId = encounterId,
                isPreparingCamera = true,
            )
            when (val preparation = prepareCameraSafely()) {
                is CameraCapturePreparation.Ready -> {
                    val request = CameraLaunchRequest(
                        requestId = UUID.randomUUID().toString(),
                        uri = preparation.handle.contentUri.toString(),
                    )
                    cameraContext = CameraContext(
                        patientId = patientId,
                        encounterId = encounterId,
                        handle = preparation.handle,
                        requestId = request.requestId,
                    )
                    mutableUiState.value = AttachmentTransferUiState(
                        encounterId = encounterId,
                        cameraLaunchRequest = request,
                    )
                }

                is CameraCapturePreparation.Failed -> {
                    mutableUiState.value = AttachmentTransferUiState(
                        encounterId = encounterId,
                        report = AttachmentImportReportUi(
                            batchError = AttachmentImportBatchError.UNAVAILABLE,
                        ),
                    )
                }
            }
        }
    }

    fun onCameraLaunchStarted(requestId: String) {
        val current = cameraContext?.takeIf { it.requestId == requestId } ?: return
        mutableUiState.value = AttachmentTransferUiState(
            encounterId = current.encounterId,
            isCameraInFlight = true,
        )
    }

    fun onCameraLaunchFailed(requestId: String) {
        val current = cameraContext?.takeIf { it.requestId == requestId } ?: return
        cancelCamera(current)
        mutableUiState.value = AttachmentTransferUiState(
            encounterId = current.encounterId,
            report = AttachmentImportReportUi(
                batchError = AttachmentImportBatchError.UNAVAILABLE,
            ),
        )
    }

    fun onCameraCaptureResult(success: Boolean) {
        val current = cameraContext ?: return
        if (!success) {
            cancelCamera(current)
            mutableUiState.value = AttachmentTransferUiState(encounterId = current.encounterId)
            return
        }
        cameraContext = null
        viewModelScope.launch {
            mutableUiState.value = AttachmentTransferUiState(
                encounterId = current.encounterId,
                isImporting = true,
                pendingItemCount = 1,
            )
            val report = try {
                when (
                    val result = attachmentServiceProvider.get().commitCameraCapture(
                        current.patientId,
                        current.encounterId,
                        current.handle,
                    )
                ) {
                    is CameraCaptureCommitResult.Completed -> result.importResult.toUiReport()
                    CameraCaptureCommitResult.AlreadyFinalized -> AttachmentImportReportUi(
                        batchError = AttachmentImportBatchError.UNAVAILABLE,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                current.handle.close()
                AttachmentImportReportUi(batchError = AttachmentImportBatchError.UNAVAILABLE)
            }
            mutableUiState.value = AttachmentTransferUiState(
                encounterId = current.encounterId,
                report = report,
            )
        }
    }

    fun dismissReport() {
        mutableUiState.update { it.copy(report = null) }
    }

    override fun onCleared() {
        cameraContext?.let(::cancelCamera)
    }

    private fun cancelCamera(context: CameraContext) {
        cameraContext = null
        try {
            attachmentServiceProvider.get().cancelCameraCapture(context.handle)
        } catch (_: RuntimeException) {
            context.handle.close()
        }
    }

    private suspend fun prepareCameraSafely(): CameraCapturePreparation = try {
        attachmentServiceProvider.get().prepareCameraCapture()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        CameraCapturePreparation.Failed(
            com.loveluke.medicalrecord.core.attachment.CameraCaptureFailure.STORAGE_UNAVAILABLE,
        )
    }

    private data class CameraContext(
        val patientId: String,
        val encounterId: String,
        val handle: CameraCaptureHandle,
        val requestId: String,
    )
}

private fun AttachmentServiceImportResult.toUiReport(): AttachmentImportReportUi = when (this) {
    is AttachmentServiceImportResult.Completed -> AttachmentImportReportUi(
        items = items.map { item ->
            when (item) {
                is AttachmentServiceItemResult.Imported -> AttachmentImportItemUi(
                    index = item.index,
                    displayName = item.attachment.displayName,
                    failure = null,
                )

                is AttachmentServiceItemResult.Failed -> AttachmentImportItemUi(
                    index = item.index,
                    displayName = null,
                    failure = item.reason,
                )
            }
        },
    )

    is AttachmentServiceImportResult.Rejected -> AttachmentImportReportUi(
        batchError = when (reason) {
            AttachmentServiceBatchRejection.TOO_MANY_ITEMS ->
                AttachmentImportBatchError.TOO_MANY_ITEMS

            AttachmentServiceBatchRejection.INVALID_IDENTITY ->
                AttachmentImportBatchError.INVALID_IDENTITY
        },
    )

    is AttachmentServiceImportResult.FailClosed -> AttachmentImportReportUi(
        batchError = AttachmentImportBatchError.FAIL_CLOSED,
    )
}
