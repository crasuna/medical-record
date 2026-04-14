package com.crasuna.medicalrecord

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private val medicationReminderTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

data class MedicationFormState(
    val id: String? = null,
    val name: String = "",
    val dose: String = "",
    val frequency: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val notes: String = "",
    val reminderMinutesOfDay: List<Int> = emptyList(),
    val persistedReminderIds: List<String> = emptyList(),
    val isLoading: Boolean = false,
) {
    fun toEntity(): MedicationEntity {
        return MedicationEntity(
            id = id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            dose = dose.trim().ifBlank { null },
            frequency = frequency.trim().ifBlank { null },
            startDate = startDate,
            endDate = endDate,
            notes = notes.trim().ifBlank { null },
        )
    }
}

@HiltViewModel
class MedicationsViewModel @Inject constructor(
    medicationRepository: MedicationRepository,
) : ViewModel() {
    private val _filter = MutableStateFlow(MedicationFilter.CURRENT)
    val filter: StateFlow<MedicationFilter> = _filter.asStateFlow()
    val medications = medicationRepository.observeMedications(_filter)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(filter: MedicationFilter) {
        _filter.value = filter
    }
}

@HiltViewModel
class MedicationEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val medicationRepository: MedicationRepository,
    private val medicationReminderScheduler: MedicationReminderScheduler,
) : ViewModel() {
    private val medicationId: String? = savedStateHandle["medicationId"]
    private val _formState = MutableStateFlow(MedicationFormState(isLoading = medicationId != null))
    val formState: StateFlow<MedicationFormState> = _formState.asStateFlow()

    init {
        if (medicationId != null) {
            viewModelScope.launch {
                medicationRepository.getMedication(medicationId)?.let { medication ->
                    _formState.value = MedicationFormState(
                        id = medication.medication.id,
                        name = medication.medication.name,
                        dose = medication.medication.dose.orEmpty(),
                        frequency = medication.medication.frequency.orEmpty(),
                        startDate = medication.medication.startDate,
                        endDate = medication.medication.endDate,
                        notes = medication.medication.notes.orEmpty(),
                        reminderMinutesOfDay = medication.reminders.map { it.timeMinutesOfDay }.normalizeReminderTimes(),
                        persistedReminderIds = medication.reminders.map { it.id },
                        isLoading = false,
                    )
                } ?: _formState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun update(transform: (MedicationFormState) -> MedicationFormState) {
        _formState.update(transform)
    }

    fun addReminderTime(minutesOfDay: Int) {
        _formState.update { state ->
            state.copy(reminderMinutesOfDay = (state.reminderMinutesOfDay + minutesOfDay).normalizeReminderTimes())
        }
    }

    fun updateReminderTime(index: Int, minutesOfDay: Int) {
        _formState.update { state ->
            if (index !in state.reminderMinutesOfDay.indices) {
                state
            } else {
                val updated = state.reminderMinutesOfDay.toMutableList()
                updated[index] = minutesOfDay
                state.copy(reminderMinutesOfDay = updated.normalizeReminderTimes())
            }
        }
    }

    fun removeReminderTime(index: Int) {
        _formState.update { state ->
            if (index !in state.reminderMinutesOfDay.indices) {
                state
            } else {
                state.copy(
                    reminderMinutesOfDay = state.reminderMinutesOfDay
                        .filterIndexed { currentIndex, _ -> currentIndex != index }
                        .normalizeReminderTimes(),
                )
            }
        }
    }

    suspend fun save(): String = medicationRepository.saveMedication(
        medication = formState.value.toEntity(),
        reminderMinutesOfDay = formState.value.reminderMinutesOfDay.normalizeReminderTimes(),
    )

    suspend fun delete() {
        formState.value.id?.let { medicationRepository.deleteMedication(it) }
    }

    fun needsNotificationPermissionRequest(): Boolean = medicationReminderScheduler.needsNotificationPermissionRequest()

    fun areNotificationsEnabled(): Boolean = medicationReminderScheduler.areNotificationsEnabled()

    fun canScheduleExactAlarms(): Boolean = medicationReminderScheduler.canScheduleExactAlarms()

    fun notificationSettingsIntent() = medicationReminderScheduler.buildNotificationSettingsIntent()

    fun exactAlarmSettingsIntent() = medicationReminderScheduler.buildExactAlarmSettingsIntent()

    suspend fun syncMedicationReminders(medicationId: String) {
        medicationReminderScheduler.syncMedication(medicationId)
    }

    fun cancelReminderAlarms(reminderIds: List<String>) {
        medicationReminderScheduler.cancelReminderIds(reminderIds)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationListRoute(
    onCreateMedication: () -> Unit,
    onEditMedication: (String) -> Unit,
    viewModel: MedicationsViewModel = hiltViewModel(),
) {
    val medications by viewModel.medications.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val activeCount = medications.count { medication ->
        medication.medication.endDate == null || !medication.medication.endDate.isBefore(LocalDate.now())
    }

    MedicalRecordScreenScaffold(
        topBar = {
            MedicalRecordTopAppBar(title = stringResource(R.string.screen_medications_title))
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateMedication) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.cd_add_medication))
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedicalRecordHeroCard(
                title = stringResource(R.string.screen_medications_title),
                subtitle = stringResource(R.string.medications_hero_subtitle),
                icon = Icons.Outlined.Medication,
                trailing = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MedicalRecordInfoPill(text = medications.size.toString(), accent = true)
                        MedicalRecordInfoPill(
                            text = pluralStringResource(
                                R.plurals.medications_active_count,
                                activeCount,
                                activeCount,
                            ),
                            icon = Icons.Outlined.Schedule,
                        )
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MedicationFilter.values().forEach { candidate ->
                    FilterChip(
                        selected = filter == candidate,
                        onClick = { viewModel.setFilter(candidate) },
                        label = { Text(stringResource(candidate.labelRes())) },
                    )
                }
            }
            if (medications.isEmpty()) {
                MedicalRecordEmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    title = stringResource(R.string.empty_no_medications_title),
                    subtitle = stringResource(R.string.empty_no_medications_subtitle),
                    icon = Icons.Outlined.Medication,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    medications.forEach { medicationWithReminders ->
                        val medication = medicationWithReminders.medication
                        val reminderSummary = medicationWithReminders.reminders
                            .sortedBy { it.timeMinutesOfDay }
                            .joinToString(", ") { it.timeMinutesOfDay.toReminderTimeText() }
                        val doseAndFrequencyFallback = stringResource(R.string.medication_dose_and_frequency_not_set)
                        MedicalRecordSurfaceCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEditMedication(medication.id) },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            medication.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            listOfNotNull(medication.dose, medication.frequency)
                                                .joinToString(" / ")
                                                .ifBlank { doseAndFrequencyFallback },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    AssistChip(
                                        onClick = { onEditMedication(medication.id) },
                                        label = {
                                            Text(
                                                stringResource(
                                                    if (medication.endDate == null || !medication.endDate.isBefore(LocalDate.now())) {
                                                        R.string.filter_current
                                                    } else {
                                                        R.string.filter_ended
                                                    },
                                                ),
                                            )
                                        },
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MedicalRecordInfoPill(
                                        text = stringResource(
                                            R.string.medication_date_range,
                                            medication.startDate.format(dateFormatter),
                                            medication.endDate?.format(dateFormatter) ?: stringResource(R.string.status_ongoing),
                                        ),
                                        icon = Icons.Outlined.Schedule,
                                        accent = true,
                                    )
                                }
                                if (reminderSummary.isNotBlank()) {
                                    MedicalRecordInfoPill(
                                        text = stringResource(R.string.medication_reminder_summary, reminderSummary),
                                        icon = Icons.Outlined.Schedule,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationEditorRoute(
    onNavigateBack: () -> Unit,
    viewModel: MedicationEditorViewModel = hiltViewModel(),
) {
    val formState by viewModel.formState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var showNotificationSettingsDialog by remember { mutableStateOf(false) }
    var showExactAlarmDialog by remember { mutableStateOf(false) }
    var pendingReminderMedicationId by remember { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val savedMedicationId = pendingReminderMedicationId ?: return@rememberLauncherForActivityResult
        if (!granted) {
            showNotificationSettingsDialog = true
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            when {
                !viewModel.areNotificationsEnabled() -> {
                    showNotificationSettingsDialog = true
                }
                !viewModel.canScheduleExactAlarms() -> {
                    showExactAlarmDialog = true
                }
                else -> {
                    viewModel.syncMedicationReminders(savedMedicationId)
                    pendingReminderMedicationId = null
                    onNavigateBack()
                }
            }
        }
    }

    MedicalRecordScreenScaffold(
        topBar = {
            MedicalRecordTopAppBar(
                title = stringResource(
                    if (formState.id == null) R.string.new_medication else R.string.edit_medication,
                ),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (formState.id != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (formState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MedicalRecordHeroCard(
                    title = stringResource(
                        if (formState.id == null) R.string.new_medication else R.string.edit_medication,
                    ),
                    subtitle = stringResource(R.string.medication_form_hero_subtitle),
                    icon = Icons.Outlined.Medication,
                )
                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_medication_basics),
                    subtitle = stringResource(R.string.section_medication_basics_subtitle),
                ) {
                    MedicalRecordTextField(
                        value = formState.name,
                        onValueChange = { value: String -> viewModel.update { it.copy(name = value) } },
                        label = stringResource(R.string.label_medication_name_required),
                        singleLine = true,
                    )
                    MedicalRecordTextField(
                        value = formState.dose,
                        onValueChange = { value: String -> viewModel.update { it.copy(dose = value) } },
                        label = stringResource(R.string.label_dose),
                        singleLine = true,
                    )
                    MedicalRecordTextField(
                        value = formState.frequency,
                        onValueChange = { value: String -> viewModel.update { it.copy(frequency = value) } },
                        label = stringResource(R.string.label_frequency),
                        singleLine = true,
                    )
                }
                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_medication_schedule),
                    subtitle = stringResource(R.string.section_medication_schedule_subtitle),
                ) {
                    OutlinedButton(
                        onClick = {
                            context.pickMedicationDate(formState.startDate) { selected ->
                                viewModel.update { it.copy(startDate = selected) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.start_date_value, formState.startDate.format(dateFormatter)))
                    }
                    OutlinedButton(
                        onClick = {
                            context.pickMedicationDate(formState.endDate ?: LocalDate.now()) { selected ->
                                viewModel.update { it.copy(endDate = selected) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.end_date_value,
                                formState.endDate?.format(dateFormatter) ?: stringResource(R.string.value_not_set),
                            ),
                        )
                    }
                    if (formState.endDate != null) {
                        TextButton(onClick = { viewModel.update { it.copy(endDate = null) } }) {
                            Text(stringResource(R.string.action_clear_end_date))
                        }
                    }
                }
                MedicalRecordSectionCard(
                    title = stringResource(R.string.section_medication_reminders),
                    subtitle = stringResource(R.string.medication_reminders_helper),
                ) {
                    MedicationReminderSection(
                        reminderMinutesOfDay = formState.reminderMinutesOfDay,
                        onAddReminderTime = {
                            context.pickMedicationTime(null) { selectedMinutes ->
                                viewModel.addReminderTime(selectedMinutes)
                            }
                        },
                        onEditReminderTime = { index: Int, currentMinutes: Int ->
                            context.pickMedicationTime(currentMinutes) { selectedMinutes ->
                                viewModel.updateReminderTime(index, selectedMinutes)
                            }
                        },
                        onDeleteReminderTime = { index: Int ->
                            viewModel.removeReminderTime(index)
                        },
                    )
                }
                MedicalRecordSectionCard(
                    title = stringResource(R.string.label_notes),
                    subtitle = stringResource(R.string.section_notes_subtitle),
                ) {
                    MedicalRecordTextField(
                        value = formState.notes,
                        onValueChange = { value: String -> viewModel.update { it.copy(notes = value) } },
                        label = stringResource(R.string.label_notes),
                        minLines = 3,
                    )
                }
                MedicalRecordPrimaryButton(
                    text = stringResource(R.string.action_save_medication),
                    onClick = {
                        scope.launch {
                            val persistedReminderIds = formState.persistedReminderIds
                            val hasReminderTimes = formState.reminderMinutesOfDay.isNotEmpty()
                            val savedMedicationId = viewModel.save()
                            if (persistedReminderIds.isNotEmpty()) {
                                viewModel.cancelReminderAlarms(persistedReminderIds)
                            }
                            if (!hasReminderTimes) {
                                pendingReminderMedicationId = null
                                onNavigateBack()
                                return@launch
                            }

                            pendingReminderMedicationId = savedMedicationId
                            when {
                                viewModel.needsNotificationPermissionRequest() -> {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                !viewModel.areNotificationsEnabled() -> {
                                    showNotificationSettingsDialog = true
                                }
                                !viewModel.canScheduleExactAlarms() -> {
                                    showExactAlarmDialog = true
                                }
                                else -> {
                                    viewModel.syncMedicationReminders(savedMedicationId)
                                    pendingReminderMedicationId = null
                                    onNavigateBack()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = formState.name.isNotBlank(),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_medication_title)) },
            text = { Text(stringResource(R.string.dialog_delete_medication_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.cancelReminderAlarms(formState.persistedReminderIds)
                            viewModel.delete()
                            confirmDelete = false
                            onNavigateBack()
                        }
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.dialog_action_cancel))
                }
            },
        )
    }

    if (showNotificationSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationSettingsDialog = false },
            title = { Text(stringResource(R.string.dialog_notification_settings_title)) },
            text = { Text(stringResource(R.string.dialog_notification_settings_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationSettingsDialog = false
                        pendingReminderMedicationId = null
                        context.startActivity(viewModel.notificationSettingsIntent())
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(R.string.action_open_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotificationSettingsDialog = false
                        pendingReminderMedicationId = null
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(R.string.dialog_action_cancel))
                }
            },
        )
    }

    if (showExactAlarmDialog) {
        AlertDialog(
            onDismissRequest = { showExactAlarmDialog = false },
            title = { Text(stringResource(R.string.dialog_exact_alarm_title)) },
            text = { Text(stringResource(R.string.dialog_exact_alarm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExactAlarmDialog = false
                        pendingReminderMedicationId = null
                        context.startActivity(viewModel.exactAlarmSettingsIntent())
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(R.string.action_open_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExactAlarmDialog = false
                        pendingReminderMedicationId = null
                        onNavigateBack()
                    },
                ) {
                    Text(stringResource(R.string.dialog_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MedicationReminderSection(
    reminderMinutesOfDay: List<Int>,
    onAddReminderTime: () -> Unit,
    onEditReminderTime: (Int, Int) -> Unit,
    onDeleteReminderTime: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (reminderMinutesOfDay.isEmpty()) {
            Text(
                stringResource(R.string.empty_medication_reminders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            reminderMinutesOfDay.forEachIndexed { index, minutesOfDay ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Icon(Icons.Outlined.Schedule, contentDescription = null)
                                }
                            }
                            Text(
                                minutesOfDay.toReminderTimeText(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onEditReminderTime(index, minutesOfDay) }) {
                                Text(stringResource(R.string.action_edit))
                            }
                            IconButton(onClick = { onDeleteReminderTime(index) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.cd_delete_reminder_time),
                                )
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onAddReminderTime, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_add_reminder_time))
        }
    }
}

private fun MedicationFilter.labelRes(): Int {
    return when (this) {
        MedicationFilter.CURRENT -> R.string.filter_current
        MedicationFilter.ALL -> R.string.filter_all
        MedicationFilter.ENDED -> R.string.filter_ended
    }
}

private fun Context.pickMedicationDate(initialDate: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(
        this,
        { _, year, month, dayOfMonth -> onPicked(LocalDate.of(year, month + 1, dayOfMonth)) },
        initialDate.year,
        initialDate.monthValue - 1,
        initialDate.dayOfMonth,
    ).show()
}

private fun Context.pickMedicationTime(initialMinutesOfDay: Int?, onPicked: (Int) -> Unit) {
    val seedTime = initialMinutesOfDay?.let { minutes ->
        LocalTime.of(minutes / 60, minutes % 60)
    } ?: LocalTime.now()
    TimePickerDialog(
        this,
        { _, hourOfDay, minute -> onPicked((hourOfDay * 60) + minute) },
        seedTime.hour,
        seedTime.minute,
        true,
    ).show()
}

private fun List<Int>.normalizeReminderTimes(): List<Int> {
    return map { it.coerceIn(0, (24 * 60) - 1) }
        .distinct()
        .sorted()
}

private fun Int.toReminderTimeText(): String {
    val safeMinutes = coerceIn(0, (24 * 60) - 1)
    return LocalTime.of(safeMinutes / 60, safeMinutes % 60).format(medicationReminderTimeFormatter)
}
