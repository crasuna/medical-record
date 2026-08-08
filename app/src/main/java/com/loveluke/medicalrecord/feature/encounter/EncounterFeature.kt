package com.loveluke.medicalrecord.feature.encounter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.app.navigation.LocalDetailBackNavigationVisible
import com.loveluke.medicalrecord.core.attachment.EncounterDeleteResult
import com.loveluke.medicalrecord.core.attachment.EncryptedAttachmentService
import com.loveluke.medicalrecord.core.database.EncounterRepository
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.designsystem.EmptyState
import com.loveluke.medicalrecord.core.designsystem.ErrorState
import com.loveluke.medicalrecord.core.designsystem.LoadingState
import com.loveluke.medicalrecord.core.designsystem.MaxWidthContent
import com.loveluke.medicalrecord.core.designsystem.MedicalRecordThemeTokens
import com.loveluke.medicalrecord.core.designsystem.ScreenContentPadding
import com.loveluke.medicalrecord.core.model.Encounter
import com.loveluke.medicalrecord.core.model.EncounterDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class EncounterListUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val encounters: List<Encounter> = emptyList(),
)

sealed interface EncounterListAction {
    data object Retry : EncounterListAction
}

@HiltViewModel
class EncounterListViewModel @Inject constructor(
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EncounterListUiState())
    val uiState: StateFlow<EncounterListUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun onAction(action: EncounterListAction) {
        when (action) {
            EncounterListAction.Retry -> load()
        }
    }

    private fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = EncounterListUiState(isLoading = true)
            try {
                val patient = patientRepository.ensureDefaultPatient()
                encounterRepository.observeEncounters(patient.id).collect { encounters ->
                    _uiState.value = EncounterListUiState(encounters = encounters)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = EncounterListUiState(isLoading = false, hasError = true)
            }
        }
    }
}

@Immutable
data class EncounterDetailUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val details: EncounterDetails? = null,
    val showDeleteConfirmation: Boolean = false,
    val isDeleting: Boolean = false,
)

sealed interface EncounterDetailAction {
    data object Retry : EncounterDetailAction
    data object RequestDelete : EncounterDetailAction
    data object DismissDelete : EncounterDetailAction
    data object ConfirmDelete : EncounterDetailAction
}

sealed interface EncounterDetailEvent {
    data object Deleted : EncounterDetailEvent
}

@HiltViewModel
class EncounterDetailViewModel @Inject constructor(
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val attachmentServiceProvider: Provider<EncryptedAttachmentService>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EncounterDetailUiState())
    val uiState: StateFlow<EncounterDetailUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<EncounterDetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<EncounterDetailEvent> = _events.asSharedFlow()
    private var encounterId: String? = null
    private var job: Job? = null

    fun load(encounterId: String) {
        if (this.encounterId == encounterId && job?.isActive == true) return
        this.encounterId = encounterId
        job?.cancel()
        job = viewModelScope.launch {
            _uiState.value = EncounterDetailUiState(isLoading = true)
            try {
                val patient = patientRepository.ensureDefaultPatient()
                encounterRepository.observeEncounter(patient.id, encounterId).collect { details ->
                    _uiState.value = EncounterDetailUiState(
                        isLoading = false,
                        details = details,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = EncounterDetailUiState(isLoading = false, hasError = true)
            }
        }
    }

    fun onAction(action: EncounterDetailAction) {
        when (action) {
            EncounterDetailAction.Retry -> encounterId?.let(::load)
            EncounterDetailAction.RequestDelete -> _uiState.update { it.copy(showDeleteConfirmation = true) }
            EncounterDetailAction.DismissDelete -> _uiState.update { it.copy(showDeleteConfirmation = false) }
            EncounterDetailAction.ConfirmDelete -> delete()
        }
    }

    private fun delete() {
        val targetDetails = _uiState.value.details ?: return
        if (_uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirmation = false, isDeleting = true) }
            try {
                when (attachmentServiceProvider.get().deleteEncounter(targetDetails)) {
                    is EncounterDeleteResult.Deleted -> _events.emit(EncounterDetailEvent.Deleted)
                    is EncounterDeleteResult.CiphertextDeleteFailed,
                    is EncounterDeleteResult.MetadataDeleteFailed,
                    is EncounterDeleteResult.Failed,
                    -> _uiState.update { it.copy(isDeleting = false, hasError = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isDeleting = false, hasError = true) }
            }
        }
    }
}

enum class EncounterFormField {
    VISIT_DATE,
    VISIT_TIME,
    HOSPITAL,
}

@Immutable
data class EncounterEditorUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val hasLoadError: Boolean = false,
    val hasSaveError: Boolean = false,
    val encounterId: String? = null,
    val visitDate: String = LocalDate.now().toString(),
    val visitTime: String = "",
    val hospital: String = "",
    val department: String = "",
    val doctor: String = "",
    val chiefComplaint: String = "",
    val diagnosis: String = "",
    val disposition: String = "",
    val notes: String = "",
    val invalidFields: Set<EncounterFormField> = emptySet(),
) {
    val isEditing: Boolean get() = encounterId != null
}

sealed interface EncounterEditorAction {
    data class VisitDateChanged(val value: String) : EncounterEditorAction
    data class VisitTimeChanged(val value: String) : EncounterEditorAction
    data class HospitalChanged(val value: String) : EncounterEditorAction
    data class DepartmentChanged(val value: String) : EncounterEditorAction
    data class DoctorChanged(val value: String) : EncounterEditorAction
    data class ChiefComplaintChanged(val value: String) : EncounterEditorAction
    data class DiagnosisChanged(val value: String) : EncounterEditorAction
    data class DispositionChanged(val value: String) : EncounterEditorAction
    data class NotesChanged(val value: String) : EncounterEditorAction
    data object Save : EncounterEditorAction
    data object Retry : EncounterEditorAction
}

sealed interface EncounterEditorEvent {
    data class Saved(val encounterId: String) : EncounterEditorEvent
}

@HiltViewModel
class EncounterEditorViewModel @Inject constructor(
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(EncounterEditorUiState())
    val uiState: StateFlow<EncounterEditorUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<EncounterEditorEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<EncounterEditorEvent> = _events.asSharedFlow()
    private var original: Encounter? = null
    private var requestedId: String? = null

    fun load(encounterId: String?) {
        if (requestedId == encounterId && (encounterId == null || original != null)) return
        requestedId = encounterId
        if (encounterId == null) {
            original = null
            _uiState.value = EncounterEditorUiState()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, hasLoadError = false) }
            try {
                val patient = patientRepository.ensureDefaultPatient()
                encounterRepository.observeEncounter(patient.id, encounterId).collect { details ->
                    val encounter = details?.encounter
                    if (encounter == null) {
                        _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
                    } else {
                        original = encounter
                        _uiState.value = encounter.toEditorState()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
            }
        }
    }

    fun onAction(action: EncounterEditorAction) {
        when (action) {
            is EncounterEditorAction.VisitDateChanged -> update(EncounterFormField.VISIT_DATE) { copy(visitDate = action.value) }
            is EncounterEditorAction.VisitTimeChanged -> update(EncounterFormField.VISIT_TIME) { copy(visitTime = action.value) }
            is EncounterEditorAction.HospitalChanged -> update(EncounterFormField.HOSPITAL) { copy(hospital = action.value) }
            is EncounterEditorAction.DepartmentChanged -> _uiState.update { it.copy(department = action.value) }
            is EncounterEditorAction.DoctorChanged -> _uiState.update { it.copy(doctor = action.value) }
            is EncounterEditorAction.ChiefComplaintChanged -> _uiState.update { it.copy(chiefComplaint = action.value) }
            is EncounterEditorAction.DiagnosisChanged -> _uiState.update { it.copy(diagnosis = action.value) }
            is EncounterEditorAction.DispositionChanged -> _uiState.update { it.copy(disposition = action.value) }
            is EncounterEditorAction.NotesChanged -> _uiState.update { it.copy(notes = action.value) }
            EncounterEditorAction.Save -> save()
            EncounterEditorAction.Retry -> load(requestedId)
        }
    }

    private inline fun update(
        field: EncounterFormField,
        transform: EncounterEditorUiState.() -> EncounterEditorUiState,
    ) {
        _uiState.update { current ->
            current.transform().copy(invalidFields = current.invalidFields - field)
        }
    }

    private fun save() {
        val state = _uiState.value
        val invalid = buildSet {
            if (state.hospital.isBlank()) add(EncounterFormField.HOSPITAL)
            if (state.visitDate.toLocalDateOrNull() == null) add(EncounterFormField.VISIT_DATE)
            if (state.visitTime.isNotBlank() && state.visitTime.toLocalTimeOrNull() == null) {
                add(EncounterFormField.VISIT_TIME)
            }
        }
        if (invalid.isNotEmpty()) {
            _uiState.update { it.copy(invalidFields = invalid) }
            return
        }
        if (state.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, hasSaveError = false) }
            try {
                val patient = patientRepository.ensureDefaultPatient()
                val now = Instant.now()
                val existing = original
                val encounter = Encounter(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    patientId = patient.id,
                    visitDate = checkNotNull(state.visitDate.toLocalDateOrNull()),
                    visitTime = state.visitTime.toLocalTimeOrNull(),
                    hospital = state.hospital.trim(),
                    department = state.department.trim().nullIfBlank(),
                    doctor = state.doctor.trim().nullIfBlank(),
                    chiefComplaint = state.chiefComplaint.trim().nullIfBlank(),
                    diagnosis = state.diagnosis.trim().nullIfBlank(),
                    disposition = state.disposition.trim().nullIfBlank(),
                    notes = state.notes.trim().nullIfBlank(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                encounterRepository.saveEncounter(encounter)
                _uiState.update { it.copy(isSaving = false, encounterId = encounter.id) }
                _events.emit(EncounterEditorEvent.Saved(encounter.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, hasSaveError = true) }
            }
        }
    }
}

private fun Encounter.toEditorState() = EncounterEditorUiState(
    encounterId = id,
    visitDate = visitDate.toString(),
    visitTime = visitTime?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty(),
    hospital = hospital,
    department = department.orEmpty(),
    doctor = doctor.orEmpty(),
    chiefComplaint = chiefComplaint.orEmpty(),
    diagnosis = diagnosis.orEmpty(),
    disposition = disposition.orEmpty(),
    notes = notes.orEmpty(),
)

private fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(trim())
} catch (_: DateTimeParseException) {
    null
}

private fun String.toLocalTimeOrNull(): LocalTime? {
    if (isBlank()) return null
    return try {
        LocalTime.parse(trim(), DateTimeFormatter.ofPattern("H:mm"))
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun String.nullIfBlank(): String? = takeIf(String::isNotBlank)

@Composable
fun EncounterListRoute(
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: EncounterListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    EncounterListScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onCreate = onCreate,
        onOpen = onOpen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncounterListScreen(
    uiState: EncounterListUiState,
    onAction: (EncounterListAction) -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.encounters_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_encounter))
            }
        },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(Modifier.padding(padding))
            uiState.hasError -> ErrorState(
                onRetry = { onAction(EncounterListAction.Retry) },
                modifier = Modifier.padding(padding),
            )

            uiState.encounters.isEmpty() -> EmptyState(
                titleRes = R.string.no_encounters_title,
                bodyRes = R.string.no_encounters_body,
                modifier = Modifier.padding(padding),
                icon = Icons.AutoMirrored.Outlined.EventNote,
                actionLabelRes = R.string.new_encounter,
                onAction = onCreate,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = ScreenContentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.encounters, key = Encounter::id) { encounter ->
                    EncounterListItem(encounter = encounter, onOpen = onOpen)
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun EncounterListItem(encounter: Encounter, onOpen: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(encounter.id) }) {
        ListItem(
            headlineContent = {
                Text(encounter.hospital, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    listOfNotNull(
                        encounter.visitDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        encounter.department,
                        encounter.diagnosis,
                    ).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.EventNote, contentDescription = null) },
        )
    }
}

@Composable
fun EncounterDetailRoute(
    patientId: String,
    encounterId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
    onOpenAttachment: (String, String) -> Unit,
    viewModel: EncounterDetailViewModel = hiltViewModel(),
    attachmentViewModel: AttachmentCoordinatorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val attachmentUiState by attachmentViewModel.uiState.collectAsStateWithLifecycle()
    val pickerUiState = attachmentUiState.takeIf { it.encounterId == encounterId }
        ?: AttachmentTransferUiState(encounterId = encounterId)
    LaunchedEffect(encounterId) { viewModel.load(encounterId) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == EncounterDetailEvent.Deleted) onDeleted()
        }
    }
    EncounterDetailScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBack = onBack,
        onEdit = onEdit,
        onOpenAttachment = onOpenAttachment,
        attachmentUiState = pickerUiState,
        onAttachmentSelection = { selection ->
            attachmentViewModel.importSelection(patientId, encounterId, selection)
        },
        onPrepareCameraCapture = {
            attachmentViewModel.prepareCameraCapture(patientId, encounterId)
        },
        onCameraLaunchStarted = attachmentViewModel::onCameraLaunchStarted,
        onCameraLaunchFailed = attachmentViewModel::onCameraLaunchFailed,
        onCameraCaptureResult = attachmentViewModel::onCameraCaptureResult,
        onDismissAttachmentReport = attachmentViewModel::dismissReport,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncounterDetailScreen(
    uiState: EncounterDetailUiState,
    onAction: (EncounterDetailAction) -> Unit,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenAttachment: (String, String) -> Unit,
    attachmentUiState: AttachmentTransferUiState,
    onAttachmentSelection: (AttachmentSelection) -> Unit,
    onPrepareCameraCapture: () -> Unit,
    onCameraLaunchStarted: (String) -> Unit,
    onCameraLaunchFailed: (String) -> Unit,
    onCameraCaptureResult: (Boolean) -> Unit,
    onDismissAttachmentReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showBack = LocalDetailBackNavigationVisible.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.encounter_details_title)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    uiState.details?.encounter?.let { encounter ->
                        IconButton(onClick = { onEdit(encounter.id) }) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.edit))
                        }
                        IconButton(onClick = { onAction(EncounterDetailAction.RequestDelete) }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading || uiState.isDeleting -> LoadingState(Modifier.padding(padding))
            uiState.hasError -> ErrorState(
                onRetry = { onAction(EncounterDetailAction.Retry) },
                modifier = Modifier.padding(padding),
            )

            uiState.details == null -> EmptyState(
                titleRes = R.string.error_title,
                bodyRes = R.string.error_body,
                modifier = Modifier.padding(padding),
            )

            else -> EncounterDetailsContent(
                details = uiState.details,
                onOpenAttachment = onOpenAttachment,
                attachmentUiState = attachmentUiState,
                onAttachmentSelection = onAttachmentSelection,
                onPrepareCameraCapture = onPrepareCameraCapture,
                onCameraLaunchStarted = onCameraLaunchStarted,
                onCameraLaunchFailed = onCameraLaunchFailed,
                onCameraCaptureResult = onCameraCaptureResult,
                onDismissAttachmentReport = onDismissAttachmentReport,
                modifier = Modifier.padding(padding),
            )
        }
    }
    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { onAction(EncounterDetailAction.DismissDelete) },
            title = { Text(stringResource(R.string.encounter_delete_title)) },
            text = { Text(stringResource(R.string.encounter_delete_body)) },
            confirmButton = {
                Button(onClick = { onAction(EncounterDetailAction.ConfirmDelete) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(EncounterDetailAction.DismissDelete) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EncounterDetailsContent(
    details: EncounterDetails,
    onOpenAttachment: (String, String) -> Unit,
    attachmentUiState: AttachmentTransferUiState,
    onAttachmentSelection: (AttachmentSelection) -> Unit,
    onPrepareCameraCapture: () -> Unit,
    onCameraLaunchStarted: (String) -> Unit,
    onCameraLaunchFailed: (String) -> Unit,
    onCameraCaptureResult: (Boolean) -> Unit,
    onDismissAttachmentReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ScreenContentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(details.encounter.hospital, style = MaterialTheme.typography.headlineMedium)
                Text(
                    details.encounter.visitDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DetailField(R.string.encounter_visit_time, details.encounter.visitTime?.toString())
                    DetailField(R.string.encounter_department, details.encounter.department)
                    DetailField(R.string.encounter_doctor, details.encounter.doctor)
                    DetailField(R.string.encounter_chief_complaint, details.encounter.chiefComplaint)
                    DetailField(R.string.encounter_diagnosis, details.encounter.diagnosis)
                    DetailField(R.string.encounter_disposition, details.encounter.disposition)
                    DetailField(R.string.encounter_notes, details.encounter.notes)
                }
            }
        }
        item {
            Text(stringResource(R.string.attachments_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.attachment_batch_limit),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (details.attachments.isEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.attachments_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.attachments_empty_body),
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(details.attachments, key = { it.id }) { attachment ->
                ListItem(
                    headlineContent = { Text(attachment.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val formattedSize = NumberFormat.getNumberInstance().apply {
                            minimumFractionDigits = 0
                            maximumFractionDigits = 1
                        }.format(attachment.sizeBytes / 1_048_576.0)
                        Text(
                            listOfNotNull(
                                attachment.mimeType,
                                stringResource(R.string.attachment_size, formattedSize),
                            ).joinToString(" · "),
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    modifier = Modifier.clickable {
                        onOpenAttachment(details.encounter.id, attachment.id)
                    },
                )
            }
        }
        item {
            AttachmentPickerRow(
                uiState = attachmentUiState,
                onSelection = onAttachmentSelection,
                onPrepareCameraCapture = onPrepareCameraCapture,
                onCameraLaunchStarted = onCameraLaunchStarted,
                onCameraLaunchFailed = onCameraLaunchFailed,
                onCameraCaptureResult = onCameraCaptureResult,
                onDismissReport = onDismissAttachmentReport,
            )
        }
    }
}

@Composable
private fun DetailField(labelRes: Int, value: String?) {
    Column {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
        Text(
            text = value?.takeIf(String::isNotBlank) ?: stringResource(R.string.not_provided),
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
fun EncounterEditorRoute(
    encounterId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: EncounterEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(encounterId) { viewModel.load(encounterId) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is EncounterEditorEvent.Saved) onSaved(event.encounterId)
        }
    }
    EncounterEditorScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncounterEditorScreen(
    uiState: EncounterEditorUiState,
    onAction: (EncounterEditorAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (uiState.isEditing) R.string.edit_encounter_title else R.string.new_encounter_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingState(Modifier.padding(padding))
            uiState.hasLoadError -> ErrorState(
                onRetry = { onAction(EncounterEditorAction.Retry) },
                modifier = Modifier.padding(padding),
            )

            else -> MaxWidthContent(
                modifier = Modifier.padding(padding),
                maxWidth = MedicalRecordThemeTokens.formMaxWidth,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ScreenContentPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        FormTextField(
                            value = uiState.visitDate,
                            onValueChange = { onAction(EncounterEditorAction.VisitDateChanged(it)) },
                            labelRes = R.string.encounter_visit_date,
                            isError = EncounterFormField.VISIT_DATE in uiState.invalidFields,
                            errorRes = R.string.validation_invalid_date,
                        )
                    }
                    item {
                        FormTextField(
                            value = uiState.visitTime,
                            onValueChange = { onAction(EncounterEditorAction.VisitTimeChanged(it)) },
                            labelRes = R.string.encounter_visit_time,
                            isError = EncounterFormField.VISIT_TIME in uiState.invalidFields,
                            errorRes = R.string.validation_invalid_time,
                        )
                    }
                    item {
                        FormTextField(
                            value = uiState.hospital,
                            onValueChange = { onAction(EncounterEditorAction.HospitalChanged(it)) },
                            labelRes = R.string.encounter_hospital,
                            isError = EncounterFormField.HOSPITAL in uiState.invalidFields,
                            errorRes = R.string.validation_required,
                        )
                    }
                    item { FormTextField(uiState.department, { onAction(EncounterEditorAction.DepartmentChanged(it)) }, R.string.encounter_department) }
                    item { FormTextField(uiState.doctor, { onAction(EncounterEditorAction.DoctorChanged(it)) }, R.string.encounter_doctor) }
                    item { FormTextField(uiState.chiefComplaint, { onAction(EncounterEditorAction.ChiefComplaintChanged(it)) }, R.string.encounter_chief_complaint, singleLine = false) }
                    item { FormTextField(uiState.diagnosis, { onAction(EncounterEditorAction.DiagnosisChanged(it)) }, R.string.encounter_diagnosis, singleLine = false) }
                    item { FormTextField(uiState.disposition, { onAction(EncounterEditorAction.DispositionChanged(it)) }, R.string.encounter_disposition, singleLine = false) }
                    item { FormTextField(uiState.notes, { onAction(EncounterEditorAction.NotesChanged(it)) }, R.string.encounter_notes, singleLine = false) }
                    if (uiState.hasSaveError) {
                        item {
                            Text(
                                stringResource(R.string.encounter_save_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.cancel))
                            }
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    onAction(EncounterEditorAction.Save)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !uiState.isSaving,
                            ) {
                                Text(stringResource(if (uiState.isSaving) R.string.saving else R.string.save))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    isError: Boolean = false,
    errorRes: Int = R.string.validation_required,
    singleLine: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(labelRes)) },
        isError = isError,
        supportingText = if (isError) ({ Text(stringResource(errorRes)) }) else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
    )
}
