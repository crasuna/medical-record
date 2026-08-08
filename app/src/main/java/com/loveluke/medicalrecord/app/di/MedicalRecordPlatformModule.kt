package com.loveluke.medicalrecord.app.di

import android.content.Context
import com.loveluke.medicalrecord.app.storage.DatabaseInstanceRegistry
import com.loveluke.medicalrecord.core.attachment.AttachmentOrphanCleaner
import com.loveluke.medicalrecord.core.attachment.AttachmentStoragePaths
import com.loveluke.medicalrecord.core.attachment.TemporaryPlaintextRegistry
import com.loveluke.medicalrecord.core.database.AppDatabase
import com.loveluke.medicalrecord.core.reminder.ReminderPlatformArtifactClearer
import com.loveluke.medicalrecord.core.security.SensitiveDataClearCoordinator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationCoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object MedicalRecordPlatformModule {
    @Provides
    @Singleton
    fun provideAttachmentStoragePaths(
        @ApplicationContext context: Context,
    ): AttachmentStoragePaths = AttachmentStoragePaths(context.filesDir)

    @Provides
    @Singleton
    fun provideTemporaryPlaintextRegistry(
        @ApplicationContext context: Context,
    ): TemporaryPlaintextRegistry = TemporaryPlaintextRegistry(
        cacheDirectory = context.cacheDir,
    )

    @Provides
    @Singleton
    fun provideAttachmentOrphanCleaner(
        attachmentStoragePaths: AttachmentStoragePaths,
    ): AttachmentOrphanCleaner = AttachmentOrphanCleaner(attachmentStoragePaths)

    @Provides
    @Singleton
    fun provideSensitiveDataClearCoordinator(
        @ApplicationContext context: Context,
        attachmentStoragePaths: AttachmentStoragePaths,
        plaintextRegistry: TemporaryPlaintextRegistry,
        databaseInstanceRegistry: DatabaseInstanceRegistry,
        reminderPlatformArtifactClearer: ReminderPlatformArtifactClearer,
    ): SensitiveDataClearCoordinator = SensitiveDataClearCoordinator.forAndroid(
        context = context,
        databaseFile = context.getDatabasePath(AppDatabase.DATABASE_NAME),
        attachmentStoragePaths = attachmentStoragePaths,
        plaintextRegistry = plaintextRegistry,
        closeDatabase = databaseInstanceRegistry::closeIfOpen,
        clearReminderArtifacts = reminderPlatformArtifactClearer::clear,
    )

    @Provides
    @Singleton
    @ApplicationCoroutineScope
    fun provideApplicationCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
