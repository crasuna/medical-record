package com.loveluke.medicalrecord.feature.encounter

import android.net.Uri
import com.loveluke.medicalrecord.core.attachment.AttachmentDeleteResult
import com.loveluke.medicalrecord.core.attachment.AttachmentDeletionRollbackState
import com.loveluke.medicalrecord.core.attachment.AttachmentPreviewResult
import com.loveluke.medicalrecord.core.attachment.AttachmentQuarantineReason
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceBatchRejection
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceFailure
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceImportResult
import com.loveluke.medicalrecord.core.attachment.AttachmentServiceItemResult
import com.loveluke.medicalrecord.core.attachment.CameraCaptureFailure
import com.loveluke.medicalrecord.core.attachment.CameraCapturePreparation
import com.loveluke.medicalrecord.core.model.AttachmentIntegrityState
import com.loveluke.medicalrecord.core.model.EncounterDetails
import com.loveluke.medicalrecord.core.security.SecureMaterialFailure
import com.loveluke.medicalrecord.feature.FakeEncounterRepository
import com.loveluke.medicalrecord.feature.FakeEncryptedAttachmentService
import com.loveluke.medicalrecord.feature.MainDispatcherRule
import com.loveluke.medicalrecord.feature.attachment
import com.loveluke.medicalrecord.feature.encounter
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun completedImportMapsEveryItemWithoutHidingFailures() =
        runTest(mainDispatcherRule.dispatcher) {
            val service = FakeEncryptedAttachmentService()
            val importedAttachment = attachment(displayName = "visit-scan.jpg")
            service.importUrisResult = AttachmentServiceImportResult.Completed(
                listOf(
                    AttachmentServiceItemResult.Imported(0, importedAttachment),
                    AttachmentServiceItemResult.Failed(1, AttachmentServiceFailure.TOO_LARGE),
                ),
            )
            val viewModel = AttachmentCoordinatorViewModel(Provider { service })
            val uris = listOf(
                Uri.parse("content://picker/first"),
                Uri.parse("content://picker/second"),
            )

            viewModel.importSelection(
                patientId = importedAttachment.patientId,
                encounterId = importedAttachment.encounterId,
                selection = AttachmentSelection.PickedImages(uris),
            )
            runCurrent()

            val report = requireNotNull(viewModel.uiState.value.report)
            assertNull(report.batchError)
            assertEquals(2, report.items.size)
            assertTrue(report.items[0].imported)
            assertEquals("visit-scan.jpg", report.items[0].displayName)
            assertFalse(report.items[1].imported)
            assertEquals(AttachmentServiceFailure.TOO_LARGE, report.items[1].failure)
            assertEquals(listOf(uris), service.importedUriBatches)
            assertTrue(viewModel.uiState.value.controlsEnabled)
        }

    @Test
    fun rejectedAndFailClosedImportsRemainDistinctBatchFailures() =
        runTest(mainDispatcherRule.dispatcher) {
            val service = FakeEncryptedAttachmentService()
            val viewModel = AttachmentCoordinatorViewModel(Provider { service })
            val uri = Uri.parse("content://picker/document")
            val patientId = UUID.randomUUID().toString()
            val encounterId = UUID.randomUUID().toString()

            service.importUrisResult = AttachmentServiceImportResult.Rejected(
                AttachmentServiceBatchRejection.TOO_MANY_ITEMS,
            )
            viewModel.importSelection(
                patientId,
                encounterId,
                AttachmentSelection.PickedPdfs(listOf(uri)),
            )
            runCurrent()
            assertEquals(
                AttachmentImportBatchError.TOO_MANY_ITEMS,
                viewModel.uiState.value.report?.batchError,
            )

            service.importUrisResult = AttachmentServiceImportResult.FailClosed(
                SecureMaterialFailure.WRAPPING_KEY_MISSING,
            )
            viewModel.importSelection(
                patientId,
                encounterId,
                AttachmentSelection.PickedPdfs(listOf(uri)),
            )
            runCurrent()
            assertEquals(
                AttachmentImportBatchError.FAIL_CLOSED,
                viewModel.uiState.value.report?.batchError,
            )
        }

    @Test
    fun cameraPreparationFailureRestoresControlsAndDoesNotLaunchCamera() =
        runTest(mainDispatcherRule.dispatcher) {
            val service = FakeEncryptedAttachmentService().apply {
                cameraPreparationResult = CameraCapturePreparation.Failed(
                    CameraCaptureFailure.CONTENT_URI_UNAVAILABLE,
                )
            }
            val viewModel = AttachmentCoordinatorViewModel(Provider { service })

            viewModel.prepareCameraCapture(
                patientId = UUID.randomUUID().toString(),
                encounterId = UUID.randomUUID().toString(),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isPreparingCamera)
            assertNull(state.cameraLaunchRequest)
            assertEquals(AttachmentImportBatchError.UNAVAILABLE, state.report?.batchError)
            assertTrue(state.controlsEnabled)
        }

    @Test
    fun authenticationFailureFromPreviewServiceShowsQuarantineState() =
        runTest(mainDispatcherRule.dispatcher) {
            val encounter = encounter()
            val attachment = attachment(encounterId = encounter.id)
            val repository = FakeEncounterRepository(
                initialDetails = EncounterDetails(encounter, listOf(attachment)),
            )
            val service = FakeEncryptedAttachmentService().apply {
                previewResult = AttachmentPreviewResult.Quarantined(
                    reason = AttachmentQuarantineReason.AUTHENTICATION_FAILED,
                    metadataMarked = true,
                )
            }
            val viewModel = AttachmentPreviewViewModel(repository, Provider { service })

            viewModel.load(attachment.patientId, encounter.id, attachment.id)
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertTrue(state.isQuarantined)
            assertFalse(state.hasError)
            assertNull(state.plaintextPath)
            assertEquals(attachment.id, state.attachment?.id)
        }

    @Test
    fun successfulAttachmentDeleteEmitsDeletedOnlyAfterCiphertextServiceSucceeds() =
        runTest(mainDispatcherRule.dispatcher) {
            val encounter = encounter()
            val attachment = attachment(
                encounterId = encounter.id,
                integrityState = AttachmentIntegrityState.QUARANTINED,
            )
            val repository = FakeEncounterRepository(
                initialDetails = EncounterDetails(encounter, listOf(attachment)),
            )
            val service = FakeEncryptedAttachmentService()
            val viewModel = AttachmentPreviewViewModel(repository, Provider { service })
            viewModel.load(attachment.patientId, encounter.id, attachment.id)
            runCurrent()
            val deletedEvent = backgroundScope.async { viewModel.events.first() }
            runCurrent()

            viewModel.onAction(AttachmentPreviewAction.RequestDelete)
            assertTrue(viewModel.uiState.value.showDeleteConfirmation)
            viewModel.onAction(AttachmentPreviewAction.ConfirmDelete)
            runCurrent()

            assertEquals(AttachmentPreviewEvent.Deleted, deletedEvent.await())
            assertEquals(listOf(attachment), service.deletedAttachments)
        }

    @Test
    fun ciphertextDeleteFailureKeepsAttachmentAndDoesNotEmitDeleted() =
        runTest(mainDispatcherRule.dispatcher) {
            val encounter = encounter()
            val attachment = attachment(
                encounterId = encounter.id,
                integrityState = AttachmentIntegrityState.QUARANTINED,
            )
            val repository = FakeEncounterRepository(
                initialDetails = EncounterDetails(encounter, listOf(attachment)),
            )
            val service = FakeEncryptedAttachmentService().apply {
                attachmentDeleteResult = AttachmentDeleteResult.CiphertextDeleteFailed(
                    ciphertextFilesStaged = 1,
                    rollbackState = AttachmentDeletionRollbackState.COMPLETE,
                    tombstoneFilesRetained = 0,
                )
            }
            val viewModel = AttachmentPreviewViewModel(repository, Provider { service })
            viewModel.load(attachment.patientId, encounter.id, attachment.id)
            runCurrent()
            val deletedEvent = backgroundScope.async { viewModel.events.first() }
            runCurrent()

            viewModel.onAction(AttachmentPreviewAction.RequestDelete)
            viewModel.onAction(AttachmentPreviewAction.ConfirmDelete)
            runCurrent()

            assertFalse(deletedEvent.isCompleted)
            assertFalse(viewModel.uiState.value.isDeleting)
            assertTrue(viewModel.uiState.value.hasError)
            assertEquals(listOf(attachment), service.deletedAttachments)
        }
}
