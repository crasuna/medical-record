package com.loveluke.medicalrecord.feature.encounter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.core.attachment.AttachmentDeleteResult
import com.loveluke.medicalrecord.core.attachment.AttachmentPreviewHandle
import com.loveluke.medicalrecord.core.attachment.AttachmentPreviewResult
import com.loveluke.medicalrecord.core.attachment.EncryptedAttachmentService
import com.loveluke.medicalrecord.core.database.EncounterRepository
import com.loveluke.medicalrecord.core.designsystem.EmptyState
import com.loveluke.medicalrecord.core.designsystem.ErrorState
import com.loveluke.medicalrecord.core.designsystem.LoadingState
import com.loveluke.medicalrecord.core.model.Attachment
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class AttachmentPreviewUiState(
    val isLoading: Boolean = true,
    val isDeleting: Boolean = false,
    val hasError: Boolean = false,
    val isQuarantined: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val attachment: Attachment? = null,
    val plaintextPath: String? = null,
    val mimeType: String? = null,
)

sealed interface AttachmentPreviewAction {
    data object Retry : AttachmentPreviewAction
    data object RequestDelete : AttachmentPreviewAction
    data object DismissDelete : AttachmentPreviewAction
    data object ConfirmDelete : AttachmentPreviewAction
}

sealed interface AttachmentPreviewEvent {
    data object Deleted : AttachmentPreviewEvent
}

@HiltViewModel
class AttachmentPreviewViewModel @Inject constructor(
    private val encounterRepository: EncounterRepository,
    private val attachmentServiceProvider: Provider<EncryptedAttachmentService>,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AttachmentPreviewUiState())
    val uiState = mutableUiState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<AttachmentPreviewEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AttachmentPreviewEvent> = mutableEvents.asSharedFlow()

    private var requestedKey: PreviewKey? = null
    private var previewHandle: AttachmentPreviewHandle? = null
    private var currentAttachment: Attachment? = null
    private var loadJob: Job? = null

    fun load(patientId: String, encounterId: String, attachmentId: String) {
        val key = PreviewKey(patientId, encounterId, attachmentId)
        if (requestedKey == key && (loadJob?.isActive == true || mutableUiState.value.plaintextPath != null)) {
            return
        }
        requestedKey = key
        loadJob?.cancel()
        closePreviewHandle()
        loadJob = viewModelScope.launch {
            mutableUiState.value = AttachmentPreviewUiState(isLoading = true)
            try {
                val details = encounterRepository.observeEncounter(patientId, encounterId).first()
                val attachment = details?.attachments?.firstOrNull { it.id == attachmentId }
                if (attachment == null) {
                    mutableUiState.value = AttachmentPreviewUiState(isLoading = false, hasError = true)
                    return@launch
                }
                currentAttachment = attachment
                if (attachment.integrityState == AttachmentIntegrityState.QUARANTINED) {
                    mutableUiState.value = AttachmentPreviewUiState(
                        isLoading = false,
                        isQuarantined = true,
                        attachment = attachment,
                    )
                    return@launch
                }
                when (val result = attachmentServiceProvider.get().openPreview(attachment)) {
                    is AttachmentPreviewResult.Ready -> {
                        previewHandle = result.handle
                        mutableUiState.value = AttachmentPreviewUiState(
                            isLoading = false,
                            attachment = attachment,
                            plaintextPath = result.handle.file.absolutePath,
                            mimeType = result.handle.mimeType,
                        )
                    }

                    is AttachmentPreviewResult.Quarantined -> {
                        mutableUiState.value = AttachmentPreviewUiState(
                            isLoading = false,
                            isQuarantined = true,
                            attachment = attachment,
                        )
                    }

                    is AttachmentPreviewResult.FailClosed,
                    is AttachmentPreviewResult.Failed,
                    -> mutableUiState.value = AttachmentPreviewUiState(
                        isLoading = false,
                        hasError = true,
                        attachment = attachment,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                closePreviewHandle()
                mutableUiState.update { it.copy(isLoading = false, hasError = true) }
            }
        }
    }

    fun releasePreview() {
        loadJob?.cancel()
        loadJob = null
        closePreviewHandle()
        requestedKey = null
        mutableUiState.update {
            it.copy(isLoading = false, plaintextPath = null, mimeType = null)
        }
    }

    fun onAction(action: AttachmentPreviewAction) {
        when (action) {
            AttachmentPreviewAction.Retry -> requestedKey?.let { key ->
                requestedKey = null
                load(key.patientId, key.encounterId, key.attachmentId)
            }

            AttachmentPreviewAction.RequestDelete -> mutableUiState.update {
                it.copy(showDeleteConfirmation = true)
            }

            AttachmentPreviewAction.DismissDelete -> mutableUiState.update {
                it.copy(showDeleteConfirmation = false)
            }

            AttachmentPreviewAction.ConfirmDelete -> deleteAttachment()
        }
    }

    override fun onCleared() {
        closePreviewHandle()
    }

    private fun deleteAttachment() {
        val attachment = currentAttachment ?: return
        if (mutableUiState.value.isDeleting) return
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(showDeleteConfirmation = false, isDeleting = true)
            }
            closePreviewHandle()
            try {
                when (attachmentServiceProvider.get().delete(attachment)) {
                    is AttachmentDeleteResult.Deleted -> mutableEvents.emit(AttachmentPreviewEvent.Deleted)
                    is AttachmentDeleteResult.CiphertextDeleteFailed,
                    is AttachmentDeleteResult.MetadataDeleteFailed,
                    is AttachmentDeleteResult.Failed,
                    -> mutableUiState.update { it.copy(isDeleting = false, hasError = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update { it.copy(isDeleting = false, hasError = true) }
            }
        }
    }

    private fun closePreviewHandle() {
        previewHandle?.close()
        previewHandle = null
    }

    private data class PreviewKey(
        val patientId: String,
        val encounterId: String,
        val attachmentId: String,
    )
}

@Composable
fun AttachmentPreviewRoute(
    patientId: String,
    encounterId: String,
    attachmentId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: AttachmentPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(patientId, encounterId, attachmentId) {
        viewModel.load(patientId, encounterId, attachmentId)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == AttachmentPreviewEvent.Deleted) onDeleted()
        }
    }
    DisposableEffect(lifecycleOwner, patientId, encounterId, attachmentId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.load(patientId, encounterId, attachmentId)
                Lifecycle.Event.ON_PAUSE -> viewModel.releasePreview()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releasePreview()
        }
    }
    AttachmentPreviewScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPreviewScreen(
    uiState: AttachmentPreviewUiState,
    onAction: (AttachmentPreviewAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        uiState.attachment?.displayName
                            ?: stringResource(R.string.attachment_preview_title),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (uiState.attachment != null) {
                        IconButton(onClick = { onAction(AttachmentPreviewAction.RequestDelete) }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading || uiState.isDeleting -> LoadingState(Modifier.padding(padding))
            uiState.isQuarantined -> EmptyState(
                titleRes = R.string.attachment_preview_title,
                bodyRes = R.string.attachment_quarantined,
                modifier = Modifier.padding(padding),
                icon = Icons.Outlined.ErrorOutline,
            )

            uiState.hasError || uiState.plaintextPath == null || uiState.mimeType == null ->
                ErrorState(
                    onRetry = { onAction(AttachmentPreviewAction.Retry) },
                    modifier = Modifier.padding(padding),
                    titleRes = R.string.attachment_preview_title,
                    bodyRes = R.string.attachment_preview_unavailable,
                )

            uiState.mimeType == "application/pdf" -> PdfAttachmentPreview(
                filePath = uiState.plaintextPath,
                pageCount = uiState.attachment?.pageCount ?: 1,
                modifier = Modifier.padding(padding),
            )

            uiState.mimeType.startsWith("image/") -> ImageAttachmentPreview(
                filePath = uiState.plaintextPath,
                contentDescription = uiState.attachment?.displayName,
                modifier = Modifier.padding(padding),
            )

            else -> EmptyState(
                titleRes = R.string.attachment_preview_title,
                bodyRes = R.string.attachment_preview_unavailable,
                modifier = Modifier.padding(padding),
                icon = Icons.Outlined.Description,
            )
        }
    }
    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { onAction(AttachmentPreviewAction.DismissDelete) },
            title = { Text(stringResource(R.string.attachment_delete_title)) },
            text = { Text(stringResource(R.string.attachment_delete_body)) },
            confirmButton = {
                Button(onClick = { onAction(AttachmentPreviewAction.ConfirmDelete) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AttachmentPreviewAction.DismissDelete) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ImageAttachmentPreview(
    filePath: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val rendered by produceState<RenderedBitmap>(RenderedBitmap.Loading, filePath) {
        value = withContext(Dispatchers.IO) {
            decodeSampledImage(File(filePath))
                ?.let(RenderedBitmap::Ready)
                ?: RenderedBitmap.Failed
        }
    }
    BitmapPreview(rendered, contentDescription, modifier)
}

@Composable
private fun PdfAttachmentPreview(
    filePath: String,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val safePageCount = pageCount.coerceAtLeast(1)
    var pageIndex by rememberSaveable(filePath) { mutableIntStateOf(0) }
    val rendered by produceState<RenderedBitmap>(
        initialValue = RenderedBitmap.Loading,
        filePath,
        pageIndex,
    ) {
        value = withContext(Dispatchers.IO) {
            renderPdfPage(File(filePath), pageIndex)
                ?.let(RenderedBitmap::Ready)
                ?: RenderedBitmap.Failed
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        BitmapPreview(
            rendered = rendered,
            contentDescription = stringResource(R.string.attachment_pdf_page_description, pageIndex + 1),
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { pageIndex -= 1 },
                enabled = pageIndex > 0,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.attachment_previous_page),
                )
            }
            Text(stringResource(R.string.attachment_page_indicator, pageIndex + 1, safePageCount))
            IconButton(
                onClick = { pageIndex += 1 },
                enabled = pageIndex + 1 < safePageCount,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    stringResource(R.string.attachment_next_page),
                )
            }
        }
    }
}

@Composable
private fun BitmapPreview(
    rendered: RenderedBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    when (rendered) {
        RenderedBitmap.Loading -> LoadingState(modifier)
        RenderedBitmap.Failed -> EmptyState(
            titleRes = R.string.attachment_preview_title,
            bodyRes = R.string.attachment_preview_unavailable,
            modifier = modifier,
            icon = Icons.Outlined.Description,
        )

        is RenderedBitmap.Ready -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = rendered.bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private sealed interface RenderedBitmap {
    data object Loading : RenderedBitmap
    data object Failed : RenderedBitmap
    data class Ready(val bitmap: ImageBitmap) : RenderedBitmap
}

internal fun decodeSampledImage(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_IMAGE_DIMENSION_PX ||
        bounds.outHeight / sampleSize > MAX_IMAGE_DIMENSION_PX
    ) {
        sampleSize *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        file.path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null
    val orientation = readImageOrientation(file)
    return try {
        decoded.applyOrientation(orientation).asImageBitmap()
    } catch (_: RuntimeException) {
        decoded.recycle()
        null
    }
}

private data class ImageOrientation(
    val isFlippedHorizontally: Boolean,
    val clockwiseRotationDegrees: Int,
)

private fun readImageOrientation(file: File): ImageOrientation = try {
    ExifInterface(file).let { exif ->
        ImageOrientation(
            isFlippedHorizontally = exif.isFlipped,
            clockwiseRotationDegrees = exif.rotationDegrees,
        )
    }
} catch (_: IOException) {
    ImageOrientation(isFlippedHorizontally = false, clockwiseRotationDegrees = 0)
} catch (_: RuntimeException) {
    ImageOrientation(isFlippedHorizontally = false, clockwiseRotationDegrees = 0)
}

private fun Bitmap.applyOrientation(orientation: ImageOrientation): Bitmap {
    if (!orientation.isFlippedHorizontally && orientation.clockwiseRotationDegrees == 0) {
        return this
    }
    val matrix = Matrix().apply {
        // ExifInterface defines mirrored orientations as a horizontal flip followed by rotation.
        if (orientation.isFlippedHorizontally) postScale(-1f, 1f)
        if (orientation.clockwiseRotationDegrees != 0) {
            postRotate(orientation.clockwiseRotationDegrees.toFloat())
        }
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also { transformed ->
        if (transformed !== this) recycle()
    }
}

private fun renderPdfPage(file: File, pageIndex: Int): ImageBitmap? = try {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) return null
            renderer.openPage(pageIndex).use { page ->
                val scale = min(
                    MAX_PDF_WIDTH_PX.toFloat() / page.width,
                    MAX_PDF_HEIGHT_PX.toFloat() / page.height,
                ).coerceAtMost(1f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = createBitmap(width, height)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(
                    bitmap,
                    null,
                    Matrix().apply { setScale(scale, scale) },
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                bitmap.asImageBitmap()
            }
        }
    }
} catch (_: Exception) {
    null
}

private const val MAX_IMAGE_DIMENSION_PX = 2_048
private const val MAX_PDF_WIDTH_PX = 1_600
private const val MAX_PDF_HEIGHT_PX = 2_200
