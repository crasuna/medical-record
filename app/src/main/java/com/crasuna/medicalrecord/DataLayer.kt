package com.crasuna.medicalrecord

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class AttachmentType {
    IMAGE,
    PDF,
}

enum class MedicationFilter {
    CURRENT,
    ALL,
    ENDED,
}

data class EncounterSummary(
    val id: String,
    val visitDate: LocalDate,
    val visitTime: LocalTime?,
    val hospital: String,
    val department: String?,
    val doctor: String?,
    val diagnosis: String?,
    val attachmentCount: Int,
)

@Entity(tableName = "encounters")
data class EncounterEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val visitDate: LocalDate,
    val visitTime: LocalTime?,
    val hospital: String,
    val department: String?,
    val doctor: String?,
    val chiefComplaint: String?,
    val diagnosis: String?,
    val disposition: String?,
    val notes: String?,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "encounter_attachments",
    foreignKeys = [
        ForeignKey(
            entity = EncounterEntity::class,
            parentColumns = ["id"],
            childColumns = ["encounterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("encounterId")],
)
data class EncounterAttachmentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val encounterId: String,
    val type: AttachmentType,
    val displayName: String,
    val mimeType: String,
    val encryptedPath: String,
    val thumbnailPath: String?,
    val pageCount: Int?,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dose: String?,
    val frequency: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val notes: String?,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Entity(
    tableName = "medication_reminders",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId")],
)
data class MedicationReminderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val medicationId: String,
    val timeMinutesOfDay: Int,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class EncounterWithAttachments(
    @Embedded val encounter: EncounterEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "encounterId",
    )
    val attachments: List<EncounterAttachmentEntity>,
)

data class MedicationWithReminders(
    @Embedded val medication: MedicationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "medicationId",
    )
    val reminders: List<MedicationReminderEntity>,
)

data class MedicationReminderSchedule(
    val reminderId: String,
    val medicationId: String,
    val medicationName: String,
    val dose: String?,
    val frequency: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val timeMinutesOfDay: Int,
)

class MedicalRecordTypeConverters {
    @TypeConverter
    fun localDateToEpoch(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun epochToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun localTimeToSeconds(value: LocalTime?): Int? = value?.toSecondOfDay()

    @TypeConverter
    fun secondsToLocalTime(value: Int?): LocalTime? = value?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    @TypeConverter
    fun instantToEpochMilli(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMilliToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}

@Dao
interface EncounterDao {
    @Query(
        """
        SELECT encounters.id,
               encounters.visitDate,
               encounters.visitTime,
               encounters.hospital,
               encounters.department,
               encounters.doctor,
               encounters.diagnosis,
               CAST(COUNT(encounter_attachments.id) AS INTEGER) AS attachmentCount
        FROM encounters
        LEFT JOIN encounter_attachments
          ON encounter_attachments.encounterId = encounters.id
        GROUP BY encounters.id
        ORDER BY encounters.visitDate DESC, encounters.visitTime DESC
        """,
    )
    fun observeSummaries(): Flow<List<EncounterSummary>>

    @Transaction
    @Query("SELECT * FROM encounters ORDER BY visitDate DESC, visitTime DESC")
    fun observeEncounterDetails(): Flow<List<EncounterWithAttachments>>

    @Transaction
    @Query("SELECT * FROM encounters WHERE id = :id LIMIT 1")
    fun observeEncounter(id: String): Flow<EncounterWithAttachments?>

    @Query("SELECT * FROM encounters WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EncounterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(encounter: EncounterEntity)

    @Query("DELETE FROM encounters WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM encounter_attachments WHERE encounterId = :encounterId ORDER BY createdAt DESC")
    fun observeForEncounter(encounterId: String): Flow<List<EncounterAttachmentEntity>>

    @Query("SELECT * FROM encounter_attachments WHERE encounterId = :encounterId")
    suspend fun getForEncounter(encounterId: String): List<EncounterAttachmentEntity>

    @Query("SELECT * FROM encounter_attachments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EncounterAttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: EncounterAttachmentEntity)

    @Delete
    suspend fun delete(attachment: EncounterAttachmentEntity)
}

@Dao
interface MedicationDao {
    @Transaction
    @Query("SELECT * FROM medications ORDER BY startDate DESC, createdAt DESC")
    fun observeAllWithReminders(): Flow<List<MedicationWithReminders>>

    @Query("SELECT * FROM medications WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MedicationEntity?

    @Transaction
    @Query("SELECT * FROM medications WHERE id = :id LIMIT 1")
    suspend fun getWithRemindersById(id: String): MedicationWithReminders?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(medication: MedicationEntity)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MedicationReminderDao {
    @Query("SELECT * FROM medication_reminders WHERE medicationId = :medicationId ORDER BY timeMinutesOfDay ASC")
    suspend fun getForMedication(medicationId: String): List<MedicationReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<MedicationReminderEntity>)

    @Query("DELETE FROM medication_reminders WHERE medicationId = :medicationId")
    suspend fun deleteByMedicationId(medicationId: String)

    @Query(
        """
        SELECT medication_reminders.id AS reminderId,
               medications.id AS medicationId,
               medications.name AS medicationName,
               medications.dose AS dose,
               medications.frequency AS frequency,
               medications.startDate AS startDate,
               medications.endDate AS endDate,
               medication_reminders.timeMinutesOfDay AS timeMinutesOfDay
        FROM medication_reminders
        INNER JOIN medications
            ON medications.id = medication_reminders.medicationId
        ORDER BY medications.name ASC, medication_reminders.timeMinutesOfDay ASC
        """,
    )
    suspend fun getAllSchedules(): List<MedicationReminderSchedule>

    @Query(
        """
        SELECT medication_reminders.id AS reminderId,
               medications.id AS medicationId,
               medications.name AS medicationName,
               medications.dose AS dose,
               medications.frequency AS frequency,
               medications.startDate AS startDate,
               medications.endDate AS endDate,
               medication_reminders.timeMinutesOfDay AS timeMinutesOfDay
        FROM medication_reminders
        INNER JOIN medications
            ON medications.id = medication_reminders.medicationId
        WHERE medication_reminders.medicationId = :medicationId
        ORDER BY medication_reminders.timeMinutesOfDay ASC
        """,
    )
    suspend fun getSchedulesForMedication(medicationId: String): List<MedicationReminderSchedule>

    @Query(
        """
        SELECT medication_reminders.id AS reminderId,
               medications.id AS medicationId,
               medications.name AS medicationName,
               medications.dose AS dose,
               medications.frequency AS frequency,
               medications.startDate AS startDate,
               medications.endDate AS endDate,
               medication_reminders.timeMinutesOfDay AS timeMinutesOfDay
        FROM medication_reminders
        INNER JOIN medications
            ON medications.id = medication_reminders.medicationId
        WHERE medication_reminders.id = :reminderId
        LIMIT 1
        """,
    )
    suspend fun getScheduleByReminderId(reminderId: String): MedicationReminderSchedule?
}

@Database(
    entities = [EncounterEntity::class, EncounterAttachmentEntity::class, MedicationEntity::class, MedicationReminderEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(MedicalRecordTypeConverters::class)
abstract class MedicalRecordDatabase : RoomDatabase() {
    abstract fun encounterDao(): EncounterDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun medicationDao(): MedicationDao
    abstract fun medicationReminderDao(): MedicationReminderDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `medication_reminders` (
                `id` TEXT NOT NULL,
                `medicationId` TEXT NOT NULL,
                `timeMinutesOfDay` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_medication_reminders_medicationId`
            ON `medication_reminders` (`medicationId`)
            """.trimIndent(),
        )
    }
}

interface EncounterRepository {
    fun observeSummaries(): Flow<List<EncounterSummary>>
    fun observeEncounter(id: String): Flow<EncounterWithAttachments?>
    suspend fun getEncounter(id: String): EncounterEntity?
    suspend fun saveEncounter(encounter: EncounterEntity): String
    suspend fun deleteEncounterCascade(id: String)
}

interface AttachmentRepository {
    fun observeAttachments(encounterId: String): Flow<List<EncounterAttachmentEntity>>
    suspend fun importAttachment(
        encounterId: String,
        sourceUri: Uri,
        fallbackName: String? = null,
        forcedMimeType: String? = null,
    )
    suspend fun getAttachment(id: String): EncounterAttachmentEntity?
    suspend fun preparePreview(id: String): File?
    suspend fun prepareThumbnail(path: String?): File?
    suspend fun deleteAttachment(id: String)
}

interface MedicationRepository {
    fun observeMedications(filter: Flow<MedicationFilter>): Flow<List<MedicationWithReminders>>
    suspend fun getMedication(id: String): MedicationWithReminders?
    suspend fun saveMedication(medication: MedicationEntity, reminderMinutesOfDay: List<Int>): String
    suspend fun deleteMedication(id: String)
}

@Singleton
class OfflineEncounterRepository @Inject constructor(
    private val database: MedicalRecordDatabase,
    private val attachmentDao: AttachmentDao,
    private val encounterDao: EncounterDao,
    private val fileEncryptionManager: FileEncryptionManager,
    private val ioDispatcher: CoroutineDispatcher,
) : EncounterRepository {
    override fun observeSummaries(): Flow<List<EncounterSummary>> = encounterDao.observeSummaries()

    override fun observeEncounter(id: String): Flow<EncounterWithAttachments?> = encounterDao.observeEncounter(id)

    override suspend fun getEncounter(id: String): EncounterEntity? = withContext(ioDispatcher) {
        encounterDao.getById(id)
    }

    override suspend fun saveEncounter(encounter: EncounterEntity): String = withContext(ioDispatcher) {
        val existing = encounterDao.getById(encounter.id)
        encounterDao.upsert(
            encounter.copy(
                createdAt = existing?.createdAt ?: encounter.createdAt,
                updatedAt = Instant.now(),
            ),
        )
        encounter.id
    }

    override suspend fun deleteEncounterCascade(id: String) = withContext(ioDispatcher) {
        val attachments = database.withTransaction {
            val snapshot = attachmentDao.getForEncounter(id)
            encounterDao.deleteById(id)
            snapshot
        }
        attachments.forEach {
            fileEncryptionManager.deleteIfExists(it.encryptedPath)
            fileEncryptionManager.deleteIfExists(it.thumbnailPath)
        }
    }
}

@Singleton
class OfflineAttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentDao: AttachmentDao,
    private val fileEncryptionManager: FileEncryptionManager,
    private val ioDispatcher: CoroutineDispatcher,
) : AttachmentRepository {
    override fun observeAttachments(encounterId: String): Flow<List<EncounterAttachmentEntity>> =
        attachmentDao.observeForEncounter(encounterId)

    override suspend fun importAttachment(
        encounterId: String,
        sourceUri: Uri,
        fallbackName: String?,
        forcedMimeType: String?,
    ) = withContext(ioDispatcher) {
        val mimeType = forcedMimeType ?: context.contentResolver.getType(sourceUri) ?: "application/octet-stream"
        val type = if (mimeType == "application/pdf") AttachmentType.PDF else AttachmentType.IMAGE
        val id = UUID.randomUUID().toString()
        val extension = when (type) {
            AttachmentType.PDF -> "pdf"
            AttachmentType.IMAGE -> mimeType.substringAfter('/', "jpg")
        }
        val encryptedFile = fileEncryptionManager.createEncryptedAttachmentFile("$id.$extension.enc")
        val thumbnailFile = fileEncryptionManager.createEncryptedThumbnailFile("$id.jpg.enc")
        val displayName = fallbackName
            ?: sourceUri.readDisplayName(context.contentResolver)
            ?: context.getString(R.string.attachment_fallback_name, id)

        val tempPlain = File(context.cacheDir, "import_$id.$extension")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempPlain.outputStream().use { plainOut -> input.copyTo(plainOut) }
            } ?: error("Unable to open source attachment.")

            val thumbnailBytes: ByteArray?
            val pageCount: Int?
            if (type == AttachmentType.PDF) {
                val (thumb, pages) = fileEncryptionManager.createPdfThumbnailAndPageCount(tempPlain)
                thumbnailBytes = thumb
                pageCount = pages
            } else {
                thumbnailBytes = fileEncryptionManager.createImageThumbnailFromUri(sourceUri)
                pageCount = null
            }

            tempPlain.inputStream().use { plainInput ->
                fileEncryptionManager.encryptInputToFile(plainInput, encryptedFile)
            }
            if (thumbnailBytes != null) {
                fileEncryptionManager.storeEncryptedBytes(thumbnailBytes, thumbnailFile)
            }

            attachmentDao.insert(
                EncounterAttachmentEntity(
                    id = id,
                    encounterId = encounterId,
                    type = type,
                    displayName = displayName,
                    mimeType = mimeType,
                    encryptedPath = encryptedFile.absolutePath,
                    thumbnailPath = thumbnailBytes?.let { thumbnailFile.absolutePath },
                    pageCount = pageCount,
                ),
            )
        } catch (error: Throwable) {
            fileEncryptionManager.deleteIfExists(encryptedFile.absolutePath)
            fileEncryptionManager.deleteIfExists(thumbnailFile.absolutePath)
            throw error
        } finally {
            tempPlain.delete()
        }
    }

    override suspend fun getAttachment(id: String): EncounterAttachmentEntity? = withContext(ioDispatcher) {
        attachmentDao.getById(id)
    }

    override suspend fun preparePreview(id: String): File? = withContext(ioDispatcher) {
        val attachment = attachmentDao.getById(id) ?: return@withContext null
        val source = File(attachment.encryptedPath)
        val targetName = when (attachment.type) {
            AttachmentType.PDF -> "${attachment.id}.pdf"
            AttachmentType.IMAGE -> "${attachment.id}.${attachment.mimeType.substringAfter('/', "jpg")}"
        }
        val previewFile = fileEncryptionManager.createPreviewCopy(targetName)
        try {
            fileEncryptionManager.decryptFileTo(source, previewFile)
        } catch (error: Throwable) {
            previewFile.delete()
            throw error
        }
    }

    override suspend fun prepareThumbnail(path: String?): File? = withContext(ioDispatcher) {
        if (path.isNullOrBlank()) return@withContext null
        val source = File(path)
        val previewFile = fileEncryptionManager.createPreviewCopy("thumb_${source.nameWithoutExtension}.jpg")
        try {
            fileEncryptionManager.decryptFileTo(source, previewFile)
        } catch (error: Throwable) {
            previewFile.delete()
            throw error
        }
    }

    override suspend fun deleteAttachment(id: String) = withContext(ioDispatcher) {
        val attachment = attachmentDao.getById(id) ?: return@withContext
        attachmentDao.delete(attachment)
        fileEncryptionManager.deleteIfExists(attachment.encryptedPath)
        fileEncryptionManager.deleteIfExists(attachment.thumbnailPath)
    }
}

@Singleton
class OfflineMedicationRepository @Inject constructor(
    private val database: MedicalRecordDatabase,
    private val medicationDao: MedicationDao,
    private val medicationReminderDao: MedicationReminderDao,
    private val ioDispatcher: CoroutineDispatcher,
) : MedicationRepository {
    override fun observeMedications(filter: Flow<MedicationFilter>): Flow<List<MedicationWithReminders>> {
        return medicationDao.observeAllWithReminders().combine(filter) { medications, selectedFilter ->
            medications.filterBy(selectedFilter)
        }
    }

    override suspend fun getMedication(id: String): MedicationWithReminders? = withContext(ioDispatcher) {
        medicationDao.getWithRemindersById(id)
    }

    override suspend fun saveMedication(medication: MedicationEntity, reminderMinutesOfDay: List<Int>): String = withContext(ioDispatcher) {
        val existing = medicationDao.getById(medication.id)
        val now = Instant.now()
        val normalizedReminders = reminderMinutesOfDay.distinct().sorted()
        database.withTransaction {
            medicationDao.upsert(
                medication.copy(
                    createdAt = existing?.createdAt ?: medication.createdAt,
                    updatedAt = now,
                ),
            )
            medicationReminderDao.deleteByMedicationId(medication.id)
            if (normalizedReminders.isNotEmpty()) {
                medicationReminderDao.insertAll(
                    normalizedReminders.map { minutes ->
                        MedicationReminderEntity(
                            medicationId = medication.id,
                            timeMinutesOfDay = minutes,
                            createdAt = now,
                            updatedAt = now,
                        )
                    },
                )
            }
        }
        medication.id
    }

    override suspend fun deleteMedication(id: String) = withContext(ioDispatcher) {
        medicationDao.deleteById(id)
    }
}

fun List<MedicationWithReminders>.filterBy(
    filter: MedicationFilter,
    today: LocalDate = LocalDate.now(),
): List<MedicationWithReminders> {
    return when (filter) {
        MedicationFilter.ALL -> this
        MedicationFilter.CURRENT -> filter {
            it.medication.endDate == null || !it.medication.endDate.isBefore(today)
        }
        MedicationFilter.ENDED -> filter {
            it.medication.endDate != null && it.medication.endDate.isBefore(today)
        }
    }
}

private fun Uri.readDisplayName(contentResolver: ContentResolver): String? {
    if (scheme == ContentResolver.SCHEME_FILE) {
        return lastPathSegment
    }
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    contentResolver.query(this, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getString(0)
        }
    }
    return null
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        securePassphraseManager: SecurePassphraseManager,
    ): MedicalRecordDatabase {
        SQLiteDatabase.loadLibs(context)
        val factory = SupportFactory(securePassphraseManager.getDatabasePassphrase())
        return Room.databaseBuilder(context, MedicalRecordDatabase::class.java, "medical-record.db")
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideEncounterDao(database: MedicalRecordDatabase): EncounterDao = database.encounterDao()

    @Provides
    fun provideAttachmentDao(database: MedicalRecordDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideMedicationDao(database: MedicalRecordDatabase): MedicationDao = database.medicationDao()

    @Provides
    fun provideMedicationReminderDao(database: MedicalRecordDatabase): MedicationReminderDao = database.medicationReminderDao()

    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideEncounterRepository(repository: OfflineEncounterRepository): EncounterRepository = repository

    @Provides
    @Singleton
    fun provideAttachmentRepository(repository: OfflineAttachmentRepository): AttachmentRepository = repository

    @Provides
    @Singleton
    fun provideMedicationRepository(repository: OfflineMedicationRepository): MedicationRepository = repository
}
