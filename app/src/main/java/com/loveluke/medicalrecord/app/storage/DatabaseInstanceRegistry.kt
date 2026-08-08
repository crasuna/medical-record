package com.loveluke.medicalrecord.app.storage

import com.loveluke.medicalrecord.core.database.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks only a database instance that has already passed the SQLCipher runtime checks.
 *
 * Recovery must never call the Hilt [javax.inject.Provider] for [AppDatabase]: doing so could
 * retry the same failed open that caused the locked state. Closing this registry is also a
 * one-way operation for the current process, because repositories in the singleton graph may
 * still hold the old Room instance. A successful local-data clear therefore requires restart.
 */
@Singleton
class DatabaseInstanceRegistry @Inject constructor() {
    private val monitor = Any()
    private var database: AppDatabase? = null
    private var closedForRecovery: Boolean = false

    fun register(verifiedDatabase: AppDatabase) {
        synchronized(monitor) {
            check(!closedForRecovery) {
                "The encrypted database cannot be republished after recovery has started."
            }
            val current = database
            check(current == null || current === verifiedDatabase) {
                "A different encrypted database instance is already registered."
            }
            database = verifiedDatabase
        }
    }

    fun closeIfOpen() {
        val current = synchronized(monitor) {
            closedForRecovery = true
            database
        }
        current?.close()
        synchronized(monitor) {
            if (database === current) database = null
        }
    }
}
