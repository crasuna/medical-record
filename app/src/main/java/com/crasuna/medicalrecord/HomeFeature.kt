package com.crasuna.medicalrecord

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private val homeTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

data class OverviewStats(
    val totalEncounters: Int = 0,
    val totalAttachments: Int = 0,
    val currentMedicationCount: Int = 0,
    val todayReminderCount: Int = 0,
)

data class TodayReminderItem(
    val reminderId: String,
    val medicationId: String,
    val medicationName: String,
    val dose: String?,
    val frequency: String?,
    val timeMinutesOfDay: Int,
)

sealed interface GlobalSearchResult {
    val id: String

    data class Encounter(
        val detail: EncounterWithAttachments,
    ) : GlobalSearchResult {
        override val id: String = detail.encounter.id
    }

    data class Medication(
        val medication: MedicationWithReminders,
    ) : GlobalSearchResult {
        override val id: String = medication.medication.id
    }
}

data class GlobalSearchSection(
    @StringRes val titleRes: Int,
    val results: List<GlobalSearchResult>,
)

data class HomeUiState(
    val query: String = "",
    val overviewStats: OverviewStats = OverviewStats(),
    val recentEncounters: List<EncounterWithAttachments> = emptyList(),
    val currentMedications: List<MedicationWithReminders> = emptyList(),
    val todayReminders: List<TodayReminderItem> = emptyList(),
    val searchSections: List<GlobalSearchSection> = emptyList(),
) {
    val isSearching: Boolean
        get() = query.trim().isNotBlank()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    encounterRepository: EncounterRepository,
    medicationRepository: MedicationRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val uiState = combine(
        encounterRepository.observeEncounterDetails(),
        medicationRepository.observeAllMedications(),
        query,
    ) { encounters, medications, queryValue ->
        buildHomeUiState(
            encounters = encounters,
            medications = medications,
            query = queryValue,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun updateQuery(query: String) {
        _query.value = query
    }
}

@Composable
fun HomeRoute(
    onCreateEncounter: () -> Unit,
    onCreateMedication: () -> Unit,
    onOpenEncounter: (String) -> Unit,
    onEditMedication: (String) -> Unit,
    onOpenEncounters: () -> Unit,
    onOpenMedications: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    HomeScreen(
        uiState = uiState,
        onQueryChange = viewModel::updateQuery,
        onCreateEncounter = onCreateEncounter,
        onCreateMedication = onCreateMedication,
        onOpenEncounter = onOpenEncounter,
        onEditMedication = onEditMedication,
        onOpenEncounters = onOpenEncounters,
        onOpenMedications = onOpenMedications,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onQueryChange: (String) -> Unit,
    onCreateEncounter: () -> Unit,
    onCreateMedication: () -> Unit,
    onOpenEncounter: (String) -> Unit,
    onEditMedication: (String) -> Unit,
    onOpenEncounters: () -> Unit,
    onOpenMedications: () -> Unit,
) {
    MedicalRecordScreenScaffold(
        topBar = {
            MedicalRecordTopAppBar(title = stringResource(R.string.screen_home_title))
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MedicalRecordHeroCard(
                title = stringResource(R.string.screen_home_title),
                subtitle = stringResource(R.string.home_hero_subtitle),
                icon = Icons.Outlined.Home,
            )

            MedicalRecordSearchField(
                value = uiState.query,
                onValueChange = onQueryChange,
                label = stringResource(R.string.label_global_search),
                placeholder = stringResource(R.string.home_search_placeholder),
            )

            if (uiState.isSearching) {
                SearchResultsContent(
                    uiState = uiState,
                    onOpenEncounter = onOpenEncounter,
                    onEditMedication = onEditMedication,
                )
            } else {
                OverviewSection(
                    stats = uiState.overviewStats,
                    onCreateEncounter = onCreateEncounter,
                    onCreateMedication = onCreateMedication,
                )
                RecentEncountersSection(
                    encounters = uiState.recentEncounters,
                    onOpenEncounter = onOpenEncounter,
                    onOpenAll = onOpenEncounters,
                )
                CurrentMedicationsSection(
                    medications = uiState.currentMedications,
                    onEditMedication = onEditMedication,
                    onOpenAll = onOpenMedications,
                )
                TodayRemindersSection(
                    reminders = uiState.todayReminders,
                    onEditMedication = onEditMedication,
                    onOpenAll = onOpenMedications,
                )
            }
        }
    }
}

@Composable
private fun OverviewSection(
    stats: OverviewStats,
    onCreateEncounter: () -> Unit,
    onCreateMedication: () -> Unit,
) {
    MedicalRecordSectionCard(
        title = stringResource(R.string.section_home_overview),
        subtitle = stringResource(R.string.section_home_overview_subtitle),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.EventNote,
                    value = stats.totalEncounters.toString(),
                    label = stringResource(R.string.overview_total_encounters),
                )
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.AttachFile,
                    value = stats.totalAttachments.toString(),
                    label = stringResource(R.string.overview_total_attachments),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Medication,
                    value = stats.currentMedicationCount.toString(),
                    label = stringResource(R.string.overview_current_medications),
                )
                OverviewStatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Schedule,
                    value = stats.todayReminderCount.toString(),
                    label = stringResource(R.string.overview_today_reminders),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MedicalRecordPrimaryButton(
                    text = stringResource(R.string.new_encounter),
                    onClick = onCreateEncounter,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Add,
                )
                MedicalRecordPrimaryButton(
                    text = stringResource(R.string.new_medication),
                    onClick = onCreateMedication,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Add,
                )
            }
        }
    }
}

@Composable
private fun RecentEncountersSection(
    encounters: List<EncounterWithAttachments>,
    onOpenEncounter: (String) -> Unit,
    onOpenAll: () -> Unit,
) {
    MedicalRecordSectionCard(
        title = stringResource(R.string.section_recent_encounters),
        subtitle = stringResource(R.string.section_recent_encounters_subtitle),
        trailing = {
            TextButton(onClick = onOpenAll) {
                Text(stringResource(R.string.action_view_all))
            }
        },
    ) {
        if (encounters.isEmpty()) {
            SectionEmptyText(text = stringResource(R.string.home_empty_recent_encounters))
        } else {
            encounters.forEach { encounter ->
                EncounterSummaryCard(
                    encounter = encounter,
                    onClick = { onOpenEncounter(encounter.encounter.id) },
                )
            }
        }
    }
}

@Composable
private fun CurrentMedicationsSection(
    medications: List<MedicationWithReminders>,
    onEditMedication: (String) -> Unit,
    onOpenAll: () -> Unit,
) {
    MedicalRecordSectionCard(
        title = stringResource(R.string.section_current_medications),
        subtitle = stringResource(R.string.section_current_medications_subtitle),
        trailing = {
            TextButton(onClick = onOpenAll) {
                Text(stringResource(R.string.action_view_all))
            }
        },
    ) {
        if (medications.isEmpty()) {
            SectionEmptyText(text = stringResource(R.string.home_empty_current_medications))
        } else {
            medications.forEach { medication ->
                MedicationSummaryCard(
                    medication = medication,
                    onClick = { onEditMedication(medication.medication.id) },
                )
            }
        }
    }
}

@Composable
private fun TodayRemindersSection(
    reminders: List<TodayReminderItem>,
    onEditMedication: (String) -> Unit,
    onOpenAll: () -> Unit,
) {
    MedicalRecordSectionCard(
        title = stringResource(R.string.section_today_reminders),
        subtitle = stringResource(R.string.section_today_reminders_subtitle),
        trailing = {
            TextButton(onClick = onOpenAll) {
                Text(stringResource(R.string.action_view_all))
            }
        },
    ) {
        if (reminders.isEmpty()) {
            SectionEmptyText(text = stringResource(R.string.home_empty_today_reminders))
        } else {
            reminders.forEach { reminder ->
                TodayReminderCard(
                    reminder = reminder,
                    onClick = { onEditMedication(reminder.medicationId) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultsContent(
    uiState: HomeUiState,
    onOpenEncounter: (String) -> Unit,
    onEditMedication: (String) -> Unit,
) {
    if (uiState.searchSections.isEmpty()) {
        MedicalRecordEmptyState(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            title = stringResource(R.string.home_search_empty_title),
            subtitle = stringResource(R.string.home_search_empty_subtitle, uiState.query.trim()),
            icon = Icons.Outlined.Search,
        )
        return
    }

    uiState.searchSections.forEach { section ->
        MedicalRecordSectionCard(
            title = stringResource(section.titleRes),
            trailing = {
                MedicalRecordInfoPill(
                    text = section.results.size.toString(),
                    accent = true,
                )
            },
        ) {
            section.results.forEach { result ->
                when (result) {
                    is GlobalSearchResult.Encounter -> {
                        EncounterSummaryCard(
                            encounter = result.detail,
                            onClick = { onOpenEncounter(result.id) },
                        )
                    }

                    is GlobalSearchResult.Medication -> {
                        MedicationSummaryCard(
                            medication = result.medication,
                            onClick = { onEditMedication(result.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(icon, contentDescription = null)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EncounterSummaryCard(
    encounter: EncounterWithAttachments,
    onClick: () -> Unit,
) {
    val visitSummary = encounter.encounter.visitDate.format(dateFormatter) +
        (encounter.encounter.visitTime?.let { " ${it.format(homeTimeFormatter)}" } ?: "")
    val departmentAndDoctor = listOfNotNull(encounter.encounter.department, encounter.encounter.doctor)
        .joinToString(" / ")
        .ifBlank { stringResource(R.string.encounter_department_and_doctor_not_set) }
    val diagnosis = encounter.encounter.diagnosis ?: stringResource(R.string.encounter_diagnosis_not_set)

    MedicalRecordSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .padding(11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(Icons.Outlined.LocalHospital, contentDescription = null)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = encounter.encounter.hospital,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = departmentAndDoctor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = diagnosis,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MedicalRecordInfoPill(
                text = visitSummary,
                icon = Icons.Outlined.Schedule,
                accent = true,
            )
        }
        MedicalRecordInfoPill(
            text = pluralStringResource(
                R.plurals.attachments_count,
                encounter.attachments.size,
                encounter.attachments.size,
            ),
            icon = Icons.Outlined.AttachFile,
        )
    }
}

@Composable
private fun MedicationSummaryCard(
    medication: MedicationWithReminders,
    onClick: () -> Unit,
) {
    val reminderSummary = medication.reminders
        .sortedBy { it.timeMinutesOfDay }
        .joinToString(", ") { it.timeMinutesOfDay.toHomeReminderTimeText() }
    val doseAndFrequency = listOfNotNull(medication.medication.dose, medication.medication.frequency)
        .joinToString(" / ")
        .ifBlank { stringResource(R.string.medication_dose_and_frequency_not_set) }
    val isCurrent = medication.medication.endDate == null || !medication.medication.endDate.isBefore(LocalDate.now())

    MedicalRecordSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .padding(11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(Icons.Outlined.Medication, contentDescription = null)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = medication.medication.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = doseAndFrequency,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!medication.medication.notes.isNullOrBlank()) {
                        Text(
                            text = medication.medication.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            MedicalRecordInfoPill(
                text = stringResource(if (isCurrent) R.string.filter_current else R.string.filter_ended),
                accent = isCurrent,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MedicalRecordInfoPill(
                text = stringResource(
                    R.string.medication_date_range,
                    medication.medication.startDate.format(dateFormatter),
                    medication.medication.endDate?.format(dateFormatter) ?: stringResource(R.string.status_ongoing),
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

@Composable
private fun TodayReminderCard(
    reminder: TodayReminderItem,
    onClick: () -> Unit,
) {
    val detail = listOfNotNull(reminder.dose, reminder.frequency)
        .filter { it.isNotBlank() }
        .joinToString(" / ")
        .ifBlank { stringResource(R.string.medication_reminder_notification_body_fallback) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(Icons.Outlined.Schedule, contentDescription = null)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = reminder.medicationName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MedicalRecordInfoPill(
                text = reminder.timeMinutesOfDay.toHomeReminderTimeText(),
                icon = Icons.Outlined.Schedule,
                accent = true,
            )
        }
    }
}

@Composable
private fun SectionEmptyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun buildHomeUiState(
    encounters: List<EncounterWithAttachments>,
    medications: List<MedicationWithReminders>,
    query: String,
    today: LocalDate = LocalDate.now(),
): HomeUiState {
    val sortedEncounters = encounters.sortedEncountersForHome()
    val sortedMedications = medications.sortedMedicationsForHome()
    val currentMedications = sortedMedications.filterNotEnded(today)
    val todayReminders = sortedMedications.buildTodayReminders(today)
    val trimmedQuery = query.trim()

    return HomeUiState(
        query = query,
        overviewStats = OverviewStats(
            totalEncounters = sortedEncounters.size,
            totalAttachments = sortedEncounters.sumOf { it.attachments.size },
            currentMedicationCount = currentMedications.size,
            todayReminderCount = todayReminders.size,
        ),
        recentEncounters = sortedEncounters.take(3),
        currentMedications = currentMedications.take(3),
        todayReminders = todayReminders,
        searchSections = if (trimmedQuery.isBlank()) {
            emptyList()
        } else {
            buildSearchSections(
                encounters = sortedEncounters,
                medications = sortedMedications,
                query = trimmedQuery,
            )
        },
    )
}

private fun buildSearchSections(
    encounters: List<EncounterWithAttachments>,
    medications: List<MedicationWithReminders>,
    query: String,
): List<GlobalSearchSection> {
    val encounterResults = encounters
        .filter { it.matchesQuery(query) }
        .map(GlobalSearchResult::Encounter)
    val medicationResults = medications
        .filter { it.matchesQuery(query) }
        .map(GlobalSearchResult::Medication)

    return buildList {
        if (encounterResults.isNotEmpty()) {
            add(GlobalSearchSection(R.string.search_results_encounters, encounterResults))
        }
        if (medicationResults.isNotEmpty()) {
            add(GlobalSearchSection(R.string.search_results_medications, medicationResults))
        }
    }
}

private fun List<EncounterWithAttachments>.sortedEncountersForHome(): List<EncounterWithAttachments> {
    return sortedWith(
        compareByDescending<EncounterWithAttachments> { it.encounter.visitDate }
            .thenByDescending { it.encounter.visitTime ?: LocalTime.MIN },
    )
}

private fun List<MedicationWithReminders>.sortedMedicationsForHome(): List<MedicationWithReminders> {
    return sortedWith(
        compareByDescending<MedicationWithReminders> { it.medication.startDate }
            .thenByDescending { it.medication.createdAt },
    )
}

private fun List<MedicationWithReminders>.filterNotEnded(today: LocalDate): List<MedicationWithReminders> {
    return filter { medication ->
        medication.medication.endDate == null || !medication.medication.endDate.isBefore(today)
    }
}

private fun List<MedicationWithReminders>.buildTodayReminders(today: LocalDate): List<TodayReminderItem> {
    return filter { medication ->
        !today.isBefore(medication.medication.startDate) &&
            (medication.medication.endDate == null || !today.isAfter(medication.medication.endDate))
    }.flatMap { medication ->
        medication.reminders.map { reminder ->
            TodayReminderItem(
                reminderId = reminder.id,
                medicationId = medication.medication.id,
                medicationName = medication.medication.name,
                dose = medication.medication.dose,
                frequency = medication.medication.frequency,
                timeMinutesOfDay = reminder.timeMinutesOfDay,
            )
        }
    }.sortedWith(compareBy<TodayReminderItem> { it.timeMinutesOfDay }.thenBy { it.medicationName })
}

private fun EncounterWithAttachments.matchesQuery(query: String): Boolean {
    val encounterFields = listOfNotNull(
        encounter.hospital,
        encounter.department,
        encounter.doctor,
        encounter.chiefComplaint,
        encounter.diagnosis,
        encounter.disposition,
        encounter.notes,
    )
    return encounterFields.any { it.contains(query, ignoreCase = true) } ||
        attachments.any { it.displayName.contains(query, ignoreCase = true) }
}

private fun MedicationWithReminders.matchesQuery(query: String): Boolean {
    val medicationFields = listOfNotNull(
        medication.name,
        medication.dose,
        medication.frequency,
        medication.notes,
    )
    return medicationFields.any { it.contains(query, ignoreCase = true) }
}

private fun Int.toHomeReminderTimeText(): String {
    val safeMinutes = coerceIn(0, (24 * 60) - 1)
    return LocalTime.of(safeMinutes / 60, safeMinutes % 60).format(homeTimeFormatter)
}
