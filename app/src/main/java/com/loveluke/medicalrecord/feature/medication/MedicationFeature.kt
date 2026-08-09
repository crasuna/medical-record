package com.loveluke.medicalrecord.feature.medication

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.app.navigation.LocalDetailBackNavigationVisible
import com.loveluke.medicalrecord.app.testing.MedicalRecordTestTags
import com.loveluke.medicalrecord.core.database.MedicationRepository
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.designsystem.EmptyState
import com.loveluke.medicalrecord.core.designsystem.ErrorState
import com.loveluke.medicalrecord.core.designsystem.LoadingState
import com.loveluke.medicalrecord.core.designsystem.MaxWidthContent
import com.loveluke.medicalrecord.core.designsystem.MedicalRecordThemeTokens
import com.loveluke.medicalrecord.core.designsystem.ScreenContentPadding
import com.loveluke.medicalrecord.core.model.Medication
import com.loveluke.medicalrecord.core.model.MedicationCourseStatus
import com.loveluke.medicalrecord.core.model.MedicationFilter
import com.loveluke.medicalrecord.core.model.MedicationWithReminders
import com.loveluke.medicalrecord.core.model.ReminderDraft
import com.loveluke.medicalrecord.core.model.courseStatus
import com.loveluke.medicalrecord.core.time.MedicalRecordTimeSource
import com.loveluke.medicalrecord.core.time.SystemMedicalRecordTimeSource
import com.loveluke.medicalrecord.core.reminder.ReminderPermissionGateway
import com.loveluke.medicalrecord.core.reminder.ReminderPermissionSnapshot
import com.loveluke.medicalrecord.core.reminder.ReminderNotificationBlockReason
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.UUID
import javax.inject.Inject
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
data class MedicationListUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val filter: MedicationFilter = MedicationFilter.CURRENT,
    val medications: List<Medication> = emptyList(),
    val today: LocalDate = LocalDate.MIN,
)

sealed interface MedicationListAction {
    data class SelectFilter(val filter: MedicationFilter) : MedicationListAction
    data object Retry : MedicationListAction
    data object Refresh : MedicationListAction
}

@HiltViewModel
class MedicationListViewModel @Inject constructor(
    private val patientRepository: PatientRepository,
    private val medicationRepository: MedicationRepository,
    timeSource: MedicalRecordTimeSource = SystemMedicalRecordTimeSource,
) : ViewModel() {
    private var todayProvider: () -> LocalDate = timeSource::today
    private val _uiState = MutableStateFlow(MedicationListUiState())
    val uiState: StateFlow<MedicationListUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    init {
        observe(MedicationFilter.CURRENT)
    }

    fun onAction(action: MedicationListAction) {
        when (action) {
            is MedicationListAction.SelectFilter -> observe(action.filter)
            MedicationListAction.Retry -> observe(_uiState.value.filter)
            MedicationListAction.Refresh -> observe(_uiState.value.filter)
        }
    }

    internal constructor(
        patientRepository: PatientRepository,
        medicationRepository: MedicationRepository,
        todayProvider: () -> LocalDate,
    ) : this(patientRepository, medicationRepository) {
        this.todayProvider = todayProvider
    }

    private fun observe(filter: MedicationFilter) {
        job?.cancel()
        job = viewModelScope.launch {
            val today = todayProvider()
            _uiState.update {
                it.copy(isLoading = true, hasError = false, filter = filter, today = today)
            }
            try {
                val patient = patientRepository.ensureDefaultPatient()
                medicationRepository.observeMedications(patient.id, filter, today).collect { medications ->
                    _uiState.value = MedicationListUiState(
                        isLoading = false,
                        filter = filter,
                        medications = medications,
                        today = today,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, hasError = true) }
            }
        }
    }
}

@Immutable
data class MedicationDetailUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val medication: MedicationWithReminders? = null,
    val showDeleteConfirmation: Boolean = false,
    val isDeleting: Boolean = false,
    val today: LocalDate = LocalDate.MIN,
)

sealed interface MedicationDetailAction {
    data object Retry : MedicationDetailAction
    data object RequestDelete : MedicationDetailAction
    data object DismissDelete : MedicationDetailAction
    data object ConfirmDelete : MedicationDetailAction
}

sealed interface MedicationDetailEvent {
    data object Deleted : MedicationDetailEvent
}

@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    private val patientRepository: PatientRepository,
    private val medicationRepository: MedicationRepository,
    private val timeSource: MedicalRecordTimeSource = SystemMedicalRecordTimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MedicationDetailUiState())
    val uiState: StateFlow<MedicationDetailUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<MedicationDetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<MedicationDetailEvent> = _events.asSharedFlow()
    private var medicationId: String? = null
    private var patientId: String? = null
    private var job: Job? = null

    fun load(medicationId: String) {
        if (this.medicationId == medicationId && job?.isActive == true) return
        this.medicationId = medicationId
        job?.cancel()
        job = viewModelScope.launch {
            val today = timeSource.today()
            _uiState.value = MedicationDetailUiState(isLoading = true, today = today)
            try {
                val patient = patientRepository.ensureDefaultPatient()
                patientId = patient.id
                medicationRepository.observeMedication(patient.id, medicationId).collect { medication ->
                    _uiState.value = MedicationDetailUiState(
                        isLoading = false,
                        medication = medication,
                        today = today,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = MedicationDetailUiState(
                    isLoading = false,
                    hasError = true,
                    today = today,
                )
            }
        }
    }

    fun onAction(action: MedicationDetailAction) {
        when (action) {
            MedicationDetailAction.Retry -> medicationId?.let(::load)
            MedicationDetailAction.RequestDelete -> _uiState.update { it.copy(showDeleteConfirmation = true) }
            MedicationDetailAction.DismissDelete -> _uiState.update { it.copy(showDeleteConfirmation = false) }
            MedicationDetailAction.ConfirmDelete -> delete()
        }
    }

    private fun delete() {
        val targetPatient = patientId ?: return
        val targetMedication = medicationId ?: return
        if (_uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirmation = false, isDeleting = true) }
            try {
                if (medicationRepository.deleteMedication(targetPatient, targetMedication)) {
                    _events.emit(MedicationDetailEvent.Deleted)
                } else {
                    _uiState.update { it.copy(isDeleting = false, hasError = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isDeleting = false, hasError = true) }
            }
        }
    }
}

enum class MedicationFormField {
    NAME,
    START_DATE,
    END_DATE,
    REMINDER_TIME,
}

@Immutable
data class ReminderEditorItem(
    val stableId: String,
    val time: String,
    val enabledByUser: Boolean,
    val isInvalid: Boolean = false,
)

@Immutable
data class MedicationEditorUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val hasLoadError: Boolean = false,
    val hasSaveError: Boolean = false,
    val medicationId: String? = null,
    val name: String = "",
    val dose: String = "",
    val frequency: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val notes: String = "",
    val reminders: List<ReminderEditorItem> = emptyList(),
    val invalidFields: Set<MedicationFormField> = emptySet(),
) {
    val isEditing: Boolean get() = medicationId != null
    val hasEnabledReminder: Boolean get() = reminders.any(ReminderEditorItem::enabledByUser)
}

sealed interface MedicationEditorAction {
    data class NameChanged(val value: String) : MedicationEditorAction
    data class DoseChanged(val value: String) : MedicationEditorAction
    data class FrequencyChanged(val value: String) : MedicationEditorAction
    data class StartDateChanged(val value: String) : MedicationEditorAction
    data class EndDateChanged(val value: String) : MedicationEditorAction
    data class NotesChanged(val value: String) : MedicationEditorAction
    data object AddReminder : MedicationEditorAction
    data class RemoveReminder(val stableId: String) : MedicationEditorAction
    data class ReminderTimeChanged(val stableId: String, val value: String) : MedicationEditorAction
    data class ReminderEnabledChanged(val stableId: String, val enabled: Boolean) : MedicationEditorAction
    data object Save : MedicationEditorAction
    data object Retry : MedicationEditorAction
}

sealed interface MedicationEditorEvent {
    data class Saved(val medicationId: String) : MedicationEditorEvent
}

@HiltViewModel
class MedicationEditorViewModel @Inject constructor(
    private val patientRepository: PatientRepository,
    private val medicationRepository: MedicationRepository,
    private val timeSource: MedicalRecordTimeSource = SystemMedicalRecordTimeSource,
) : ViewModel() {
    private val _uiState = MutableStateFlow(newMedicationState())
    val uiState: StateFlow<MedicationEditorUiState> = _uiState.asStateFlow()
    private val _events = MutableSharedFlow<MedicationEditorEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<MedicationEditorEvent> = _events.asSharedFlow()
    private var original: Medication? = null
    private var requestedId: String? = null

    fun load(medicationId: String?) {
        if (requestedId == medicationId && (medicationId == null || original != null)) return
        requestedId = medicationId
        if (medicationId == null) {
            original = null
            _uiState.value = newMedicationState()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, hasLoadError = false) }
            try {
                val patient = patientRepository.ensureDefaultPatient()
                medicationRepository.observeMedication(patient.id, medicationId).collect { result ->
                    if (result == null) {
                        _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
                    } else {
                        original = result.medication
                        _uiState.value = result.toEditorState()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, hasLoadError = true) }
            }
        }
    }

    fun onAction(action: MedicationEditorAction) {
        when (action) {
            is MedicationEditorAction.NameChanged -> updateField(MedicationFormField.NAME) { copy(name = action.value) }
            is MedicationEditorAction.DoseChanged -> _uiState.update { it.copy(dose = action.value) }
            is MedicationEditorAction.FrequencyChanged -> _uiState.update { it.copy(frequency = action.value) }
            is MedicationEditorAction.StartDateChanged -> updateField(MedicationFormField.START_DATE) { copy(startDate = action.value) }
            is MedicationEditorAction.EndDateChanged -> updateField(MedicationFormField.END_DATE) { copy(endDate = action.value) }
            is MedicationEditorAction.NotesChanged -> _uiState.update { it.copy(notes = action.value) }
            MedicationEditorAction.AddReminder -> _uiState.update {
                it.copy(reminders = it.reminders + ReminderEditorItem(UUID.randomUUID().toString(), "08:00", true))
            }

            is MedicationEditorAction.RemoveReminder -> _uiState.update {
                it.copy(reminders = it.reminders.filterNot { reminder -> reminder.stableId == action.stableId })
            }

            is MedicationEditorAction.ReminderTimeChanged -> _uiState.update {
                it.copy(
                    reminders = it.reminders.map { reminder ->
                        if (reminder.stableId == action.stableId) {
                            reminder.copy(time = action.value, isInvalid = false)
                        } else {
                            reminder
                        }
                    },
                    invalidFields = it.invalidFields - MedicationFormField.REMINDER_TIME,
                )
            }

            is MedicationEditorAction.ReminderEnabledChanged -> _uiState.update {
                it.copy(
                    reminders = it.reminders.map { reminder ->
                        if (reminder.stableId == action.stableId) reminder.copy(enabledByUser = action.enabled) else reminder
                    },
                )
            }

            MedicationEditorAction.Save -> save()
            MedicationEditorAction.Retry -> load(requestedId)
        }
    }

    private inline fun updateField(
        field: MedicationFormField,
        transform: MedicationEditorUiState.() -> MedicationEditorUiState,
    ) {
        _uiState.update { current ->
            current.transform().copy(invalidFields = current.invalidFields - field)
        }
    }

    private fun save() {
        val state = _uiState.value
        val startDate = state.startDate.toDateOrNull()
        val endDate = state.endDate.takeIf(String::isNotBlank)?.toDateOrNull()
        val invalidReminderIds = state.reminders
            .filter { it.time.toMinutesOfDayOrNull() == null }
            .map(ReminderEditorItem::stableId)
            .toSet()
        val invalid = buildSet {
            if (state.name.isBlank()) add(MedicationFormField.NAME)
            if (startDate == null) add(MedicationFormField.START_DATE)
            if (state.endDate.isNotBlank() && endDate == null) add(MedicationFormField.END_DATE)
            if (startDate != null && endDate != null && endDate.isBefore(startDate)) add(MedicationFormField.END_DATE)
            if (invalidReminderIds.isNotEmpty()) add(MedicationFormField.REMINDER_TIME)
        }
        if (invalid.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    invalidFields = invalid,
                    reminders = it.reminders.map { reminder ->
                        reminder.copy(isInvalid = reminder.stableId in invalidReminderIds)
                    },
                )
            }
            return
        }
        if (state.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, hasSaveError = false) }
            try {
                val patient = patientRepository.ensureDefaultPatient()
                val now = timeSource.instant()
                val existing = original
                val medication = Medication(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    patientId = patient.id,
                    name = state.name.trim(),
                    dose = state.dose.trim().nullIfBlank(),
                    frequency = state.frequency.trim().nullIfBlank(),
                    startDate = checkNotNull(startDate),
                    endDate = endDate,
                    notes = state.notes.trim().nullIfBlank(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                val drafts = state.reminders
                    .map {
                        ReminderDraft(
                            timeMinutesOfDay = checkNotNull(it.time.toMinutesOfDayOrNull()),
                            enabledByUser = it.enabledByUser,
                        )
                    }
                    .groupBy(ReminderDraft::timeMinutesOfDay)
                    .map { (timeMinutesOfDay, duplicates) ->
                        ReminderDraft(
                            timeMinutesOfDay = timeMinutesOfDay,
                            enabledByUser = duplicates.any(ReminderDraft::enabledByUser),
                        )
                    }
                    .sortedBy(ReminderDraft::timeMinutesOfDay)
                medicationRepository.saveMedicationWithReminders(medication, drafts)
                _uiState.update { it.copy(isSaving = false, medicationId = medication.id) }
                _events.emit(MedicationEditorEvent.Saved(medication.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, hasSaveError = true) }
            }
        }
    }

    private fun newMedicationState(): MedicationEditorUiState = MedicationEditorUiState(
        startDate = timeSource.today().toString(),
    )
}

private fun MedicationWithReminders.toEditorState() = MedicationEditorUiState(
    medicationId = medication.id,
    name = medication.name,
    dose = medication.dose.orEmpty(),
    frequency = medication.frequency.orEmpty(),
    startDate = medication.startDate.toString(),
    endDate = medication.endDate?.toString().orEmpty(),
    notes = medication.notes.orEmpty(),
    reminders = reminders.sortedBy { it.timeMinutesOfDay }.map { reminder ->
        ReminderEditorItem(
            stableId = reminder.id,
            time = LocalTime.of(reminder.timeMinutesOfDay / 60, reminder.timeMinutesOfDay % 60)
                .format(DateTimeFormatter.ofPattern("HH:mm")),
            enabledByUser = reminder.enabledByUser,
        )
    },
)

private fun String.toDateOrNull(): LocalDate? = try {
    LocalDate.parse(trim())
} catch (_: DateTimeParseException) {
    null
}

private fun String.toMinutesOfDayOrNull(): Int? = try {
    val time = LocalTime.parse(trim(), DateTimeFormatter.ofPattern("H:mm"))
    time.hour * 60 + time.minute
} catch (_: DateTimeParseException) {
    null
}

private fun String.nullIfBlank(): String? = takeIf(String::isNotBlank)

@Composable
fun MedicationListRoute(
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: MedicationListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAction(MedicationListAction.Refresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    MedicationListScreen(uiState, viewModel::onAction, onCreate, onOpen)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationListScreen(
    uiState: MedicationListUiState,
    onAction: (MedicationListAction) -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(MedicalRecordTestTags.SCREEN_MEDICATIONS),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.medications_title)) }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreate,
                modifier = Modifier.testTag(MedicalRecordTestTags.MEDICATION_NEW),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_medication))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MedicationFilterRow(uiState.filter) { onAction(MedicationListAction.SelectFilter(it)) }
            when {
                uiState.isLoading -> LoadingState()
                uiState.hasError -> ErrorState(onRetry = { onAction(MedicationListAction.Retry) })
                uiState.medications.isEmpty() -> EmptyState(
                    titleRes = R.string.no_medications_title,
                    bodyRes = R.string.no_medications_body,
                    icon = Icons.Outlined.Medication,
                    actionLabelRes = R.string.new_medication,
                    onAction = onCreate,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ScreenContentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.medications, key = Medication::id) { medication ->
                        MedicationListItem(medication, uiState.today, onOpen)
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MedicationFilterRow(selected: MedicationFilter, onSelected: (MedicationFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MedicationFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                modifier = Modifier.testTag(filter.testTag()),
                label = { Text(stringResource(filter.labelRes())) },
            )
        }
    }
}

private fun MedicationFilter.labelRes(): Int = when (this) {
    MedicationFilter.CURRENT -> R.string.medication_filter_current
    MedicationFilter.UPCOMING -> R.string.medication_filter_upcoming
    MedicationFilter.ENDED -> R.string.medication_filter_ended
    MedicationFilter.ALL -> R.string.medication_filter_all
}

private fun MedicationFilter.testTag(): String = when (this) {
    MedicationFilter.CURRENT -> MedicalRecordTestTags.MEDICATION_FILTER_CURRENT
    MedicationFilter.UPCOMING -> MedicalRecordTestTags.MEDICATION_FILTER_UPCOMING
    MedicationFilter.ENDED -> MedicalRecordTestTags.MEDICATION_FILTER_ENDED
    MedicationFilter.ALL -> MedicalRecordTestTags.MEDICATION_FILTER_ALL
}

@Composable
private fun MedicationListItem(
    medication: Medication,
    today: LocalDate,
    onOpen: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(medication.id) }) {
        ListItem(
            headlineContent = { Text(medication.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(medication.dose, medication.frequency).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { Icon(Icons.Outlined.Medication, contentDescription = null) },
            trailingContent = { CourseChip(medication.courseStatus(today)) },
        )
    }
}

@Composable
private fun CourseChip(status: MedicationCourseStatus) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(
                when (status) {
                    MedicationCourseStatus.CURRENT -> R.string.medication_course_current
                    MedicationCourseStatus.UPCOMING -> R.string.medication_course_upcoming
                    MedicationCourseStatus.ENDED -> R.string.medication_course_ended
                },
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun MedicationDetailRoute(
    medicationId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: MedicationDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(medicationId) { viewModel.load(medicationId) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { if (it == MedicationDetailEvent.Deleted) onDeleted() }
    }
    MedicationDetailScreen(uiState, viewModel::onAction, onBack, onEdit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailScreen(
    uiState: MedicationDetailUiState,
    onAction: (MedicationDetailAction) -> Unit,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showBack = LocalDetailBackNavigationVisible.current
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.medication_details_title)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    uiState.medication?.medication?.let { medication ->
                        IconButton(
                            onClick = { onEdit(medication.id) },
                            modifier = Modifier.testTag(MedicalRecordTestTags.MEDICATION_EDIT),
                        ) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.edit))
                        }
                        IconButton(
                            onClick = { onAction(MedicationDetailAction.RequestDelete) },
                            modifier = Modifier.testTag(MedicalRecordTestTags.MEDICATION_DELETE),
                        ) {
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
                onRetry = { onAction(MedicationDetailAction.Retry) },
                modifier = Modifier.padding(padding),
            )
            uiState.medication == null -> EmptyState(
                titleRes = R.string.error_title,
                bodyRes = R.string.error_body,
                modifier = Modifier.padding(padding),
            )
            else -> MedicationDetailsContent(
                result = uiState.medication,
                today = uiState.today,
                modifier = Modifier.padding(padding),
            )
        }
    }
    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { onAction(MedicationDetailAction.DismissDelete) },
            title = { Text(stringResource(R.string.medication_delete_title)) },
            text = { Text(stringResource(R.string.medication_delete_body)) },
            confirmButton = {
                Button(
                    onClick = { onAction(MedicationDetailAction.ConfirmDelete) },
                    modifier = Modifier.testTag(MedicalRecordTestTags.MEDICATION_DELETE_CONFIRM),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(MedicationDetailAction.DismissDelete) },
                    modifier = Modifier.testTag(MedicalRecordTestTags.MEDICATION_DELETE_CANCEL),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MedicationDetailsContent(
    result: MedicationWithReminders,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(MedicalRecordTestTags.MEDICATION_DETAIL_CONTENT),
        contentPadding = ScreenContentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(result.medication.name, style = MaterialTheme.typography.headlineMedium)
            CourseChip(result.medication.courseStatus(today))
        }
        item {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MedicationDetailField(R.string.medication_dose, result.medication.dose)
                    MedicationDetailField(R.string.medication_frequency, result.medication.frequency)
                    MedicationDetailField(
                        R.string.medication_start_date,
                        result.medication.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    )
                    MedicationDetailField(
                        R.string.medication_end_date,
                        result.medication.endDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    )
                    MedicationDetailField(R.string.medication_notes, result.medication.notes)
                }
            }
        }
        item { Text(stringResource(R.string.reminders_title), style = MaterialTheme.typography.titleLarge) }
        if (result.reminders.isEmpty()) {
            item { Text(stringResource(R.string.not_provided), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(result.reminders, key = { it.id }) { reminder ->
                ListItem(
                    headlineContent = {
                        Text(
                            LocalTime.of(reminder.timeMinutesOfDay / 60, reminder.timeMinutesOfDay % 60)
                                .format(DateTimeFormatter.ofPattern("HH:mm")),
                        )
                    },
                    supportingContent = {
                        Text(stringResource(if (reminder.enabledByUser) R.string.reminder_enabled else R.string.not_provided))
                    },
                    leadingContent = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun MedicationDetailField(labelRes: Int, value: String?) {
    Column {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
        Text(
            value?.takeIf(String::isNotBlank) ?: stringResource(R.string.not_provided),
            modifier = Modifier.padding(top = 2.dp),
            color = if (value.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun MedicationEditorRoute(
    medicationId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: MedicationEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(medicationId) { viewModel.load(medicationId) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is MedicationEditorEvent.Saved) onSaved(event.medicationId)
        }
    }
    MedicationEditorScreen(uiState, viewModel::onAction, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationEditorScreen(
    uiState: MedicationEditorUiState,
    onAction: (MedicationEditorAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionGateway = remember(context) { ReminderPermissionGateway(context) }
    var permissionSnapshot by remember { mutableStateOf(permissionGateway.snapshot()) }
    var notificationPrompted by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPrompted = true
        permissionSnapshot = permissionGateway.snapshot()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionSnapshot = permissionGateway.snapshot()
    }
    DisposableEffect(lifecycleOwner, permissionGateway) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionSnapshot = permissionGateway.snapshot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(MedicalRecordTestTags.SCREEN_MEDICATION_EDITOR),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (uiState.isEditing) R.string.edit_medication_title else R.string.new_medication_title))
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
                onRetry = { onAction(MedicationEditorAction.Retry) },
                modifier = Modifier.padding(padding),
            )
            else -> MaxWidthContent(
                modifier = Modifier.padding(padding),
                maxWidth = MedicalRecordThemeTokens.formMaxWidth,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(MedicalRecordTestTags.MEDICATION_EDITOR_FORM),
                    contentPadding = ScreenContentPadding,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { MedicationTextField(uiState.name, { onAction(MedicationEditorAction.NameChanged(it)) }, R.string.medication_name, MedicationFormField.NAME in uiState.invalidFields) }
                    item { MedicationTextField(uiState.dose, { onAction(MedicationEditorAction.DoseChanged(it)) }, R.string.medication_dose) }
                    item { MedicationTextField(uiState.frequency, { onAction(MedicationEditorAction.FrequencyChanged(it)) }, R.string.medication_frequency) }
                    item { MedicationTextField(uiState.startDate, { onAction(MedicationEditorAction.StartDateChanged(it)) }, R.string.medication_start_date, MedicationFormField.START_DATE in uiState.invalidFields, R.string.validation_invalid_date) }
                    item { MedicationTextField(uiState.endDate, { onAction(MedicationEditorAction.EndDateChanged(it)) }, R.string.medication_end_date, MedicationFormField.END_DATE in uiState.invalidFields, if (uiState.endDate.toDateOrNull() != null) R.string.validation_end_before_start else R.string.validation_invalid_date) }
                    item { MedicationTextField(uiState.notes, { onAction(MedicationEditorAction.NotesChanged(it)) }, R.string.medication_notes, singleLine = false) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.reminders_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton(
                                onClick = { onAction(MedicationEditorAction.AddReminder) },
                                modifier = Modifier.testTag(MedicalRecordTestTags.REMINDER_ADD),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Text(stringResource(R.string.reminder_add))
                            }
                        }
                    }
                    itemsIndexed(
                        items = uiState.reminders,
                        key = { _, reminder -> reminder.stableId },
                    ) { index, reminder ->
                        ReminderEditorRow(index, reminder, onAction)
                    }
                    if (uiState.hasEnabledReminder) {
                        item {
                            ReminderPermissionPanel(
                                snapshot = permissionSnapshot,
                                onNotificationAction = {
                                    if (
                                        permissionSnapshot.notificationBlockReason ==
                                        ReminderNotificationBlockReason.RUNTIME_PERMISSION &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !notificationPrompted
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        settingsLauncher.launch(
                                            permissionGateway.notificationSettingsIntent(permissionSnapshot),
                                        )
                                    }
                                },
                                onExactAlarmAction = {
                                    permissionGateway.exactAlarmSettingsIntent()?.let(settingsLauncher::launch)
                                },
                            )
                        }
                    }
                    if (uiState.hasSaveError) {
                        item { Text(stringResource(R.string.medication_save_failed), color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.cancel))
                            }
                            Button(
                                onClick = { onAction(MedicationEditorAction.Save) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(MedicalRecordTestTags.MEDICATION_SAVE),
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
private fun ReminderEditorRow(
    index: Int,
    reminder: ReminderEditorItem,
    onAction: (MedicationEditorAction) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = reminder.time,
                onValueChange = { onAction(MedicationEditorAction.ReminderTimeChanged(reminder.stableId, it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MedicalRecordTestTags.reminderTime(index)),
                label = { Text(stringResource(R.string.reminder_time)) },
                isError = reminder.isInvalid,
                supportingText = if (reminder.isInvalid) ({ Text(stringResource(R.string.validation_invalid_time)) }) else null,
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reminder_enabled), modifier = Modifier.weight(1f))
                Switch(
                    checked = reminder.enabledByUser,
                    onCheckedChange = { onAction(MedicationEditorAction.ReminderEnabledChanged(reminder.stableId, it)) },
                    modifier = Modifier.testTag(MedicalRecordTestTags.reminderEnabled(index)),
                )
                IconButton(
                    onClick = { onAction(MedicationEditorAction.RemoveReminder(reminder.stableId)) },
                    modifier = Modifier.testTag(MedicalRecordTestTags.reminderRemove(index)),
                ) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.reminder_remove))
                }
            }
        }
    }
}

@Composable
private fun ReminderPermissionPanel(
    snapshot: ReminderPermissionSnapshot,
    onNotificationAction: () -> Unit,
    onExactAlarmAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!snapshot.notificationsEnabled) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.reminder_permission_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onNotificationAction) {
                        Text(stringResource(R.string.reminder_request_notifications))
                    }
                }
            }
        }
        if (!snapshot.exactAlarmsEnabled) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.reminder_exact_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onExactAlarmAction) {
                        Text(stringResource(R.string.reminder_open_exact_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicationTextField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    isError: Boolean = false,
    errorRes: Int = R.string.validation_required,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(medicationFieldTag(labelRes)),
        label = { Text(stringResource(labelRes)) },
        isError = isError,
        supportingText = if (isError) ({ Text(stringResource(errorRes)) }) else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
    )
}

private fun medicationFieldTag(labelRes: Int): String = when (labelRes) {
    R.string.medication_name -> MedicalRecordTestTags.MEDICATION_NAME
    R.string.medication_dose -> MedicalRecordTestTags.MEDICATION_DOSE
    R.string.medication_frequency -> MedicalRecordTestTags.MEDICATION_FREQUENCY
    R.string.medication_start_date -> MedicalRecordTestTags.MEDICATION_START_DATE
    R.string.medication_end_date -> MedicalRecordTestTags.MEDICATION_END_DATE
    R.string.medication_notes -> MedicalRecordTestTags.MEDICATION_NOTES
    else -> error("Medication field is missing a stable test tag: $labelRes")
}
