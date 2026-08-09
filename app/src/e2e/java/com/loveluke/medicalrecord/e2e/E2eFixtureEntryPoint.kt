package com.loveluke.medicalrecord.e2e

import com.loveluke.medicalrecord.app.access.MedicalRecordAccessController
import com.loveluke.medicalrecord.app.reminder.ReminderRuntimeCoordinator
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.attachment.EncryptedAttachmentService
import com.loveluke.medicalrecord.core.database.EncounterRepository
import com.loveluke.medicalrecord.core.database.HomeRepository
import com.loveluke.medicalrecord.core.database.MedicationRepository
import com.loveluke.medicalrecord.core.database.PatientRepository
import com.loveluke.medicalrecord.core.database.RoomReminderScheduleStore
import com.loveluke.medicalrecord.core.time.E2eMedicalRecordTimeSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface E2eFixtureEntryPoint {
    fun accessController(): MedicalRecordAccessController

    fun patientRepository(): PatientRepository

    fun encounterRepository(): EncounterRepository

    fun medicationRepository(): MedicationRepository

    fun homeRepository(): HomeRepository

    fun attachmentService(): EncryptedAttachmentService

    fun attachmentStoragePaths(): AttachmentStoragePaths

    fun reminderScheduleStore(): RoomReminderScheduleStore

    fun reminderRuntimeCoordinator(): ReminderRuntimeCoordinator

    fun timeSource(): E2eMedicalRecordTimeSource
}
