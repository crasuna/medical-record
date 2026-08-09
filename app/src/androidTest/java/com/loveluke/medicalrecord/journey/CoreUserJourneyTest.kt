package com.loveluke.medicalrecord.journey

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loveluke.medicalrecord.app.MainActivity
import com.loveluke.medicalrecord.app.testing.MedicalRecordTestTags
import com.loveluke.medicalrecord.e2e.E2eFixture
import com.loveluke.medicalrecord.test.CoreJourney
import com.loveluke.medicalrecord.test.CoreJourneyTest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@CoreJourney
class CoreUserJourneyTest : CoreJourneyTest() {
    @get:Rule(order = 0)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val fixture: E2eFixture by lazy {
        E2eFixture.from(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun coldStartAndCoreNavigationRoundTrip() {
        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
        assertVisibleText("医疗记录")
        assertSelectedNavigation(MedicalRecordTestTags.NAV_HOME)

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_ENCOUNTERS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTERS)
        assertVisibleText("就诊记录")
        assertSelectedNavigation(MedicalRecordTestTags.NAV_ENCOUNTERS)

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_MEDICATIONS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATIONS)
        assertVisibleText("用药管理")
        assertSelectedNavigation(MedicalRecordTestTags.NAV_MEDICATIONS)

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_HOME).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
        assertSelectedNavigation(MedicalRecordTestTags.NAV_HOME)

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_ENCOUNTERS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTERS)
        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_HOME).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
    }

    @Test
    fun encounterCreateRecreateEditAndPersist() {
        val marker = marker("encounter-edit")
        val originalHospital = "海港医院 $marker"
        val editedHospital = "海港医院东院 $marker"
        val originalDiagnosis = "原始诊断 $marker"
        val editedDiagnosis = "复诊诊断 $marker"

        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_ENCOUNTERS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTERS)
        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_NEW).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_EDITOR)

        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_VISIT_DATE, "2030-06-15")
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_VISIT_TIME, "09:25")
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_HOSPITAL, originalHospital)
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_DEPARTMENT, "心内科 $marker")
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_DOCTOR, "林医生 $marker")
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_CHIEF_COMPLAINT, "胸闷 $marker")
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_DIAGNOSIS, originalDiagnosis)
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_DISPOSITION, "观察随访 $marker")
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_NOTES, "保留字段 $marker")
        saveFromForm(
            MedicalRecordTestTags.ENCOUNTER_EDITOR_FORM,
            MedicalRecordTestTags.ENCOUNTER_SAVE,
        )

        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        val created = runBlocking {
            fixture.awaitEncounter(marker) { encounter ->
                encounter.hospital == originalHospital &&
                    encounter.diagnosis == originalDiagnosis
            }
        }
        assertEncounterHospitalVisible(originalHospital)
        assertEquals(originalDiagnosis, created.diagnosis)
        assertEquals("林医生 $marker", created.doctor)
        assertEquals("观察随访 $marker", created.disposition)

        composeRule.activityRule.scenario.recreate()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        assertEncounterHospitalVisible(originalHospital)

        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_EDIT).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_EDITOR)
        assertEncounterFieldContains(MedicalRecordTestTags.ENCOUNTER_DOCTOR, "林医生 $marker")
        assertEncounterFieldContains(MedicalRecordTestTags.ENCOUNTER_NOTES, "保留字段 $marker")
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_HOSPITAL, editedHospital)
        replaceEncounterField(MedicalRecordTestTags.ENCOUNTER_DIAGNOSIS, editedDiagnosis)
        saveFromForm(
            MedicalRecordTestTags.ENCOUNTER_EDITOR_FORM,
            MedicalRecordTestTags.ENCOUNTER_SAVE,
        )

        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        val edited = runBlocking {
            fixture.awaitEncounter(marker) { encounter ->
                encounter.hospital == editedHospital && encounter.diagnosis == editedDiagnosis
            }
        }
        assertEncounterHospitalVisible(editedHospital)
        assertEquals(created.id, edited.id)
        assertEquals(editedDiagnosis, edited.diagnosis)
        assertEquals("林医生 $marker", edited.doctor)
        assertEquals("观察随访 $marker", edited.disposition)
        assertEquals("保留字段 $marker", edited.notes)

        composeRule.activityRule.scenario.recreate()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        assertEncounterHospitalVisible(editedHospital)
        scrollEncounterDetailToText("保留字段 $marker")
        assertVisibleText("保留字段 $marker")
    }

    @Test
    fun encounterDeleteCancelThenConfirmRemovesEveryProjection() {
        val marker = marker("encounter-delete")
        val encounter = runBlocking {
            fixture.awaitReady()
            fixture.seedEncounter(marker)
        }

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_ENCOUNTERS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTERS)
        waitForText(encounter.hospital)
        composeRule.onNodeWithText(encounter.hospital).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)

        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_DELETE).performClick()
        assertVisibleText("删除这条就诊记录？")
        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_DELETE_CANCEL).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        assertNotNull(runBlocking { fixture.findEncounter(marker) })

        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_DELETE).performClick()
        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_DELETE_CONFIRM).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTERS)
        runBlocking { fixture.awaitEncounterAbsent(marker) }
        composeRule.onAllNodesWithText(encounter.hospital).assertCountEquals(0)

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_HOME).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
        composeRule.onAllNodesWithText(encounter.hospital).assertCountEquals(0)
        replaceText(MedicalRecordTestTags.HOME_SEARCH, marker)
        waitForText("没有匹配记录")
        assertNull(runBlocking { fixture.findEncounter(marker) })
    }

    @Test
    fun medicationCreateTwoRemindersRecreateAndFilterCourses() {
        val currentMarker = marker("medication-current")
        val upcomingMarker = marker("medication-upcoming")
        val endedMarker = marker("medication-ended")
        val currentName = "当前药 $currentMarker"
        val upcomingName = "未来药 $upcomingMarker"
        val endedName = "结束药 $endedMarker"
        runBlocking {
            fixture.awaitReady()
            fixture.seedMedication(
                marker = upcomingMarker,
                startDate = fixture.today.plusDays(2),
                endDate = null,
                name = upcomingName,
            )
            fixture.seedMedication(
                marker = endedMarker,
                startDate = fixture.today.minusDays(10),
                endDate = fixture.today.minusDays(1),
                name = endedName,
            )
        }

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_MEDICATIONS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATIONS)
        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_NEW).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_EDITOR)
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_NAME, currentName)
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_DOSE, "10 mg $currentMarker")
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_FREQUENCY, "每日两次")
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_START_DATE, "2030-06-15")
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_END_DATE, "2030-06-20")
        replaceMedicationField(MedicalRecordTestTags.MEDICATION_NOTES, "饭后服用 $currentMarker")

        scrollFormTo(
            MedicalRecordTestTags.MEDICATION_EDITOR_FORM,
            MedicalRecordTestTags.REMINDER_ADD,
        )
        composeRule.onNodeWithTag(MedicalRecordTestTags.REMINDER_ADD).performClick()
        composeRule.onNodeWithTag(MedicalRecordTestTags.REMINDER_ADD).performClick()
        composeRule.waitForIdle()
        val firstReminderTag = MedicalRecordTestTags.reminderTime(0)
        val secondReminderTag = MedicalRecordTestTags.reminderTime(1)
        scrollFormTo(MedicalRecordTestTags.MEDICATION_EDITOR_FORM, firstReminderTag)
        composeRule.onNodeWithTag(firstReminderTag).performTextReplacement("07:30")
        scrollFormTo(MedicalRecordTestTags.MEDICATION_EDITOR_FORM, secondReminderTag)
        composeRule.onNodeWithTag(secondReminderTag).performTextReplacement("21:15")
        saveFromForm(
            MedicalRecordTestTags.MEDICATION_EDITOR_FORM,
            MedicalRecordTestTags.MEDICATION_SAVE,
        )

        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL)
        assertMedicationDetailTextVisible(currentName)
        assertMedicationDetailTextVisible("07:30")
        assertMedicationDetailTextVisible("21:15")
        val created = runBlocking { fixture.awaitMedication(currentMarker) }
        assertEquals(listOf(7 * 60 + 30, 21 * 60 + 15), created.reminders.map { it.timeMinutesOfDay })

        composeRule.activityRule.scenario.recreate()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL)
        assertMedicationDetailTextVisible(currentName)
        pressBack()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATIONS)

        assertFilterShows(
            tag = MedicalRecordTestTags.MEDICATION_FILTER_CURRENT,
            included = listOf(currentName),
            excluded = listOf(upcomingName, endedName),
        )
        assertFilterShows(
            tag = MedicalRecordTestTags.MEDICATION_FILTER_UPCOMING,
            included = listOf(upcomingName),
            excluded = listOf(currentName, endedName),
        )
        assertFilterShows(
            tag = MedicalRecordTestTags.MEDICATION_FILTER_ENDED,
            included = listOf(endedName),
            excluded = listOf(currentName, upcomingName),
        )
        assertFilterShows(
            tag = MedicalRecordTestTags.MEDICATION_FILTER_ALL,
            included = listOf(currentName, upcomingName, endedName),
            excluded = emptyList(),
        )
    }

    @Test
    fun medicationDeleteCancelThenConfirmRemovesHomeAndReminderProjection() {
        val marker = marker("medication-delete")
        val seeded = runBlocking {
            fixture.awaitReady()
            fixture.seedMedication(
                marker = marker,
                startDate = fixture.today,
                endDate = fixture.today.plusDays(5),
                reminderMinutes = listOf(8 * 60, 20 * 60),
            )
        }
        assertEquals(2, seeded.reminders.size)

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_MEDICATIONS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATIONS)
        waitForText(seeded.medication.name)
        composeRule.onNodeWithText(seeded.medication.name).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL)

        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_DELETE).performClick()
        assertVisibleText("删除这条用药？")
        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_DELETE_CANCEL).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL)
        assertEquals(2, runBlocking { fixture.findMedication(marker) }?.reminders?.size)

        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_DELETE).performClick()
        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_DELETE_CONFIRM).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATIONS)
        runBlocking {
            fixture.awaitMedicationAbsent(marker)
            fixture.reconcileReminders()
        }
        composeRule.onAllNodesWithText(seeded.medication.name).assertCountEquals(0)

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_HOME).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
        composeRule.onAllNodesWithText(seeded.medication.name).assertCountEquals(0)
        replaceText(MedicalRecordTestTags.HOME_SEARCH, marker)
        waitForText("没有匹配记录")
        assertNull(runBlocking { fixture.findMedication(marker) })
    }

    @Test
    fun homeSearchOpensBothKindsAndKeepsQueryAfterReturn() {
        val marker = marker("home-search")
        val encounter = runBlocking {
            fixture.awaitReady()
            fixture.seedEncounter(marker = marker, hospital = "搜索医院 $marker")
        }
        val medication = runBlocking {
            fixture.seedMedication(
                marker = marker,
                startDate = fixture.today,
                endDate = null,
                name = "搜索药品 $marker",
            )
        }

        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
        replaceText(MedicalRecordTestTags.HOME_SEARCH, marker)
        waitForText(encounter.hospital)
        waitForText(medication.medication.name)
        assertVisibleText("就诊记录")
        assertVisibleText("用药记录")

        composeRule.onNodeWithText(encounter.hospital).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        assertEncounterDetailTextVisible(encounter.hospital)
        returnFromDetailToHome()
        assertSearchState(marker, encounter.hospital, medication.medication.name)

        composeRule.onNodeWithText(medication.medication.name).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL)
        assertMedicationDetailTextVisible(medication.medication.name)
        returnFromDetailToHome()
        assertSearchState(marker, encounter.hospital, medication.medication.name)
    }

    @Test
    fun encryptedJpegPreviewAndDeleteKeepsMetadataAndCiphertextConsistent() {
        val marker = marker("attachment")
        val seeded = runBlocking {
            fixture.awaitReady()
            fixture.seedEncounter(marker)
        }
        val attachment = runBlocking { fixture.importEncryptedJpeg(seeded.id, marker) }
        val ciphertext = fixture.encryptedAttachmentFile(attachment)
        assertTrue(ciphertext.isFile)
        val encryptedBytes = ciphertext.readBytes()
        assertTrue(encryptedBytes.size > 32)
        assertFalse(
            "Ciphertext must not retain the JPEG file signature at byte zero.",
            encryptedBytes[0] == 0xFF.toByte() && encryptedBytes[1] == 0xD8.toByte(),
        )

        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_ENCOUNTERS).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTERS)
        waitForText(seeded.hospital)
        composeRule.onNodeWithText(seeded.hospital).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        scrollEncounterDetailToText(attachment.displayName)
        composeRule.onNodeWithText(attachment.displayName).performClick()

        waitForScreen(MedicalRecordTestTags.SCREEN_ATTACHMENT_PREVIEW)
        waitForText(attachment.displayName)
        composeRule.onNodeWithText(attachment.displayName).assertExists()
        waitForContentDescription(attachment.displayName)
        composeRule.onNodeWithContentDescription(attachment.displayName).assertIsDisplayed()
        composeRule.onNodeWithTag(MedicalRecordTestTags.ATTACHMENT_DELETE).performClick()
        assertVisibleText("删除附件？")
        composeRule.onNodeWithTag(MedicalRecordTestTags.ATTACHMENT_DELETE_CONFIRM).performClick()

        waitForScreen(MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL)
        runBlocking { fixture.awaitAttachmentAbsent(seeded.id, attachment.id) }
        assertFalse(ciphertext.exists())
        assertTrue(runBlocking { fixture.encounterDetails(seeded.id) }?.attachments?.isEmpty() == true)
        composeRule.onAllNodesWithText(attachment.displayName).assertCountEquals(0)
    }

    private fun replaceEncounterField(tag: String, value: String) {
        scrollFormTo(MedicalRecordTestTags.ENCOUNTER_EDITOR_FORM, tag)
        replaceText(tag, value)
    }

    private fun replaceMedicationField(tag: String, value: String) {
        scrollFormTo(MedicalRecordTestTags.MEDICATION_EDITOR_FORM, tag)
        replaceText(tag, value)
    }

    private fun replaceText(tag: String, value: String) {
        composeRule.onNodeWithTag(tag).performTextReplacement(value)
        composeRule.waitForIdle()
    }

    private fun scrollFormTo(formTag: String, controlTag: String) {
        composeRule.onNodeWithTag(formTag).performScrollToNode(hasTestTag(controlTag))
    }

    private fun saveFromForm(formTag: String, saveTag: String) {
        scrollFormTo(formTag, saveTag)
        composeRule.onNodeWithTag(saveTag)
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
    }

    private fun assertEncounterFieldContains(tag: String, value: String) {
        scrollFormTo(MedicalRecordTestTags.ENCOUNTER_EDITOR_FORM, tag)
        composeRule.onNodeWithTag(tag).assertTextContains(value)
    }

    private fun assertFilterShows(tag: String, included: List<String>, excluded: List<String>) {
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeRule.onNodeWithTag(tag).assertIsSelected()
        included.forEach(::waitForText)
        excluded.forEach { text -> composeRule.onAllNodesWithText(text).assertCountEquals(0) }
    }

    private fun returnFromDetailToHome() {
        pressBack()
        waitForEitherScreen(
            MedicalRecordTestTags.SCREEN_ENCOUNTERS,
            MedicalRecordTestTags.SCREEN_MEDICATIONS,
        )
        composeRule.onNodeWithTag(MedicalRecordTestTags.NAV_HOME).performClick()
        waitForScreen(MedicalRecordTestTags.SCREEN_HOME)
    }

    private fun assertSearchState(marker: String, encounterText: String, medicationText: String) {
        waitForTag(MedicalRecordTestTags.HOME_SEARCH)
        composeRule.onNodeWithTag(MedicalRecordTestTags.HOME_SEARCH).assertTextContains(marker)
        waitForText(encounterText)
        waitForText(medicationText)
    }

    private fun scrollEncounterDetailToText(text: String) {
        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_DETAIL_CONTENT)
            .performScrollToNode(androidx.compose.ui.test.hasText(text))
    }

    private fun assertEncounterHospitalVisible(text: String) {
        composeRule.onNodeWithTag(MedicalRecordTestTags.ENCOUNTER_DETAIL_CONTENT)
            .performScrollToIndex(0)
        waitForText(text)
        assertVisibleText(text)
    }

    private fun assertEncounterDetailTextVisible(text: String) {
        scrollEncounterDetailToText(text)
        assertVisibleText(text)
    }

    private fun scrollMedicationDetailToText(text: String) {
        composeRule.onNodeWithTag(MedicalRecordTestTags.MEDICATION_DETAIL_CONTENT)
            .performScrollToNode(androidx.compose.ui.test.hasText(text))
    }

    private fun assertMedicationDetailTextVisible(text: String) {
        scrollMedicationDetailToText(text)
        assertVisibleText(text)
    }

    private fun assertSelectedNavigation(tag: String) {
        composeRule.onNodeWithTag(tag)
            .assertIsSelected()
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Role,
                    Role.Tab,
                ),
            )
    }

    private fun waitForScreen(tag: String) {
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
        val readinessTag = when (tag) {
            MedicalRecordTestTags.SCREEN_HOME -> MedicalRecordTestTags.HOME_SEARCH
            MedicalRecordTestTags.SCREEN_ENCOUNTERS -> MedicalRecordTestTags.ENCOUNTER_NEW
            MedicalRecordTestTags.SCREEN_ENCOUNTER_DETAIL -> MedicalRecordTestTags.ENCOUNTER_DETAIL_CONTENT
            MedicalRecordTestTags.SCREEN_ENCOUNTER_EDITOR -> MedicalRecordTestTags.ENCOUNTER_EDITOR_FORM
            MedicalRecordTestTags.SCREEN_MEDICATIONS -> MedicalRecordTestTags.MEDICATION_FILTER_CURRENT
            MedicalRecordTestTags.SCREEN_MEDICATION_DETAIL -> MedicalRecordTestTags.MEDICATION_DETAIL_CONTENT
            MedicalRecordTestTags.SCREEN_MEDICATION_EDITOR -> MedicalRecordTestTags.MEDICATION_EDITOR_FORM
            MedicalRecordTestTags.SCREEN_ATTACHMENT_PREVIEW -> MedicalRecordTestTags.ATTACHMENT_DELETE
            else -> null
        }
        if (readinessTag != null) {
            waitForTag(readinessTag)
        }
    }

    private fun waitForEitherScreen(first: String, second: String) {
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(first).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag(second).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentDescription(description: String) {
        composeRule.waitUntil(WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertVisibleText(text: String) {
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun marker(prefix: String): String =
        "CJ-$prefix-${UUID.randomUUID().toString().take(8)}"

    companion object {
        private const val WAIT_TIMEOUT_MILLIS = 20_000L
    }
}
