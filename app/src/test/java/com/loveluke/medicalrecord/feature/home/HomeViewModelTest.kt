package com.loveluke.medicalrecord.feature.home

import androidx.lifecycle.SavedStateHandle
import com.loveluke.medicalrecord.core.model.GlobalSearchResults
import com.loveluke.medicalrecord.feature.FakeHomeRepository
import com.loveluke.medicalrecord.feature.FakePatientRepository
import com.loveluke.medicalrecord.feature.MainDispatcherRule
import com.loveluke.medicalrecord.feature.encounter
import com.loveluke.medicalrecord.feature.homeOverview
import com.loveluke.medicalrecord.feature.medication
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun overviewBecomesReadyForTheStableDefaultPatient() = runTest(mainDispatcherRule.dispatcher) {
        val overview = homeOverview()
        val viewModel = HomeViewModel(
            patientRepository = FakePatientRepository(),
            homeRepository = FakeHomeRepository(initialOverview = overview),
            savedStateHandle = SavedStateHandle(),
        )

        runCurrent()

        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasError)
        assertEquals(overview, viewModel.uiState.value.overview)
    }

    @Test
    fun searchWaitsForSettledInputAndReturnsBothResultGroups() = runTest(mainDispatcherRule.dispatcher) {
        val searchResults = GlobalSearchResults(
            query = "clinic",
            encounters = listOf(encounter()),
            medications = listOf(medication()),
        )
        val repository = FakeHomeRepository(initialSearchResults = searchResults)
        val viewModel = HomeViewModel(
            patientRepository = FakePatientRepository(),
            homeRepository = repository,
            savedStateHandle = SavedStateHandle(),
        )
        runCurrent()

        viewModel.onAction(HomeAction.QueryChanged("cli"))
        advanceTimeBy(150)
        viewModel.onAction(HomeAction.QueryChanged("clinic"))
        advanceTimeBy(299)
        runCurrent()

        assertTrue(repository.queries.isEmpty())
        assertTrue(viewModel.uiState.value.isSearchLoading)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("clinic"), repository.queries)
        assertEquals(searchResults.encounters, viewModel.uiState.value.searchResults?.encounters)
        assertEquals(searchResults.medications, viewModel.uiState.value.searchResults?.medications)
        assertFalse(viewModel.uiState.value.isSearchLoading)
    }

    @Test
    fun searchQueryAndResultsRestoreWhenTheViewModelIsRecreated() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedStateHandle = SavedStateHandle()
            val searchResults = GlobalSearchResults(
                query = "clinic",
                encounters = listOf(encounter()),
                medications = listOf(medication()),
            )
            val first = HomeViewModel(
                patientRepository = FakePatientRepository(),
                homeRepository = FakeHomeRepository(initialSearchResults = searchResults),
                savedStateHandle = savedStateHandle,
            )
            runCurrent()

            first.onAction(HomeAction.QueryChanged("clinic"))
            advanceTimeBy(SEARCH_DEBOUNCE_MILLIS_FOR_TEST)
            runCurrent()

            val restoredRepository = FakeHomeRepository(initialSearchResults = searchResults)
            val restored = HomeViewModel(
                patientRepository = FakePatientRepository(),
                homeRepository = restoredRepository,
                savedStateHandle = savedStateHandle,
            )
            runCurrent()

            assertEquals("clinic", restored.uiState.value.query)
            assertTrue(restored.uiState.value.isSearchLoading)

            advanceTimeBy(SEARCH_DEBOUNCE_MILLIS_FOR_TEST)
            runCurrent()

            assertEquals(listOf("clinic"), restoredRepository.queries)
            assertEquals(searchResults, restored.uiState.value.searchResults)
            assertFalse(restored.uiState.value.isSearchLoading)
        }

    @Test
    fun retryRecoversAfterDefaultPatientLookupError() = runTest(mainDispatcherRule.dispatcher) {
        val patientRepository = FakePatientRepository().apply { failuresRemaining = 1 }
        val overview = homeOverview()
        val viewModel = HomeViewModel(
            patientRepository = patientRepository,
            homeRepository = FakeHomeRepository(initialOverview = overview),
            savedStateHandle = SavedStateHandle(),
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasError)
        assertEquals(null, viewModel.uiState.value.overview)

        viewModel.onAction(HomeAction.Retry)
        runCurrent()

        assertFalse(viewModel.uiState.value.hasError)
        assertEquals(overview, viewModel.uiState.value.overview)
        assertEquals(2, patientRepository.ensureCalls)
    }

    @Test
    fun resumeRefreshRequeriesOverviewWithTheNewCalendarDate() =
        runTest(mainDispatcherRule.dispatcher) {
            var today = LocalDate.of(2026, 8, 8)
            val repository = FakeHomeRepository()
            val viewModel = HomeViewModel(
                patientRepository = FakePatientRepository(),
                homeRepository = repository,
                todayProvider = { today },
                savedStateHandle = SavedStateHandle(),
            )
            runCurrent()

            today = LocalDate.of(2026, 8, 9)
            viewModel.onAction(HomeAction.Refresh)
            runCurrent()

            assertEquals(
                listOf(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9)),
                repository.observedDates,
            )
        }
}

private const val SEARCH_DEBOUNCE_MILLIS_FOR_TEST = 300L
