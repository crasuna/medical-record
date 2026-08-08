package com.loveluke.medicalrecord.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSchemaInstrumentedTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun compositePatientForeignKeyRejectsCrossPatientReminder() = runBlocking {
        val now = Instant.parse("2026-08-08T08:00:00Z")
        val patientA = "00000000-0000-4000-8000-000000000001"
        val patientB = "00000000-0000-4000-8000-000000000002"
        val medicationId = "00000000-0000-4000-8000-000000000003"
        database.patientProfileDao().upsert(
            PatientProfileEntity(patientA, true, true, now, now),
        )
        database.patientProfileDao().upsert(
            PatientProfileEntity(patientB, false, false, now, now),
        )
        database.medicationDao().insert(
            MedicationEntity(
                id = medicationId,
                patientId = patientA,
                name = "Medication",
                dose = null,
                frequency = null,
                startDate = LocalDate.of(2026, 8, 8),
                endDate = null,
                notes = null,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val failure = runCatching {
            database.medicationDao().insertReminder(
                ReminderEntity(
                    id = "00000000-0000-4000-8000-000000000004",
                    patientId = patientB,
                    medicationId = medicationId,
                    timeMinutesOfDay = 600,
                    enabledByUser = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }.exceptionOrNull()

        assertNotNull(failure)
    }
}
