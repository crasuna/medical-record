package com.crasuna.medicalrecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

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
            MedicationEntity(name = "Current open", startDate = today.minusDays(10), endDate = null, dose = null, frequency = null, notes = null),
            MedicationEntity(name = "Current dated", startDate = today.minusDays(5), endDate = today, dose = null, frequency = null, notes = null),
            MedicationEntity(name = "Ended", startDate = today.minusDays(20), endDate = today.minusDays(1), dose = null, frequency = null, notes = null),
        )

        assertEquals(3, medications.filterBy(MedicationFilter.ALL, today).size)
        assertEquals(listOf("Current open", "Current dated"), medications.filterBy(MedicationFilter.CURRENT, today).map { it.name })
        assertEquals(listOf("Ended"), medications.filterBy(MedicationFilter.ENDED, today).map { it.name })
        assertTrue(medications.filterBy(MedicationFilter.CURRENT, today).none { it.name == "Ended" })
    }
}
