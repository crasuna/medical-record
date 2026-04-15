package com.crasuna.medicalrecord

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class HomeFeatureUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreen_switchesFromOverviewToSearchResults() {
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            val overviewState = sampleOverviewState()
            val searchState = sampleSearchState(query = query)

            MedicalRecordTheme {
                HomeScreen(
                    uiState = if (query.isBlank()) overviewState else searchState,
                    onQueryChange = { query = it },
                    onCreateEncounter = {},
                    onCreateMedication = {},
                    onOpenEncounter = {},
                    onEditMedication = {},
                    onOpenEncounters = {},
                    onOpenMedications = {},
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("Overview").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Medication results").fetchSemanticsNodes().isEmpty())

        composeRule.onNode(hasSetTextAction()).performTextInput("vitamin")

        assertTrue(composeRule.onAllNodesWithText("Overview").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Medication results").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Vitamin D").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun homeScreen_searchResultsTriggerNavigationCallbacks() {
        var openedEncounterId: String? = null
        var openedMedicationId: String? = null

        composeRule.setContent {
            MedicalRecordTheme {
                HomeScreen(
                    uiState = sampleCombinedSearchState(),
                    onQueryChange = {},
                    onCreateEncounter = {},
                    onCreateMedication = {},
                    onOpenEncounter = { openedEncounterId = it },
                    onEditMedication = { openedMedicationId = it },
                    onOpenEncounters = {},
                    onOpenMedications = {},
                )
            }
        }

        composeRule.onNodeWithText("General Hospital encounter-search").performClick()
        composeRule.onNodeWithText("Vitamin D").performClick()

        composeRule.runOnIdle {
            assertEquals("encounter-search", openedEncounterId)
            assertEquals("med-search", openedMedicationId)
        }
    }

    private fun sampleOverviewState(): HomeUiState {
        return buildHomeUiState(
            encounters = listOf(
                sampleEncounter("encounter-1", "General Hospital encounter-1"),
            ),
            medications = listOf(
                sampleMedication("med-1", "Vitamin D"),
            ),
            query = "",
            today = LocalDate.of(2026, 4, 15),
        )
    }

    private fun sampleSearchState(query: String): HomeUiState {
        return HomeUiState(
            query = query,
            searchSections = listOf(
                GlobalSearchSection(
                    titleRes = R.string.search_results_medications,
                    results = listOf(GlobalSearchResult.Medication(sampleMedication("med-1", "Vitamin D"))),
                ),
            ),
        )
    }

    private fun sampleCombinedSearchState(): HomeUiState {
        return HomeUiState(
            query = "vi",
            searchSections = listOf(
                GlobalSearchSection(
                    titleRes = R.string.search_results_encounters,
                    results = listOf(
                        GlobalSearchResult.Encounter(
                            sampleEncounter("encounter-search", "General Hospital encounter-search"),
                        ),
                    ),
                ),
                GlobalSearchSection(
                    titleRes = R.string.search_results_medications,
                    results = listOf(GlobalSearchResult.Medication(sampleMedication("med-search", "Vitamin D"))),
                ),
            ),
        )
    }

    private fun sampleEncounter(id: String, hospital: String): EncounterWithAttachments {
        return EncounterWithAttachments(
            encounter = EncounterEntity(
                id = id,
                visitDate = LocalDate.of(2026, 4, 15),
                visitTime = null,
                hospital = hospital,
                department = "Internal Medicine",
                doctor = "Dr. Li",
                chiefComplaint = "Checkup",
                diagnosis = "Stable",
                disposition = null,
                notes = null,
            ),
            attachments = emptyList(),
        )
    }

    private fun sampleMedication(id: String, name: String): MedicationWithReminders {
        return MedicationWithReminders(
            medication = MedicationEntity(
                id = id,
                name = name,
                dose = "1 tablet",
                frequency = "Daily",
                startDate = LocalDate.of(2026, 4, 10),
                endDate = null,
                notes = "After dinner",
            ),
            reminders = emptyList(),
        )
    }
}
