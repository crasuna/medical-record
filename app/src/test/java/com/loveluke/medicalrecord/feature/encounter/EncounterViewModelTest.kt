package com.loveluke.medicalrecord.feature.encounter

import com.loveluke.medicalrecord.core.attachment.AttachmentDeletionRollbackState
import com.loveluke.medicalrecord.core.attachment.EncounterDeleteResult
import com.loveluke.medicalrecord.core.model.EncounterDetails
import com.loveluke.medicalrecord.feature.FakeEncounterRepository
import com.loveluke.medicalrecord.feature.FakeEncryptedAttachmentService
import com.loveluke.medicalrecord.feature.FakePatientRepository
import com.loveluke.medicalrecord.feature.MainDispatcherRule
import com.loveluke.medicalrecord.feature.encounter
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
import javax.inject.Provider

@OptIn(ExperimentalCoroutinesApi::class)
class EncounterViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun emptyListEmissionStopsLoading() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = EncounterListViewModel(
                FakePatientRepository(),
                FakeEncounterRepository(initialEncounters = emptyList()),
            )

            runCurrent()

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.encounters.isEmpty())
        }

    @Test
    fun deleteRequiresConfirmationAndEmitsDeletedOnlyAfterRepositorySuccess() =
        runTest(mainDispatcherRule.dispatcher) {
            val encounter = encounter()
            val repository = FakeEncounterRepository(
                initialDetails = EncounterDetails(encounter, emptyList()),
            )
            val attachmentService = FakeEncryptedAttachmentService()
            val viewModel = EncounterDetailViewModel(
                FakePatientRepository(),
                repository,
                Provider { attachmentService },
            )
            viewModel.load(encounter.id)
            runCurrent()

            viewModel.onAction(EncounterDetailAction.RequestDelete)
            assertTrue(viewModel.uiState.value.showDeleteConfirmation)

            viewModel.onAction(EncounterDetailAction.DismissDelete)
            assertFalse(viewModel.uiState.value.showDeleteConfirmation)

            val deletedEvent = backgroundScope.async { viewModel.events.first() }
            runCurrent()
            viewModel.onAction(EncounterDetailAction.RequestDelete)
            viewModel.onAction(EncounterDetailAction.ConfirmDelete)
            runCurrent()

            assertEquals(EncounterDetailEvent.Deleted, deletedEvent.await())
            assertEquals(encounter.id, attachmentService.deletedEncounter?.encounter?.id)
            assertFalse(viewModel.uiState.value.showDeleteConfirmation)
        }

    @Test
    fun ciphertextDeleteFailurePreservesEncounterAndDoesNotEmitDeleted() =
        runTest(mainDispatcherRule.dispatcher) {
            val encounter = encounter()
            val repository = FakeEncounterRepository(
                initialDetails = EncounterDetails(encounter, emptyList()),
            )
            val attachmentService = FakeEncryptedAttachmentService().apply {
                encounterDeleteResult = EncounterDeleteResult.CiphertextDeleteFailed(
                    ciphertextFilesStaged = 1,
                    rollbackState = AttachmentDeletionRollbackState.COMPLETE,
                    tombstoneFilesRetained = 0,
                )
            }
            val viewModel = EncounterDetailViewModel(
                FakePatientRepository(),
                repository,
                Provider { attachmentService },
            )
            viewModel.load(encounter.id)
            runCurrent()
            val deletedEvent = backgroundScope.async { viewModel.events.first() }
            runCurrent()

            viewModel.onAction(EncounterDetailAction.RequestDelete)
            viewModel.onAction(EncounterDetailAction.ConfirmDelete)
            runCurrent()

            assertFalse(deletedEvent.isCompleted)
            assertFalse(viewModel.uiState.value.isDeleting)
            assertTrue(viewModel.uiState.value.hasError)
            assertEquals(encounter.id, attachmentService.deletedEncounter?.encounter?.id)
        }

    @Test
    fun editorRejectsInvalidFieldsThenSavesNormalizedEncounter() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeEncounterRepository()
            val viewModel = EncounterEditorViewModel(FakePatientRepository(), repository)
            viewModel.load(null)
            viewModel.onAction(EncounterEditorAction.VisitDateChanged("2026-02-30"))
            viewModel.onAction(EncounterEditorAction.VisitTimeChanged("25:10"))
            viewModel.onAction(EncounterEditorAction.HospitalChanged("  "))
            viewModel.onAction(EncounterEditorAction.Save)

            assertEquals(
                setOf(
                    EncounterFormField.VISIT_DATE,
                    EncounterFormField.VISIT_TIME,
                    EncounterFormField.HOSPITAL,
                ),
                viewModel.uiState.value.invalidFields,
            )
            assertTrue(repository.savedEncounters.isEmpty())

            viewModel.onAction(EncounterEditorAction.VisitDateChanged("2026-08-08"))
            viewModel.onAction(EncounterEditorAction.VisitTimeChanged("09:05"))
            viewModel.onAction(EncounterEditorAction.HospitalChanged("  Harbor Clinic  "))
            val savedEvent = backgroundScope.async { viewModel.events.first() }
            runCurrent()
            viewModel.onAction(EncounterEditorAction.Save)
            runCurrent()

            val saved = repository.savedEncounters.single()
            assertEquals("Harbor Clinic", saved.hospital)
            assertEquals("09:05", saved.visitTime.toString())
            assertEquals(saved.id, (savedEvent.await() as EncounterEditorEvent.Saved).encounterId)
            assertFalse(viewModel.uiState.value.isSaving)
            assertTrue(viewModel.uiState.value.invalidFields.isEmpty())
        }
}
