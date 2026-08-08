package com.loveluke.medicalrecord.core.security

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlCipherRuntimeVerifierTest {
    private val database: SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            else -> null
        }
    } as SupportSQLiteDatabase

    @Test
    fun `reports SQLite SQLCipher and WAL runtime details`() {
        val values = mapOf(
            "SELECT sqlite_version()" to "3.50.4",
            "PRAGMA cipher_version" to "4.17.0 community",
            "PRAGMA journal_mode" to "wal",
        )
        val verifier = SqlCipherRuntimeVerifier.forTesting { _, sql -> values[sql] }

        val result = verifier.verify(database)

        assertEquals(
            SqlCipherRuntimeVerification.Verified(
                sqliteVersion = "3.50.4",
                cipherVersion = "4.17.0 community",
                journalMode = "wal",
            ),
            result,
        )
    }

    @Test
    fun `missing cipher pragma fails closed`() {
        val verifier = SqlCipherRuntimeVerifier.forTesting { _, sql ->
            when (sql) {
                "SELECT sqlite_version()" -> "3.50.4"
                "PRAGMA cipher_version" -> null
                "PRAGMA journal_mode" -> "wal"
                else -> null
            }
        }

        val result = verifier.verify(database)

        assertEquals(
            SqlCipherRuntimeVerification.Failed(
                SqlCipherRuntimeVerificationFailure.CIPHER_VERSION_UNAVAILABLE,
            ),
            result,
        )
    }

    @Test
    fun `non WAL journal mode is an explicit gate failure`() {
        val verifier = SqlCipherRuntimeVerifier.forTesting { _, sql ->
            when (sql) {
                "SELECT sqlite_version()" -> "3.50.4"
                "PRAGMA cipher_version" -> "4.17.0 community"
                "PRAGMA journal_mode" -> "delete"
                else -> null
            }
        }

        val result = verifier.verify(database)

        assertTrue(result is SqlCipherRuntimeVerification.Failed)
        assertEquals(
            SqlCipherRuntimeVerificationFailure.WAL_NOT_ENABLED,
            (result as SqlCipherRuntimeVerification.Failed).reason,
        )
    }
}
