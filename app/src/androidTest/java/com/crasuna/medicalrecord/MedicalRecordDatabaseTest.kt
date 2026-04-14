package com.crasuna.medicalrecord

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MedicalRecordDatabaseTest {

    private lateinit var database: MedicalRecordDatabase
    private lateinit var encounterDao: EncounterDao
    private lateinit var attachmentDao: AttachmentDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MedicalRecordDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        encounterDao = database.encounterDao()
        attachmentDao = database.attachmentDao()
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
}
