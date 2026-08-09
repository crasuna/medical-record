package com.loveluke.medicalrecord.core.time

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object MedicalRecordTimeModule {
    @Provides
    @Singleton
    fun provideMedicalRecordTimeSource(): MedicalRecordTimeSource =
        SystemMedicalRecordTimeSource
}
