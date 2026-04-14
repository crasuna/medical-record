package com.crasuna.medicalrecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TypeConvertersAndMedicationLogicTest {

    private val converters = MedicalRecordTypeConverters()

    @Test
    fun `local date time and instant converters round trip`() {
        val date = LocalDate.of(2026, 4, 14)
        val time = LocalTime.of(9, 30)
        val instant = Instant.ofEpochMilli(1_713_087_600_000)

        assertEquals(date, converters.epochToLocalDate(converters.localDateToEpoch(date)))
        assertEquals(time, converters.secondsToLocalTime(converters.localTimeToSeconds(time)))
        assertEquals(instant, converters.epochMilliToInstant(converters.instantToEpochMilli(instant)))
    }

    @Test
    fun `medication filter returns current all and ended records`() {
        val today = LocalDate.of(2026, 4, 14)
        val medications = listOf(
            MedicationWithReminders(
                medication = MedicationEntity(
                    name = "Current open",
                    startDate = today.minusDays(10),
                    endDate = null,
                    dose = null,
                    frequency = null,
                    notes = null,
                ),
                reminders = emptyList(),
            ),
            MedicationWithReminders(
                medication = MedicationEntity(
                    name = "Current dated",
                    startDate = today.minusDays(5),
                    endDate = today,
                    dose = null,
                    frequency = null,
                    notes = null,
                ),
                reminders = emptyList(),
            ),
            MedicationWithReminders(
                medication = MedicationEntity(
                    name = "Ended",
                    startDate = today.minusDays(20),
                    endDate = today.minusDays(1),
                    dose = null,
                    frequency = null,
                    notes = null,
                ),
                reminders = emptyList(),
            ),
        )

        assertEquals(3, medications.filterBy(MedicationFilter.ALL, today).size)
        assertEquals(
            listOf("Current open", "Current dated"),
            medications.filterBy(MedicationFilter.CURRENT, today).map { it.medication.name },
        )
        assertEquals(listOf("Ended"), medications.filterBy(MedicationFilter.ENDED, today).map { it.medication.name })
        assertTrue(medications.filterBy(MedicationFilter.CURRENT, today).none { it.medication.name == "Ended" })
    }

    @Test
    fun `compute next reminder trigger handles future same day and next day`() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val schedule = reminderSchedule(
            startDate = LocalDate.of(2026, 4, 1),
            endDate = null,
            minutesOfDay = 8 * 60,
        )

        val futureSameDay = computeNextReminderTrigger(
            schedule = schedule,
            now = ZonedDateTime.of(2026, 4, 14, 7, 30, 0, 0, zoneId),
            zoneId = zoneId,
        )
        val nextDay = computeNextReminderTrigger(
            schedule = schedule,
            now = ZonedDateTime.of(2026, 4, 14, 9, 0, 0, 0, zoneId),
            zoneId = zoneId,
        )

        assertEquals(ZonedDateTime.of(2026, 4, 14, 8, 0, 0, 0, zoneId), futureSameDay)
        assertEquals(ZonedDateTime.of(2026, 4, 15, 8, 0, 0, 0, zoneId), nextDay)
    }

    @Test
    fun `compute next reminder trigger respects start and end dates`() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val upcomingSchedule = reminderSchedule(
            startDate = LocalDate.of(2026, 4, 16),
            endDate = LocalDate.of(2026, 4, 20),
            minutesOfDay = (20 * 60) + 15,
        )
        val endedSchedule = reminderSchedule(
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 14),
            minutesOfDay = 8 * 60,
        )

        val startsInFuture = computeNextReminderTrigger(
            schedule = upcomingSchedule,
            now = ZonedDateTime.of(2026, 4, 14, 7, 30, 0, 0, zoneId),
            zoneId = zoneId,
        )
        val endsSameDayAfterReminder = computeNextReminderTrigger(
            schedule = endedSchedule,
            now = ZonedDateTime.of(2026, 4, 14, 9, 0, 0, 0, zoneId),
            zoneId = zoneId,
        )
        val alreadyEnded = computeNextReminderTrigger(
            schedule = endedSchedule,
            now = ZonedDateTime.of(2026, 4, 15, 7, 0, 0, 0, zoneId),
            zoneId = zoneId,
        )

        assertEquals(ZonedDateTime.of(2026, 4, 16, 20, 15, 0, 0, zoneId), startsInFuture)
        assertEquals(null, endsSameDayAfterReminder)
        assertEquals(null, alreadyEnded)
    }

    private fun reminderSchedule(
        startDate: LocalDate,
        endDate: LocalDate?,
        minutesOfDay: Int,
    ): MedicationReminderSchedule {
        return MedicationReminderSchedule(
            reminderId = "reminder-1",
            medicationId = "medication-1",
            medicationName = "Medication",
            dose = null,
            frequency = null,
            startDate = startDate,
            endDate = endDate,
            timeMinutesOfDay = minutesOfDay,
        )
    }
}
