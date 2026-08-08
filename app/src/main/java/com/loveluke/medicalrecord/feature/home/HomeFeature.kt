package com.loveluke.medicalrecord.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.loveluke.medicalrecord.R
import com.loveluke.medicalrecord.core.database.HomeRepository
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.designsystem.EmptyState
import com.loveluke.medicalrecord.core.designsystem.ErrorState
import com.loveluke.medicalrecord.core.designsystem.LoadingState
import com.loveluke.medicalrecord.core.designsystem.MaxWidthContent
import com.loveluke.medicalrecord.core.designsystem.ScreenContentPadding
import com.loveluke.medicalrecord.core.model.Encounter
import com.loveluke.medicalrecord.core.model.GlobalSearchResults
import com.loveluke.medicalrecord.core.model.HomeOverview
import com.loveluke.medicalrecord.core.model.Medication
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val isSearchLoading: Boolean = false,
    val hasError: Boolean = false,
    val query: String = "",
    val overview: HomeOverview? = null,
    val searchResults: GlobalSearchResults? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

sealed interface HomeAction {
    data class QueryChanged(val query: String) : HomeAction
    data object Retry : HomeAction
    data object Refresh : HomeAction
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val patientRepository: PatientRepository,
    private val homeRepository: HomeRepository,
) : ViewModel() {
    private var todayProvider: () -> LocalDate = LocalDate::now
    private val query = MutableStateFlow("")
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.QueryChanged -> {
                query.value = action.query
                _uiState.update {
                    it.copy(
                        query = action.query,
                        isSearchLoading = action.query.isNotBlank(),
                        searchResults = if (action.query.isBlank()) null else it.searchResults,
                    )
                }
            }

            HomeAction.Retry -> load()
            HomeAction.Refresh -> load()
        }
    }

    internal constructor(
        patientRepository: PatientRepository,
        homeRepository: HomeRepository,
        todayProvider: () -> LocalDate,
    ) : this(patientRepository, homeRepository) {
        this.todayProvider = todayProvider
    }

    @OptIn(FlowPreview::class)
    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, hasError = false) }
            val patient = try {
                patientRepository.ensureDefaultPatient()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { state ->
                    state.copy(isLoading = false, isSearchLoading = false, hasError = true)
                }
                return@launch
            }

            launch {
                try {
                    homeRepository.observeHome(patient.id, todayProvider()).collect { overview ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                hasError = false,
                                overview = overview,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _uiState.update { state ->
                        state.copy(isLoading = false, hasError = true)
                    }
                }
            }
            launch {
                query
                    .debounce(SEARCH_DEBOUNCE_MILLIS)
                    .collectLatest { currentQuery ->
                    if (currentQuery.isBlank()) {
                        _uiState.update {
                            it.copy(isSearchLoading = false, searchResults = null)
                        }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(isSearchLoading = true) }
                    try {
                        homeRepository.search(patient.id, currentQuery.trim()).collect { results ->
                            _uiState.update {
                                it.copy(
                                    isSearchLoading = false,
                                    hasError = false,
                                    searchResults = results,
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _uiState.update {
                            it.copy(isSearchLoading = false, hasError = true)
                        }
                    }
                }
            }
        }
    }
}

private const val SEARCH_DEBOUNCE_MILLIS = 300L

@Composable
fun HomeRoute(
    onCreateEncounter: () -> Unit,
    onCreateMedication: () -> Unit,
    onOpenEncounter: (String) -> Unit,
    onOpenMedication: (String) -> Unit,
    onOpenEncounters: () -> Unit,
    onOpenMedications: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAction(HomeAction.Refresh)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    HomeScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onCreateEncounter = onCreateEncounter,
        onCreateMedication = onCreateMedication,
        onOpenEncounter = onOpenEncounter,
        onOpenMedication = onOpenMedication,
        onOpenEncounters = onOpenEncounters,
        onOpenMedications = onOpenMedications,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onCreateEncounter: () -> Unit,
    onCreateMedication: () -> Unit,
    onOpenEncounter: (String) -> Unit,
    onOpenMedication: (String) -> Unit,
    onOpenEncounters: () -> Unit,
    onOpenMedications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.home_title)) })
        },
    ) { innerPadding ->
        MaxWidthContent(modifier = Modifier.padding(innerPadding)) {
            when {
                uiState.isLoading && uiState.overview == null -> LoadingState()
                uiState.hasError && uiState.overview == null -> ErrorState(
                    onRetry = { onAction(HomeAction.Retry) },
                )

                else -> HomeContent(
                    uiState = uiState,
                    onAction = onAction,
                    onCreateEncounter = onCreateEncounter,
                    onCreateMedication = onCreateMedication,
                    onOpenEncounter = onOpenEncounter,
                    onOpenMedication = onOpenMedication,
                    onOpenEncounters = onOpenEncounters,
                    onOpenMedications = onOpenMedications,
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onCreateEncounter: () -> Unit,
    onCreateMedication: () -> Unit,
    onOpenEncounter: (String) -> Unit,
    onOpenMedication: (String) -> Unit,
    onOpenEncounters: () -> Unit,
    onOpenMedications: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenContentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.home_welcome),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.home_subtitle),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { onAction(HomeAction.QueryChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.search_label)) },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
        }

        if (uiState.isSearching) {
            item {
                SearchResults(
                    uiState = uiState,
                    onOpenEncounter = onOpenEncounter,
                    onOpenMedication = onOpenMedication,
                )
            }
        } else {
            val overview = uiState.overview
            if (overview != null) {
                item { OverviewCards(overview) }
                item {
                    QuickActions(
                        onCreateEncounter = onCreateEncounter,
                        onCreateMedication = onCreateMedication,
                    )
                }
                item {
                    HomeSectionHeader(
                        title = stringResource(R.string.recent_encounters),
                        action = stringResource(R.string.view_all),
                        onAction = onOpenEncounters,
                    )
                }
                if (overview.recentEncounters.isEmpty()) {
                    item {
                        CompactEmptyCard(
                            title = stringResource(R.string.no_encounters_title),
                            body = stringResource(R.string.no_encounters_body),
                        )
                    }
                } else {
                    items(overview.recentEncounters, key = Encounter::id) { encounter ->
                        EncounterResultRow(encounter, onOpenEncounter)
                    }
                }
                item {
                    HomeSectionHeader(
                        title = stringResource(R.string.current_medications),
                        action = stringResource(R.string.view_all),
                        onAction = onOpenMedications,
                    )
                }
                if (overview.recentCurrentMedications.isEmpty()) {
                    item {
                        CompactEmptyCard(
                            title = stringResource(R.string.no_current_medications_title),
                            body = stringResource(R.string.no_current_medications_body),
                        )
                    }
                } else {
                    items(overview.recentCurrentMedications, key = Medication::id) { medication ->
                        MedicationResultRow(medication, onOpenMedication)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun OverviewCards(overview: HomeOverview) {
    val cards = listOf(
        OverviewCardData(R.string.encounters_count, overview.counts.encounterCount, Icons.AutoMirrored.Outlined.EventNote),
        OverviewCardData(R.string.attachments_count, overview.counts.attachmentCount, Icons.Outlined.Description),
        OverviewCardData(R.string.current_medications_count, overview.counts.currentMedicationCount, Icons.Outlined.Medication),
        OverviewCardData(R.string.today_reminders_count, overview.counts.todayReminderCount, Icons.Outlined.Notifications),
    )
    BoxWithConstraints {
        if (maxWidth >= 760.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cards.forEach { card ->
                    OverviewCard(card, Modifier.weight(1f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverviewCard(cards[0], Modifier.weight(1f))
                    OverviewCard(cards[1], Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverviewCard(cards[2], Modifier.weight(1f))
                    OverviewCard(cards[3], Modifier.weight(1f))
                }
            }
        }
    }
}

private data class OverviewCardData(
    val labelRes: Int,
    val value: Long,
    val icon: ImageVector,
)

@Composable
private fun OverviewCard(data: OverviewCardData, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(data.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                text = data.value.toString(),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(data.labelRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun QuickActions(
    onCreateEncounter: () -> Unit,
    onCreateMedication: () -> Unit,
) {
    BoxWithConstraints {
        if (maxWidth >= 520.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCreateEncounter, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.new_encounter), modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = onCreateMedication, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.new_medication), modifier = Modifier.padding(start = 8.dp))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreateEncounter, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.new_encounter), modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = onCreateMedication, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.new_medication), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    uiState: HomeUiState,
    onOpenEncounter: (String) -> Unit,
    onOpenMedication: (String) -> Unit,
) {
    if (uiState.isSearchLoading) {
        LoadingState(modifier = Modifier.height(240.dp), labelRes = R.string.searching)
        return
    }
    val results = uiState.searchResults
    if (results == null || (results.encounters.isEmpty() && results.medications.isEmpty())) {
        EmptyState(
            titleRes = R.string.search_empty_title,
            bodyRes = R.string.search_empty_body,
            modifier = Modifier.height(320.dp),
            icon = Icons.Outlined.Search,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (results.encounters.isNotEmpty()) {
            Text(
                text = stringResource(R.string.search_encounters_group),
                style = MaterialTheme.typography.titleMedium,
            )
            results.encounters.forEach { EncounterResultRow(it, onOpenEncounter) }
        }
        if (results.medications.isNotEmpty()) {
            Text(
                text = stringResource(R.string.search_medications_group),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            results.medications.forEach { MedicationResultRow(it, onOpenMedication) }
        }
    }
}

@Composable
private fun EncounterResultRow(encounter: Encounter, onOpen: (String) -> Unit) {
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
        leadingContent = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
        modifier = Modifier.clickable { onOpen(encounter.id) },
    )
}

@Composable
private fun MedicationResultRow(medication: Medication, onOpen: (String) -> Unit) {
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
        modifier = Modifier.clickable { onOpen(medication.id) },
    )
}

@Composable
private fun HomeSectionHeader(
    title: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = action,
            modifier = Modifier
                .clickable(onClick = onAction)
                .padding(12.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CompactEmptyCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
