package com.crasuna.medicalrecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class HomeFeatureTest {

    @Test
    fun `build home ui state computes overview recent items and todays reminders`() {
        val today = LocalDate.of(2026, 4, 15)
        val state = buildHomeUiState(
            encounters = listOf(
                encounterWithAttachments(
                    id = "encounter-older",
                    date = today.minusDays(10),
                    attachmentNames = listOf("older.pdf"),
                ),
                encounterWithAttachments(
                    id = "encounter-latest",
                    date = today,
                    time = LocalTime.of(9, 30),
                    attachmentNames = listOf("cbc.pdf", "summary.jpg"),
                ),
                encounterWithAttachments(
                    id = "encounter-middle",
                    date = today.minusDays(2),
                    attachmentNames = emptyList(),
                ),
                encounterWithAttachments(
                    id = "encounter-third",
                    date = today.minusDays(1),
                    attachmentNames = listOf("scan.pdf"),
                ),
            ),
            medications = listOf(
                medicationWithReminders(
                    id = "med-current",
                    name = "Metformin",
                    startDate = today.minusDays(5),
                    endDate = null,
                    reminderMinutes = listOf(8 * 60, 20 * 60),
                    createdAt = Instant.parse("2026-04-10T08:00:00Z"),
                ),
                medicationWithReminders(
                    id = "med-future",
                    name = "Future course",
                    startDate = today.plusDays(1),
                    endDate = null,
                    reminderMinutes = listOf(7 * 60),
                    createdAt = Instant.parse("2026-04-11T08:00:00Z"),
                ),
                medicationWithReminders(
                    id = "med-ended",
                    name = "Ended course",
                    startDate = today.minusDays(20),
                    endDate = today.minusDays(1),
                    reminderMinutes = listOf(12 * 60),
                    createdAt = Instant.parse("2026-04-01T08:00:00Z"),
                ),
            ),
            query = "",
            today = today,
        )

        assertEquals(4, state.overviewStats.totalEncounters)
        assertEquals(4, state.overviewStats.totalAttachments)
        assertEquals(2, state.overviewStats.currentMedicationCount)
        assertEquals(2, state.overviewStats.todayReminderCount)

        assertEquals(
            listOf("encounter-latest", "encounter-third", "encounter-middle"),
            state.recentEncounters.map { it.encounter.id },
        )
        assertEquals(
            listOf("med-future", "med-current"),
            state.currentMedications.map { it.medication.id },
        )
        assertEquals(
            listOf(8 * 60, 20 * 60),
            state.todayReminders.map { it.timeMinutesOfDay },
        )
        assertTrue(state.searchSections.isEmpty())
        assertFalse(state.isSearching)
    }

    @Test
    fun `build home ui state search is case insensitive and grouped by domain`() {
        val today = LocalDate.of(2026, 4, 15)
        val state = buildHomeUiState(
            encounters = listOf(
                encounterWithAttachments(
                    id = "encounter-1",
                    date = today,
                    diagnosis = "Iron deficiency",
                    notes = "Follow up in two weeks",
                    attachmentNames = listOf("CBC-REPORT.pdf"),
                ),
            ),
            medications = listOf(
                medicationWithReminders(
                    id = "med-1",
                    name = "Vitamin D",
                    frequency = "Nightly",
                    notes = "Take after dinner",
                    reminderMinutes = listOf(21 * 60),
                ),
            ),
            query = "  cBc  ",
            today = today,
        )

        assertTrue(state.isSearching)
        assertEquals(listOf(R.string.search_results_encounters), state.searchSections.map { it.titleRes })
        assertEquals("encounter-1", state.searchSections.first().results.single().id)

        val medicationSearchState = buildHomeUiState(
            encounters = listOf(
                encounterWithAttachments(
                    id = "encounter-1",
                    date = today,
                    attachmentNames = emptyList(),
                ),
            ),
            medications = listOf(
                medicationWithReminders(
                    id = "med-1",
                    name = "Vitamin D",
                    frequency = "Nightly",
                    notes = "Take after dinner",
                    reminderMinutes = listOf(21 * 60),
                ),
            ),
            query = "nightLY",
            today = today,
        )

        assertEquals(listOf(R.string.search_results_medications), medicationSearchState.searchSections.map { it.titleRes })
        assertEquals("med-1", medicationSearchState.searchSections.first().results.single().id)
    }

    @Test
    fun `build home ui state returns no result sections when nothing matches`() {
        val today = LocalDate.of(2026, 4, 15)
        val state = buildHomeUiState(
            encounters = listOf(
                encounterWithAttachments(
                    id = "encounter-1",
                    date = today,
                    diagnosis = "Flu",
                    attachmentNames = listOf("visit.pdf"),
                ),
            ),
            medications = listOf(
                medicationWithReminders(
                    id = "med-1",
                    name = "Ibuprofen",
                    reminderMinutes = listOf(8 * 60),
                ),
            ),
            query = "neurology",
            today = today,
        )

        assertTrue(state.isSearching)
        assertTrue(state.searchSections.isEmpty())
    }

    private fun encounterWithAttachments(
        id: String,
        date: LocalDate,
        time: LocalTime? = null,
        diagnosis: String? = null,
        notes: String? = null,
        attachmentNames: List<String>,
    ): EncounterWithAttachments {
        return EncounterWithAttachments(
            encounter = EncounterEntity(
                id = id,
                visitDate = date,
                visitTime = time,
                hospital = "General Hospital $id",
                department = "Internal Medicine",
                doctor = "Dr. Chen",
                chiefComplaint = "Checkup",
                diagnosis = diagnosis,
                disposition = null,
                notes = notes,
                createdAt = Instant.parse("2026-04-01T08:00:00Z"),
                updatedAt = Instant.parse("2026-04-01T08:00:00Z"),
            ),
            attachments = attachmentNames.mapIndexed { index, fileName ->
                EncounterAttachmentEntity(
                    id = "$id-attachment-$index",
                    encounterId = id,
                    type = AttachmentType.PDF,
                    displayName = fileName,
                    mimeType = "application/pdf",
                    encryptedPath = "/tmp/$fileName.enc",
                    thumbnailPath = null,
                    pageCount = 1,
                    createdAt = Instant.parse("2026-04-01T08:00:00Z"),
                    updatedAt = Instant.parse("2026-04-01T08:00:00Z"),
                )
            },
        )
    }

    private fun medicationWithReminders(
        id: String,
        name: String,
        startDate: LocalDate = LocalDate.of(2026, 4, 1),
        endDate: LocalDate? = null,
        frequency: String? = null,
        notes: String? = null,
        reminderMinutes: List<Int>,
        createdAt: Instant = Instant.parse("2026-04-01T08:00:00Z"),
    ): MedicationWithReminders {
        return MedicationWithReminders(
            medication = MedicationEntity(
                id = id,
                name = name,
                dose = "1 tablet",
                frequency = frequency,
                startDate = startDate,
                endDate = endDate,
                notes = notes,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
            reminders = reminderMinutes.mapIndexed { index, minutes ->
                MedicationReminderEntity(
                    id = "$id-reminder-$index",
                    medicationId = id,
                    timeMinutesOfDay = minutes,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                )
            },
        )
    }
}
