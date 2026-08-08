package com.loveluke.medicalrecord.app.di

import android.content.Context
import androidx.room.RoomDatabase
import com.loveluke.medicalrecord.app.storage.DatabaseInstanceRegistry
import com.loveluke.medicalrecord.core.database.AppDatabase
import com.loveluke.medicalrecord.core.security.SecureMaterialFailure
import com.loveluke.medicalrecord.core.security.SecureMaterialManager
import com.loveluke.medicalrecord.core.security.SqlCipherFactoryProvider
import com.loveluke.medicalrecord.core.security.SqlCipherFactoryResolution
import com.loveluke.medicalrecord.core.security.SqlCipherRuntimeVerification
import com.loveluke.medicalrecord.core.security.SqlCipherRuntimeVerificationFailure
import com.loveluke.medicalrecord.core.security.SqlCipherRuntimeVerifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

sealed interface DatabaseOpenFailure {
    data class SecureMaterial(val reason: SecureMaterialFailure) : DatabaseOpenFailure
    data class RuntimeVerification(
        val reason: SqlCipherRuntimeVerificationFailure,
    ) : DatabaseOpenFailure

    data object OpenFailed : DatabaseOpenFailure
}

class DatabaseFailClosedException(
    val failure: DatabaseOpenFailure,
) : IllegalStateException("Encrypted database access is unavailable.")

@Module
@InstallIn(SingletonComponent::class)
object MedicalRecordDatabaseModule {
    @Provides
    @Singleton
    fun provideSecureMaterialManager(
        @ApplicationContext context: Context,
    ): SecureMaterialManager = SecureMaterialManager.forAndroid(context)

    @Provides
    @Singleton
    fun provideSqlCipherFactoryProvider(
        secureMaterialManager: SecureMaterialManager,
    ): SqlCipherFactoryProvider = SqlCipherFactoryProvider(secureMaterialManager)

    @Provides
    @Singleton
    fun provideSqlCipherRuntimeVerifier(): SqlCipherRuntimeVerifier = SqlCipherRuntimeVerifier()

    @Provides
    @Singleton
    fun provideEncryptedDatabase(
        @ApplicationContext context: Context,
        factoryProvider: SqlCipherFactoryProvider,
        runtimeVerifier: SqlCipherRuntimeVerifier,
        instanceRegistry: DatabaseInstanceRegistry,
    ): AppDatabase {
        val databaseFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        val factory = when (val resolution = factoryProvider.create(databaseFile)) {
            is SqlCipherFactoryResolution.Ready -> resolution.factory
            is SqlCipherFactoryResolution.FailClosed -> throw DatabaseFailClosedException(
                DatabaseOpenFailure.SecureMaterial(resolution.reason),
            )
        }

        val database = try {
            AppDatabase.builder(context)
                .openHelperFactory(factory)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        } catch (_: LinkageError) {
            throw DatabaseFailClosedException(DatabaseOpenFailure.OpenFailed)
        } catch (_: RuntimeException) {
            throw DatabaseFailClosedException(DatabaseOpenFailure.OpenFailed)
        }
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            return when (val verification = runtimeVerifier.verify(sqliteDatabase)) {
                is SqlCipherRuntimeVerification.Verified -> database.also(
                    instanceRegistry::register,
                )
                is SqlCipherRuntimeVerification.Failed -> {
                    runCatching(database::close)
                    throw DatabaseFailClosedException(
                        DatabaseOpenFailure.RuntimeVerification(verification.reason),
                    )
                }
            }
        } catch (failure: DatabaseFailClosedException) {
            throw failure
        } catch (_: LinkageError) {
            runCatching(database::close)
            throw DatabaseFailClosedException(DatabaseOpenFailure.OpenFailed)
        } catch (_: RuntimeException) {
            runCatching(database::close)
            throw DatabaseFailClosedException(DatabaseOpenFailure.OpenFailed)
        }
    }
}
