package com.loveluke.medicalrecord.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PatientProfileEntity::class,
        EncounterEntity::class,
        AttachmentEntity::class,
        MedicationEntity::class,
        ReminderEntity::class,
        ReminderScheduleStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientProfileDao(): PatientProfileDao

    abstract fun encounterDao(): EncounterDao

    abstract fun medicationDao(): MedicationDao

    abstract fun homeDao(): HomeDao

    abstract fun reminderScheduleDao(): ReminderScheduleDao

    companion object {
        const val DATABASE_NAME = "medical-record.db"

        /**
         * The canonical builder. SQLCipher callers should add their open-helper factory to the
         * returned builder instead of constructing a second builder that omits schema callbacks.
         */
        fun builder(
            context: Context,
            name: String = DATABASE_NAME,
        ): Builder<AppDatabase> = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            name,
        ).addCallback(SCHEMA_CONSTRAINTS)

        fun inMemoryBuilder(context: Context): Builder<AppDatabase> =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
            ).addCallback(SCHEMA_CONSTRAINTS)

        private val SCHEMA_CONSTRAINTS = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                installSchemaConstraints(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                // Triggers are not represented in Room's schema JSON. Reassert them on every open
                // so all databases built through the canonical builder enforce the same rules.
                installSchemaConstraints(db)
            }
        }

        private fun installSchemaConstraints(db: SupportSQLiteDatabase) {
            CONSTRAINT_TRIGGERS.forEach(db::execSQL)
        }

        private val CONSTRAINT_TRIGGERS = listOf(
            """
            CREATE TRIGGER IF NOT EXISTS patient_profiles_single_default_insert
            BEFORE INSERT ON patient_profiles
            WHEN NEW.is_default = 1
              AND EXISTS (SELECT 1 FROM patient_profiles WHERE is_default = 1)
            BEGIN
                SELECT RAISE(ABORT, 'only one default patient is allowed');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS patient_profiles_single_default_update
            BEFORE UPDATE OF is_default ON patient_profiles
            WHEN NEW.is_default = 1
              AND EXISTS (
                  SELECT 1 FROM patient_profiles
                  WHERE is_default = 1 AND id <> OLD.id
              )
            BEGIN
                SELECT RAISE(ABORT, 'only one default patient is allowed');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS reminders_validate_insert
            BEFORE INSERT ON reminders
            WHEN NEW.time_minutes < 0 OR NEW.time_minutes > 1439
            BEGIN
                SELECT RAISE(ABORT, 'reminder time_minutes must be between 0 and 1439');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS reminders_validate_update
            BEFORE UPDATE OF time_minutes ON reminders
            WHEN NEW.time_minutes < 0 OR NEW.time_minutes > 1439
            BEGIN
                SELECT RAISE(ABORT, 'reminder time_minutes must be between 0 and 1439');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS medications_validate_insert
            BEFORE INSERT ON medications
            WHEN TRIM(NEW.name) = ''
              OR (NEW.end_date IS NOT NULL AND NEW.end_date < NEW.start_date)
            BEGIN
                SELECT RAISE(ABORT, 'invalid medication name or date range');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS medications_validate_update
            BEFORE UPDATE OF name, start_date, end_date ON medications
            WHEN TRIM(NEW.name) = ''
              OR (NEW.end_date IS NOT NULL AND NEW.end_date < NEW.start_date)
            BEGIN
                SELECT RAISE(ABORT, 'invalid medication name or date range');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS encounters_validate_insert
            BEFORE INSERT ON encounters
            WHEN TRIM(NEW.hospital) = ''
            BEGIN
                SELECT RAISE(ABORT, 'encounter hospital must not be blank');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS encounters_validate_update
            BEFORE UPDATE OF hospital ON encounters
            WHEN TRIM(NEW.hospital) = ''
            BEGIN
                SELECT RAISE(ABORT, 'encounter hospital must not be blank');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS attachments_validate_insert
            BEFORE INSERT ON attachments
            WHEN NEW.size_bytes < 0
              OR NEW.crypto_version <= 0
              OR (NEW.page_count IS NOT NULL AND NEW.page_count <= 0)
              OR NEW.kind NOT IN ('IMAGE', 'PDF')
              OR (NEW.kind = 'IMAGE' AND NEW.page_count IS NOT NULL)
              OR (NEW.kind = 'PDF' AND (NEW.page_count IS NULL OR NEW.page_count <= 0))
              OR NEW.integrity_state NOT IN ('AVAILABLE', 'QUARANTINED')
              OR (NEW.integrity_state = 'AVAILABLE' AND NEW.quarantined_at IS NOT NULL)
              OR (NEW.integrity_state = 'QUARANTINED' AND NEW.quarantined_at IS NULL)
              OR TRIM(NEW.display_name) = ''
              OR TRIM(NEW.mime_type) = ''
              OR TRIM(NEW.encrypted_relative_path) = ''
              OR (NEW.encrypted_thumbnail_relative_path IS NOT NULL
                  AND TRIM(NEW.encrypted_thumbnail_relative_path) = '')
            BEGIN
                SELECT RAISE(ABORT, 'invalid attachment metadata');
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS attachments_validate_update
            BEFORE UPDATE OF size_bytes, crypto_version, page_count, kind, integrity_state,
                             quarantined_at, display_name, mime_type, encrypted_relative_path,
                             encrypted_thumbnail_relative_path ON attachments
            WHEN NEW.size_bytes < 0
              OR NEW.crypto_version <= 0
              OR (NEW.page_count IS NOT NULL AND NEW.page_count <= 0)
              OR NEW.kind NOT IN ('IMAGE', 'PDF')
              OR (NEW.kind = 'IMAGE' AND NEW.page_count IS NOT NULL)
              OR (NEW.kind = 'PDF' AND (NEW.page_count IS NULL OR NEW.page_count <= 0))
              OR NEW.integrity_state NOT IN ('AVAILABLE', 'QUARANTINED')
              OR (NEW.integrity_state = 'AVAILABLE' AND NEW.quarantined_at IS NOT NULL)
              OR (NEW.integrity_state = 'QUARANTINED' AND NEW.quarantined_at IS NULL)
              OR TRIM(NEW.display_name) = ''
              OR TRIM(NEW.mime_type) = ''
              OR TRIM(NEW.encrypted_relative_path) = ''
              OR (NEW.encrypted_thumbnail_relative_path IS NOT NULL
                  AND TRIM(NEW.encrypted_thumbnail_relative_path) = '')
            BEGIN
                SELECT RAISE(ABORT, 'invalid attachment metadata');
            END
            """.trimIndent(),
        )
    }
}
