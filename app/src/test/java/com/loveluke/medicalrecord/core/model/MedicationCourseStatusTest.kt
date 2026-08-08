package com.loveluke.medicalrecord.core.model

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MedicationCourseStatusTest {
    private val today = LocalDate.of(2026, 8, 8)

    @Test
    fun currentRequiresStartedAndNotEndedBeforeToday() {
        assertEquals(
            MedicationCourseStatus.CURRENT,
            medication(startDate = today, endDate = today).courseStatus(today),
        )
        assertEquals(
            MedicationCourseStatus.CURRENT,
            medication(startDate = today.minusDays(10), endDate = null).courseStatus(today),
        )
    }

    @Test
    fun futureMedicationIsUpcomingRatherThanCurrent() {
        assertEquals(
            MedicationCourseStatus.UPCOMING,
            medication(startDate = today.plusDays(1), endDate = null).courseStatus(today),
        )
    }

    @Test
    fun medicationEndingBeforeTodayIsEnded() {
        assertEquals(
            MedicationCourseStatus.ENDED,
            medication(startDate = today.minusDays(10), endDate = today.minusDays(1))
                .courseStatus(today),
        )
    }

    private fun medication(startDate: LocalDate, endDate: LocalDate?) = Medication(
        id = "medication-id",
        patientId = "patient-id",
        name = "Medicine",
        dose = null,
        frequency = null,
        startDate = startDate,
        endDate = endDate,
        notes = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
