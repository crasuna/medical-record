package com.loveluke.medicalrecord.core.security

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loveluke.medicalrecord.core.database.AppDatabase
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedStorageInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keystoreWrappingKeyIsNonExportableAndNamespacedToInstalledVariant() {
        val namespace = testNamespace()
        val provider = AndroidKeystoreWrappingKeyProvider()

        try {
            val created = provider.create(namespace.wrappingKeyAlias)
            val reopened = provider.getExisting(namespace.wrappingKeyAlias)

            assertTrue(namespace.wrappingKeyAlias.startsWith(context.packageName))
            assertEquals("AES", created.algorithm)
            assertNull(created.encoded)
            assertNotNull(reopened)
            assertNull(reopened?.encoded)
        } finally {
            provider.delete(namespace.wrappingKeyAlias)
        }
    }

    @Test
    fun sqlCipherCreatesReopensRejectsWrongKeyAndRunsInWalMode() {
        val namespace = testNamespace()
        val wrappingKeys = AndroidKeystoreWrappingKeyProvider()
        val noBackupRoot = File(context.noBackupFilesDir, "instrumented-security/${namespace.value}")
        val store = SecureMaterialStore(
            noBackupFilesDir = noBackupRoot,
            installationNamespace = namespace,
            wrappingKeyProvider = wrappingKeys,
        )
        val manager = SecureMaterialManager(store)
        val provider = SqlCipherFactoryProvider(manager)
        val databaseName = "instrumented-${UUID.randomUUID()}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        var database: AppDatabase? = null

        try {
            val firstResolution = provider.create(databaseFile)
            assertTrue(firstResolution is SqlCipherFactoryResolution.Ready)
            firstResolution as SqlCipherFactoryResolution.Ready
            assertTrue(firstResolution.newlyProvisioned)
            database = openDatabase(databaseName, firstResolution)

            val sqlite = database.openHelper.writableDatabase
            val verification = SqlCipherRuntimeVerifier().verify(sqlite)
            assertTrue(verification is SqlCipherRuntimeVerification.Verified)
            verification as SqlCipherRuntimeVerification.Verified
            assertTrue(verification.sqliteVersion.isNotBlank())
            assertTrue(verification.cipherVersion.startsWith("4.17."))
            assertEquals("wal", verification.journalMode.lowercase())
            assertTrue(Os.sysconf(OsConstants._SC_PAGESIZE) > 0L)

            sqlite.execSQL(
                "CREATE TABLE IF NOT EXISTS instrumented_transaction " +
                    "(id INTEGER PRIMARY KEY, marker INTEGER NOT NULL)",
            )
            sqlite.beginTransaction()
            try {
                sqlite.execSQL("INSERT INTO instrumented_transaction(marker) VALUES (17)")
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
            assertEquals(1, sqlite.scalarInt("SELECT COUNT(*) FROM instrumented_transaction"))
            database.close()
            database = null

            assertEncryptedHeader(databaseFile)

            val reopenResolution = provider.create(databaseFile)
            assertTrue(reopenResolution is SqlCipherFactoryResolution.Ready)
            reopenResolution as SqlCipherFactoryResolution.Ready
            assertFalse(reopenResolution.newlyProvisioned)
            database = openDatabase(databaseName, reopenResolution)
            assertEquals(
                1,
                database.openHelper.writableDatabase.scalarInt(
                    "SELECT COUNT(*) FROM instrumented_transaction",
                ),
            )
            database.close()
            database = null

            val wrongPassphrase = ByteArray(32) { 0x55.toByte() }
            val wrongDatabase = AppDatabase.builder(context, databaseName)
                .openHelperFactory(SupportOpenHelperFactory(wrongPassphrase))
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
            val wrongKeyFailure = try {
                wrongDatabase.openHelper.writableDatabase
                null
            } catch (failure: RuntimeException) {
                failure
            } finally {
                wrongPassphrase.fill(0)
                wrongDatabase.close()
            }
            assertNotNull(wrongKeyFailure)
            assertArrayEquals(ByteArray(32), wrongPassphrase)

            val finalResolution = provider.create(databaseFile)
            assertTrue(finalResolution is SqlCipherFactoryResolution.Ready)
            database = openDatabase(
                databaseName,
                finalResolution as SqlCipherFactoryResolution.Ready,
            )
            assertEquals(
                1,
                database.openHelper.writableDatabase.scalarInt(
                    "SELECT COUNT(*) FROM instrumented_transaction",
                ),
            )
        } finally {
            database?.close()
            databaseArtifacts(databaseFile).forEach(File::delete)
            store.envelopeDirectory().walkBottomUp().forEach(File::delete)
            noBackupRoot.walkBottomUp().forEach(File::delete)
            wrappingKeys.delete(namespace.wrappingKeyAlias)
        }
    }

    private fun openDatabase(
        name: String,
        resolution: SqlCipherFactoryResolution.Ready,
    ): AppDatabase = AppDatabase.builder(context, name)
        .openHelperFactory(resolution.factory)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

    private fun testNamespace(): InstallationNamespace {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        return InstallationNamespace("${context.packageName}.instrumented.$suffix")
    }

    private fun assertEncryptedHeader(databaseFile: File) {
        val header = ByteArray(SQLITE_PLAINTEXT_HEADER.size)
        val read = FileInputStream(databaseFile).use { input -> input.read(header) }
        assertEquals(header.size, read)
        assertFalse(header.contentEquals(SQLITE_PLAINTEXT_HEADER))
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.scalarInt(sql: String): Int =
        query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun databaseArtifacts(databaseFile: File): List<File> = listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal"),
    )

    private companion object {
        val SQLITE_PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    }
}
