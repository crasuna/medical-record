package com.loveluke.medicalrecord.feature.medication

import com.loveluke.medicalrecord.core.model.MedicationFilter
import com.loveluke.medicalrecord.core.model.MedicationWithReminders
import com.loveluke.medicalrecord.feature.FakeMedicationRepository
import com.loveluke.medicalrecord.feature.FakePatientRepository
import com.loveluke.medicalrecord.feature.MainDispatcherRule
import com.loveluke.medicalrecord.feature.medication
import com.loveluke.medicalrecord.feature.reminder
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MedicationViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun emptyListEmissionStopsLoading() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = MedicationListViewModel(
                FakePatientRepository(),
                FakeMedicationRepository(initialMedications = emptyList()),
            )

            runCurrent()

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.medications.isEmpty())
        }

    @Test
    fun listStartsCurrentAndRequeriesWhenUserChangesFilter() =
        runTest(mainDispatcherRule.dispatcher) {
            val medication = medication()
            val repository = FakeMedicationRepository(initialMedications = listOf(medication))
            val viewModel = MedicationListViewModel(FakePatientRepository(), repository)
            runCurrent()

            assertEquals(MedicationFilter.CURRENT, viewModel.uiState.value.filter)
            assertEquals(listOf(medication), viewModel.uiState.value.medications)

            viewModel.onAction(MedicationListAction.SelectFilter(MedicationFilter.ENDED))
            runCurrent()

            assertEquals(MedicationFilter.ENDED, viewModel.uiState.value.filter)
            assertEquals(
                listOf(MedicationFilter.CURRENT, MedicationFilter.ENDED),
                repository.observedFilters,
            )
        }

    @Test
    fun resumeRefreshKeepsFilterAndRequeriesWithTheNewCalendarDate() =
        runTest(mainDispatcherRule.dispatcher) {
            var today = LocalDate.of(2026, 8, 8)
            val repository = FakeMedicationRepository()
            val viewModel = MedicationListViewModel(
                FakePatientRepository(),
                repository,
                todayProvider = { today },
            )
            runCurrent()

            today = LocalDate.of(2026, 8, 9)
            viewModel.onAction(MedicationListAction.Refresh)
            runCurrent()

            assertEquals(MedicationFilter.CURRENT, viewModel.uiState.value.filter)
            assertEquals(
                listOf(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 9)),
                repository.observedDates,
            )
        }

    @Test
    fun deleteRequiresConfirmationAndEmitsDeletedAfterRepositorySuccess() =
        runTest(mainDispatcherRule.dispatcher) {
            val medication = medication()
            val repository = FakeMedicationRepository(
                initialDetails = MedicationWithReminders(medication, listOf(reminder())),
            )
            val viewModel = MedicationDetailViewModel(FakePatientRepository(), repository)
            viewModel.load(medication.id)
            runCurrent()

            viewModel.onAction(MedicationDetailAction.RequestDelete)
            assertTrue(viewModel.uiState.value.showDeleteConfirmation)
            viewModel.onAction(MedicationDetailAction.DismissDelete)
            assertFalse(viewModel.uiState.value.showDeleteConfirmation)

            val deletedEvent = backgroundScope.async { viewModel.events.first() }
            runCurrent()
            viewModel.onAction(MedicationDetailAction.RequestDelete)
            viewModel.onAction(MedicationDetailAction.ConfirmDelete)
            runCurrent()

            assertEquals(MedicationDetailEvent.Deleted, deletedEvent.await())
        }

    @Test
    fun editorValidatesDateAndReminderThenSavesSortedReminderIntent() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeMedicationRepository()
            val viewModel = MedicationEditorViewModel(FakePatientRepository(), repository)
            viewModel.load(null)
            viewModel.onAction(MedicationEditorAction.NameChanged("  Medicine A  "))
            viewModel.onAction(MedicationEditorAction.StartDateChanged("2026-08-10"))
            viewModel.onAction(MedicationEditorAction.EndDateChanged("2026-08-09"))
            viewModel.onAction(MedicationEditorAction.AddReminder)
            val firstReminderId = viewModel.uiState.value.reminders.single().stableId
            viewModel.onAction(
                MedicationEditorAction.ReminderTimeChanged(firstReminderId, "25:00"),
            )
            viewModel.onAction(MedicationEditorAction.Save)

            assertEquals(
                setOf(MedicationFormField.END_DATE, MedicationFormField.REMINDER_TIME),
                viewModel.uiState.value.invalidFields,
            )
            assertTrue(viewModel.uiState.value.reminders.single().isInvalid)
            assertTrue(repository.savedMedications.isEmpty())

            viewModel.onAction(MedicationEditorAction.EndDateChanged("2026-08-11"))
            viewModel.onAction(
                MedicationEditorAction.ReminderTimeChanged(firstReminderId, "09:30"),
            )
            viewModel.onAction(MedicationEditorAction.AddReminder)
            viewModel.onAction(MedicationEditorAction.AddReminder)
            val disposableReminderId = viewModel.uiState.value.reminders.last().stableId
            viewModel.onAction(MedicationEditorAction.RemoveReminder(disposableReminderId))
            val savedEvent = backgroundScope.async { viewModel.events.first() }
            runCurrent()
            viewModel.onAction(MedicationEditorAction.Save)
            runCurrent()

            val savedMedication = repository.savedMedications.single()
            assertEquals("Medicine A", savedMedication.name)
            assertEquals(listOf(8 * 60, 9 * 60 + 30), repository.savedReminderDrafts.map { it.timeMinutesOfDay })
            assertEquals(
                savedMedication.id,
                (savedEvent.await() as MedicationEditorEvent.Saved).medicationId,
            )
            assertFalse(viewModel.uiState.value.isSaving)
            assertTrue(viewModel.uiState.value.invalidFields.isEmpty())
        }

    @Test
    fun editorMergesDuplicateTimesWithoutDiscardingAnEnabledReminder() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeMedicationRepository()
            val viewModel = MedicationEditorViewModel(FakePatientRepository(), repository)
            viewModel.load(null)
            viewModel.onAction(MedicationEditorAction.NameChanged("Medicine A"))
            viewModel.onAction(MedicationEditorAction.AddReminder)
            viewModel.onAction(MedicationEditorAction.AddReminder)
            val reminders = viewModel.uiState.value.reminders
            viewModel.onAction(
                MedicationEditorAction.ReminderEnabledChanged(
                    stableId = reminders.first().stableId,
                    enabled = false,
                ),
            )

            viewModel.onAction(MedicationEditorAction.Save)
            runCurrent()

            assertEquals(1, repository.savedReminderDrafts.size)
            assertEquals(8 * 60, repository.savedReminderDrafts.single().timeMinutesOfDay)
            assertTrue(repository.savedReminderDrafts.single().enabledByUser)
        }
}
