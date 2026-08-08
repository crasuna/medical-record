package com.loveluke.medicalrecord.core.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `next occurrence is strictly after the supplied instant`() {
        val plan = plan(start = LocalDate.of(2026, 8, 1), minute = 8 * 60)

        assertEquals(
            Instant.parse("2026-08-09T00:00:00Z"),
            plan.nextOccurrence(Instant.parse("2026-08-08T00:00:00Z"), zone)?.triggerAt,
        )
    }

    @Test
    fun `future course starts on its actual start date`() {
        val plan = plan(start = LocalDate.of(2026, 8, 10), minute = 9 * 60 + 30)

        assertEquals(
            Instant.parse("2026-08-10T01:30:00Z"),
            plan.nextOccurrence(Instant.parse("2026-08-08T00:00:00Z"), zone)?.triggerAt,
        )
    }

    @Test
    fun `end date is inclusive and then expires`() {
        val plan = plan(
            start = LocalDate.of(2026, 8, 1),
            end = LocalDate.of(2026, 8, 8),
            minute = 20 * 60,
        )

        assertEquals(
            Instant.parse("2026-08-08T12:00:00Z"),
            plan.nextOccurrence(Instant.parse("2026-08-08T11:59:59Z"), zone)?.triggerAt,
        )
        assertNull(plan.nextOccurrence(Instant.parse("2026-08-08T12:00:00Z"), zone))
    }

    @Test
    fun `disabled user intention is never scheduled`() {
        assertNull(
            plan(
                start = LocalDate.of(2026, 8, 1),
                minute = 8 * 60,
                enabled = false,
            ).nextOccurrence(Instant.parse("2026-08-08T00:00:00Z"), zone),
        )
    }

    @Test
    fun `daylight saving gap resolves to the first valid local instant`() {
        val newYork = ZoneId.of("America/New_York")
        val plan = plan(start = LocalDate.of(2026, 3, 8), minute = 2 * 60 + 30)

        assertEquals(
            Instant.parse("2026-03-08T07:30:00Z"),
            plan.nextOccurrence(Instant.parse("2026-03-08T00:00:00Z"), newYork)?.triggerAt,
        )
    }

    private fun plan(
        start: LocalDate,
        minute: Int,
        end: LocalDate? = null,
        enabled: Boolean = true,
    ) = ReminderPlan(
        reminderId = "reminder-id",
        patientId = "patient-id",
        medicationId = "medication-id",
        medicationName = "Medication",
        dose = "1 tablet",
        startDate = start,
        endDate = end,
        timeMinutesOfDay = minute,
        enabledByUser = enabled,
    )
}
