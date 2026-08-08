package com.loveluke.medicalrecord.core.database

import com.loveluke.medicalrecord.core.reminder.ReminderScheduleStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {
    @Binds
    @Singleton
    abstract fun bindPatientRepository(
        implementation: RoomMedicalRecordRepository,
    ): PatientRepository

    @Binds
    @Singleton
    abstract fun bindEncounterRepository(
        implementation: RoomMedicalRecordRepository,
    ): EncounterRepository

    @Binds
    @Singleton
    abstract fun bindMedicationRepository(
        implementation: RoomMedicalRecordRepository,
    ): MedicationRepository

    @Binds
    @Singleton
    abstract fun bindHomeRepository(
        implementation: RoomMedicalRecordRepository,
    ): HomeRepository

    @Binds
    @Singleton
    abstract fun bindReminderScheduleStore(
        implementation: RoomReminderScheduleStore,
    ): ReminderScheduleStore
}
