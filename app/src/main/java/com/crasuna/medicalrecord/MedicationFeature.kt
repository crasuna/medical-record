package com.crasuna.medicalrecord

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Medication
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import javax.inject.Inject

data class MedicationFormState(
    val id: String? = null,
    val name: String = "",
    val dose: String = "",
    val frequency: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val notes: String = "",
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
) : ViewModel() {
    private val medicationId: String? = savedStateHandle["medicationId"]
    private val _formState = MutableStateFlow(MedicationFormState(isLoading = medicationId != null))
    val formState: StateFlow<MedicationFormState> = _formState.asStateFlow()

    init {
        if (medicationId != null) {
            viewModelScope.launch {
                medicationRepository.getMedication(medicationId)?.let { medication ->
                    _formState.value = MedicationFormState(
                        id = medication.id,
                        name = medication.name,
                        dose = medication.dose.orEmpty(),
                        frequency = medication.frequency.orEmpty(),
                        startDate = medication.startDate,
                        endDate = medication.endDate,
                        notes = medication.notes.orEmpty(),
                        isLoading = false,
                    )
                } ?: _formState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun update(transform: (MedicationFormState) -> MedicationFormState) {
        _formState.update(transform)
    }

    suspend fun save(): String = medicationRepository.saveMedication(formState.value.toEntity())

    suspend fun delete() {
        formState.value.id?.let { medicationRepository.deleteMedication(it) }
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.screen_medications_title)) })
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Medication, contentDescription = null)
                        Text(stringResource(R.string.empty_no_medications_title))
                        Text(
                            stringResource(R.string.empty_no_medications_subtitle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    medications.forEach { medication ->
                        val doseAndFrequencyFallback = stringResource(R.string.medication_dose_and_frequency_not_set)
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEditMedication(medication.id) },
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(medication.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                                Text(
                                    listOfNotNull(medication.dose, medication.frequency)
                                        .joinToString(" / ")
                                        .ifBlank { doseAndFrequencyFallback },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(
                                        R.string.medication_date_range,
                                        medication.startDate.format(dateFormatter),
                                        medication.endDate?.format(dateFormatter) ?: stringResource(R.string.status_ongoing),
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (formState.id == null) R.string.new_medication else R.string.edit_medication,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = formState.name,
                    onValueChange = { value -> viewModel.update { it.copy(name = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_medication_name_required)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = formState.dose,
                    onValueChange = { value -> viewModel.update { it.copy(dose = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_dose)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = formState.frequency,
                    onValueChange = { value -> viewModel.update { it.copy(frequency = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_frequency)) },
                    singleLine = true,
                )
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
                OutlinedTextField(
                    value = formState.notes,
                    onValueChange = { value -> viewModel.update { it.copy(notes = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.label_notes)) },
                    minLines = 3,
                )
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.save()
                            onNavigateBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = formState.name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save_medication))
                }
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
