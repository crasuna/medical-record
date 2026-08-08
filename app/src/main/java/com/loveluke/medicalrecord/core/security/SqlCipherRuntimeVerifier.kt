package com.loveluke.medicalrecord.core.security

import androidx.sqlite.db.SupportSQLiteDatabase

enum class SqlCipherRuntimeVerificationFailure {
    QUERY_FAILED,
    SQLITE_VERSION_UNAVAILABLE,
    CIPHER_VERSION_UNAVAILABLE,
    JOURNAL_MODE_UNAVAILABLE,
    WAL_NOT_ENABLED,
}

sealed interface SqlCipherRuntimeVerification {
    data class Verified(
        val sqliteVersion: String,
        val cipherVersion: String,
        val journalMode: String,
    ) : SqlCipherRuntimeVerification

    data class Failed(
        val reason: SqlCipherRuntimeVerificationFailure,
    ) : SqlCipherRuntimeVerification
}

/** Queries only non-sensitive runtime metadata and treats missing SQLCipher or WAL as a gate failure. */
class SqlCipherRuntimeVerifier private constructor(
    private val scalarQuery: (SupportSQLiteDatabase, String) -> String?,
) {
    constructor() : this(::queryScalar)

    fun verify(database: SupportSQLiteDatabase): SqlCipherRuntimeVerification {
        val sqliteVersion: String
        val cipherVersion: String
        val journalMode: String
        try {
            sqliteVersion = scalarQuery(database, SQLITE_VERSION_QUERY).orEmpty().trim()
            cipherVersion = scalarQuery(database, CIPHER_VERSION_QUERY).orEmpty().trim()
            journalMode = scalarQuery(database, JOURNAL_MODE_QUERY).orEmpty().trim()
        } catch (_: RuntimeException) {
            return SqlCipherRuntimeVerification.Failed(
                SqlCipherRuntimeVerificationFailure.QUERY_FAILED,
            )
        }

        if (sqliteVersion.isEmpty()) {
            return SqlCipherRuntimeVerification.Failed(
                SqlCipherRuntimeVerificationFailure.SQLITE_VERSION_UNAVAILABLE,
            )
        }
        if (cipherVersion.isEmpty()) {
            return SqlCipherRuntimeVerification.Failed(
                SqlCipherRuntimeVerificationFailure.CIPHER_VERSION_UNAVAILABLE,
            )
        }
        if (journalMode.isEmpty()) {
            return SqlCipherRuntimeVerification.Failed(
                SqlCipherRuntimeVerificationFailure.JOURNAL_MODE_UNAVAILABLE,
            )
        }
        if (!journalMode.equals(WAL_MODE, ignoreCase = true)) {
            return SqlCipherRuntimeVerification.Failed(
                SqlCipherRuntimeVerificationFailure.WAL_NOT_ENABLED,
            )
        }
        return SqlCipherRuntimeVerification.Verified(
            sqliteVersion = sqliteVersion,
            cipherVersion = cipherVersion,
            journalMode = journalMode,
        )
    }

    companion object {
        private const val SQLITE_VERSION_QUERY = "SELECT sqlite_version()"
        private const val CIPHER_VERSION_QUERY = "PRAGMA cipher_version"
        private const val JOURNAL_MODE_QUERY = "PRAGMA journal_mode"
        private const val WAL_MODE = "wal"

        internal fun forTesting(
            scalarQuery: (SupportSQLiteDatabase, String) -> String?,
        ): SqlCipherRuntimeVerifier = SqlCipherRuntimeVerifier(scalarQuery)

        private fun queryScalar(database: SupportSQLiteDatabase, sql: String): String? =
            database.query(sql).use { cursor ->
                if (!cursor.moveToFirst() || cursor.columnCount == 0 || cursor.isNull(0)) {
                    null
                } else {
                    cursor.getString(0)
                }
            }
    }
}
