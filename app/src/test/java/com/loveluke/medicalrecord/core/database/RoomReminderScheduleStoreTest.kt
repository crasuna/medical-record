package com.loveluke.medicalrecord.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.loveluke.medicalrecord.core.model.Medication
import com.loveluke.medicalrecord.core.model.ReminderDraft
import com.loveluke.medicalrecord.core.reminder.AlarmPrecision
import com.loveluke.medicalrecord.core.reminder.ReminderSchedulingState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomReminderScheduleStoreTest {
    private val now = Instant.parse("2026-08-08T08:00:00Z")
    private val today = LocalDate.of(2026, 8, 8)
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val zoneId = ZoneId.of("UTC")

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomMedicalRecordRepository
    private lateinit var store: RoomReminderScheduleStore
    private lateinit var defaultPatientId: String
    private var idCounter = 0

    @Before
    fun setUp() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext<Context>())
            .allowMainThreadQueries()
            .build()
        repository = RoomMedicalRecordRepository(database, clock) { scheduleGeneratedUuid(++idCounter) }
        store = RoomReminderScheduleStore(database, clock) { zoneId }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun nextOccurrenceIsEarliestAcrossPatientsAndOnlyUsesEnabledActivePlans() = runTest {
        defaultPatientId = repository.ensureDefaultPatient().id
        insertPatient("patient-b")

        repository.saveMedicationWithReminders(
            medication("patient-a-med", defaultPatientId),
            listOf(ReminderDraft(600)),
        )
        repository.saveMedicationWithReminders(
            medication("patient-b-med", "patient-b"),
            listOf(ReminderDraft(540)),
        )
        repository.saveMedicationWithReminders(
            medication("disabled", defaultPatientId),
            listOf(ReminderDraft(510, enabledByUser = false)),
        )
        repository.saveMedicationWithReminders(
            medication(
                id = "ended",
                patientId = defaultPatientId,
                startDate = today.minusDays(4),
                endDate = today.minusDays(1),
            ),
            listOf(ReminderDraft(500)),
        )

        val occurrence = store.findNextOccurrence(now, zoneId)

        assertNotNull(occurrence)
        assertEquals(scheduleUuid("patient-b"), occurrence?.patientId)
        assertEquals(scheduleUuid("patient-b-med"), occurrence?.medicationId)
        assertEquals(Instant.parse("2026-08-08T09:00:00Z"), occurrence?.triggerAt)
    }

    @Test
    fun staleAlarmContentIsRejectedAfterPlanTimeOrCourseChanges() = runTest {
        defaultPatientId = repository.ensureDefaultPatient().id
        val medication = medication("medication", defaultPatientId)
        val first = repository.saveMedicationWithReminders(
            medication,
            listOf(ReminderDraft(600)),
        )
        val reminderId = first.reminders.single().id
        val tenOClock = Instant.parse("2026-08-08T10:00:00Z")
        recordScheduledAlarm(reminderId, tenOClock)

        assertEquals(
            listOf(reminderId),
            store.findDueDelivery(reminderId, tenOClock, tenOClock)
                ?.notifications
                ?.map { it.reminderId },
        )

        repository.saveMedicationWithReminders(medication, listOf(ReminderDraft(660)))
        assertEquals(
            emptyList<String>(),
            store.findDueDelivery(reminderId, tenOClock, tenOClock)
                ?.notifications
                ?.map { it.reminderId },
        )

        val replacementId = database.medicationDao()
            .getReminders(defaultPatientId, medication.id)
            .single()
            .id
        val elevenOClock = Instant.parse("2026-08-08T11:00:00Z")
        recordScheduledAlarm(replacementId, elevenOClock)
        assertEquals(
            listOf(replacementId),
            store.findDueDelivery(replacementId, elevenOClock, elevenOClock)
                ?.notifications
                ?.map { it.reminderId },
        )

        repository.saveMedicationWithReminders(
            medication.copy(
                startDate = today.minusDays(2),
                endDate = today.minusDays(1),
            ),
            listOf(ReminderDraft(660)),
        )
        assertEquals(
            emptyList<String>(),
            store.findDueDelivery(replacementId, elevenOClock, elevenOClock)
                ?.notifications
                ?.map { it.reminderId },
        )
    }

    @Test
    fun verifiedDelayedAlarmCollectsTwoEightOClockMedicationsAndEightOhFiveThenRejectsReplay() =
        runTest {
            defaultPatientId = repository.ensureDefaultPatient().id
            val firstEight = repository.saveMedicationWithReminders(
                medication("first-eight", defaultPatientId),
                listOf(ReminderDraft(8 * 60)),
            ).reminders.single()
            val secondEight = repository.saveMedicationWithReminders(
                medication("second-eight", defaultPatientId),
                listOf(ReminderDraft(8 * 60)),
            ).reminders.single()
            val eightOhFive = repository.saveMedicationWithReminders(
                medication("eight-oh-five", defaultPatientId),
                listOf(ReminderDraft(8 * 60 + 5)),
            ).reminders.single()
            val alarmAt = Instant.parse("2026-08-08T08:00:00Z")
            val deliveredAt = Instant.parse("2026-08-08T08:10:00Z")
            recordScheduledAlarm(firstEight.id, alarmAt)

            val batch = store.findDueDelivery(firstEight.id, alarmAt, deliveredAt)

            assertNotNull(batch)
            assertEquals(
                setOf(firstEight.id, secondEight.id, eightOhFive.id),
                batch?.notifications?.map { it.reminderId }?.toSet(),
            )
            assertEquals(
                listOf(alarmAt, alarmAt, alarmAt.plusSeconds(5 * 60)),
                batch?.notifications?.map { it.scheduledAt },
            )

            store.recordSchedulingState(ReminderSchedulingState.NoFutureReminder)

            assertNull(store.findDueDelivery(firstEight.id, alarmAt, deliveredAt))
        }

    @Test
    fun delayedWindowPublishesEachDailyReminderAtMostOnceAcrossMultipleDays() = runTest {
        defaultPatientId = repository.ensureDefaultPatient().id
        val reminder = repository.saveMedicationWithReminders(
            medication("daily", defaultPatientId),
            listOf(ReminderDraft(8 * 60)),
        ).reminders.single()
        val alarmAt = Instant.parse("2026-08-08T08:00:00Z")
        recordScheduledAlarm(reminder.id, alarmAt)

        val batch = store.findDueDelivery(
            anchorReminderId = reminder.id,
            scheduledAt = alarmAt,
            deliveredAt = Instant.parse("2026-08-10T08:10:00Z"),
        )

        assertEquals(1, batch?.notifications?.size)
        assertEquals(reminder.id, batch?.notifications?.single()?.reminderId)
        assertEquals(alarmAt, batch?.notifications?.single()?.scheduledAt)
    }

    @Test
    fun daylightSavingGapOccurrenceRemainsValidAtItsResolvedInstant() = runTest {
        defaultPatientId = repository.ensureDefaultPatient().id
        val newYork = ZoneId.of("America/New_York")
        val dstStore = RoomReminderScheduleStore(database, clock) { newYork }
        val dstDate = LocalDate.of(2026, 3, 8)
        val saved = repository.saveMedicationWithReminders(
            medication(
                id = "dst-gap-medication",
                patientId = defaultPatientId,
                startDate = dstDate,
                endDate = dstDate,
            ),
            listOf(ReminderDraft(2 * 60 + 30)),
        )
        val reminderId = saved.reminders.single().id
        val searchFloor = Instant.parse("2026-03-08T05:00:00Z")

        val occurrence = dstStore.findNextOccurrence(searchFloor, newYork)
        val dstOccurrence = Instant.parse("2026-03-08T07:30:00Z")

        // 02:30 does not exist on this date. java.time resolves the user's wall-clock intention
        // to 03:30 EDT, which is 07:30Z; delivery validation must use that same calculation.
        assertEquals(dstOccurrence, occurrence?.triggerAt)
        recordScheduledAlarm(reminderId, dstOccurrence)
        assertEquals(
            listOf(reminderId),
            dstStore.findDueDelivery(reminderId, dstOccurrence, dstOccurrence)
                ?.notifications
                ?.map { it.reminderId },
        )
        assertNull(
            dstStore.findDueDelivery(
                reminderId,
                dstOccurrence.plusSeconds(60),
                dstOccurrence.plusSeconds(60),
            ),
        )
    }

    @Test
    fun schedulingStateIsPersistedInRoom() = runTest {
        val scheduled = ReminderSchedulingState.Scheduled(
            reminderId = scheduleUuid("reminder-id"),
            triggerAt = Instant.parse("2026-08-09T09:00:00Z"),
            precision = AlarmPrecision.INEXACT,
        )

        store.recordSchedulingState(scheduled)
        assertEquals(scheduled, store.readPersistedState())

        store.recordSchedulingState(ReminderSchedulingState.NotificationsUnavailable)
        assertEquals(
            ReminderSchedulingState.NotificationsUnavailable,
            store.readPersistedState(),
        )
    }

    private suspend fun insertPatient(id: String) {
        database.patientProfileDao().upsert(
            PatientProfileEntity(
                id = scheduleUuid(id),
                isDefault = false,
                isHidden = false,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun recordScheduledAlarm(reminderId: String, triggerAt: Instant) {
        store.recordSchedulingState(
            ReminderSchedulingState.Scheduled(
                reminderId = reminderId,
                triggerAt = triggerAt,
                precision = AlarmPrecision.INEXACT,
            ),
        )
    }

    private fun medication(
        id: String,
        patientId: String,
        startDate: LocalDate = today,
        endDate: LocalDate? = null,
    ) = Medication(
        id = scheduleUuid(id),
        patientId = scheduleUuid(patientId),
        name = "Medication $id",
        dose = "10 mg",
        frequency = "Daily",
        startDate = startDate,
        endDate = endDate,
        notes = null,
        createdAt = now,
        updatedAt = now,
    )
}

private fun scheduleUuid(value: String): String {
    val normalized = value.lowercase()
    return runCatching { UUID.fromString(normalized) }
        .getOrNull()
        ?.takeIf { it.toString() == normalized }
        ?.toString()
        ?: UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}

private fun scheduleGeneratedUuid(index: Int): String =
    "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
