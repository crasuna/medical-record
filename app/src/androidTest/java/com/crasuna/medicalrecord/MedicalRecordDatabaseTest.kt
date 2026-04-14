package com.crasuna.medicalrecord

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MedicalRecordDatabaseTest {

    private lateinit var context: Context
    private lateinit var database: MedicalRecordDatabase
    private lateinit var encounterDao: EncounterDao
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var medicationDao: MedicationDao
    private lateinit var medicationReminderDao: MedicationReminderDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            MedicalRecordDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        encounterDao = database.encounterDao()
        attachmentDao = database.attachmentDao()
        medicationDao = database.medicationDao()
        medicationReminderDao = database.medicationReminderDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun deletingEncounterCascadesToAttachments() = runBlocking {
        val encounter = EncounterEntity(
            id = "encounter-1",
            visitDate = LocalDate.of(2026, 4, 14),
            visitTime = null,
            hospital = "General Hospital",
            department = null,
            doctor = null,
            chiefComplaint = null,
            diagnosis = null,
            disposition = null,
            notes = null,
        )
        val attachment = EncounterAttachmentEntity(
            id = "attachment-1",
            encounterId = encounter.id,
            type = AttachmentType.PDF,
            displayName = "report.pdf",
            mimeType = "application/pdf",
            encryptedPath = "/tmp/report.enc",
            thumbnailPath = null,
            pageCount = 1,
        )

        encounterDao.upsert(encounter)
        attachmentDao.insert(attachment)
        assertEquals(1, attachmentDao.getForEncounter(encounter.id).size)

        encounterDao.deleteById(encounter.id)

        assertNull(encounterDao.getById(encounter.id))
        assertEquals(0, attachmentDao.getForEncounter(encounter.id).size)
    }

    @Test
    fun deletingMedicationCascadesToReminders() = runBlocking {
        val medication = MedicationEntity(
            id = "medication-1",
            name = "Vitamin D",
            dose = "1 tablet",
            frequency = "Daily",
            startDate = LocalDate.of(2026, 4, 1),
            endDate = null,
            notes = null,
        )
        val reminder = MedicationReminderEntity(
            id = "reminder-1",
            medicationId = medication.id,
            timeMinutesOfDay = 8 * 60,
        )

        medicationDao.upsert(medication)
        medicationReminderDao.insertAll(listOf(reminder))
        assertEquals(1, medicationReminderDao.getForMedication(medication.id).size)

        medicationDao.deleteById(medication.id)

        assertNull(medicationDao.getById(medication.id))
        assertEquals(0, medicationReminderDao.getForMedication(medication.id).size)
    }

    @Test
    fun medicationRepositorySaveReplacesReminderTimesAtomically() = runBlocking {
        val repository = OfflineMedicationRepository(
            database = database,
            medicationDao = medicationDao,
            medicationReminderDao = medicationReminderDao,
            ioDispatcher = Dispatchers.IO,
        )
        val medication = MedicationEntity(
            id = "medication-2",
            name = "Metformin",
            dose = "500 mg",
            frequency = "Twice daily",
            startDate = LocalDate.of(2026, 4, 10),
            endDate = null,
            notes = "After meals",
        )

        repository.saveMedication(medication, listOf(8 * 60, 20 * 60, 8 * 60))
        var saved = repository.getMedication(medication.id)
        assertEquals(listOf(8 * 60, 20 * 60), saved?.reminders?.map { it.timeMinutesOfDay })

        repository.saveMedication(
            medication.copy(notes = "Updated"),
            listOf((9 * 60) + 30),
        )
        saved = repository.getMedication(medication.id)

        assertEquals("Updated", saved?.medication?.notes)
        assertEquals(listOf((9 * 60) + 30), saved?.reminders?.map { it.timeMinutesOfDay })
    }

    @Test
    fun migrationFrom1To2PreservesMedicationDataAndCreatesReminderTable() = runBlocking {
        val migrationDbName = "medical-record-migration-test.db"
        context.deleteDatabase(migrationDbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migrationDbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion1Schema(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        openHelper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO medications (`id`, `name`, `dose`, `frequency`, `startDate`, `endDate`, `notes`, `createdAt`, `updatedAt`)
                VALUES ('medication-migrated', 'Atorvastatin', '10 mg', 'Nightly', 20558, NULL, 'Keep taking', 1713052800000, 1713052800000)
                """.trimIndent(),
            )
            close()
        }
        openHelper.close()

        val migratedDatabase = Room.databaseBuilder(context, MedicalRecordDatabase::class.java, migrationDbName)
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val migratedMedication = migratedDatabase.medicationDao().getById("medication-migrated")
            val remindersAfterMigration = migratedDatabase.medicationReminderDao().getForMedication("medication-migrated")

            assertEquals("Atorvastatin", migratedMedication?.name)
            assertEquals(0, remindersAfterMigration.size)

            migratedDatabase.medicationReminderDao().insertAll(
                listOf(
                    MedicationReminderEntity(
                        id = "reminder-migrated",
                        medicationId = "medication-migrated",
                        timeMinutesOfDay = 7 * 60,
                        createdAt = Instant.ofEpochMilli(1_713_052_800_000),
                        updatedAt = Instant.ofEpochMilli(1_713_052_800_000),
                    ),
                ),
            )

            assertEquals(
                listOf(7 * 60),
                migratedDatabase.medicationReminderDao()
                    .getForMedication("medication-migrated")
                    .map { it.timeMinutesOfDay },
            )
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(migrationDbName)
        }
    }

    private fun createVersion1Schema(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `encounters` (
                `id` TEXT NOT NULL,
                `visitDate` INTEGER NOT NULL,
                `visitTime` INTEGER,
                `hospital` TEXT NOT NULL,
                `department` TEXT,
                `doctor` TEXT,
                `chiefComplaint` TEXT,
                `diagnosis` TEXT,
                `disposition` TEXT,
                `notes` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `encounter_attachments` (
                `id` TEXT NOT NULL,
                `encounterId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `encryptedPath` TEXT NOT NULL,
                `thumbnailPath` TEXT,
                `pageCount` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`encounterId`) REFERENCES `encounters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_encounter_attachments_encounterId`
            ON `encounter_attachments` (`encounterId`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `medications` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `dose` TEXT,
                `frequency` TEXT,
                `startDate` INTEGER NOT NULL,
                `endDate` INTEGER,
                `notes` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        database.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ada9dee38c2911836e06f5ebb790c564')",
        )
    }
}
