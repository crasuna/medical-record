package com.loveluke.medicalrecord.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.loveluke.medicalrecord.core.database.AppDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseInstanceRegistryTest {
    @Test
    fun `recovery closes a published database and forbids republishing in this process`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = DatabaseInstanceRegistry()
        val published = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
        published.openHelper.writableDatabase
        registry.register(published)

        registry.closeIfOpen()

        assertFalse(published.isOpen)
        val replacement = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
        try {
            assertThrows(IllegalStateException::class.java) {
                registry.register(replacement)
            }
        } finally {
            replacement.close()
        }
    }

    @Test
    fun `locked recovery does not need to instantiate a database before invalidation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = DatabaseInstanceRegistry()

        registry.closeIfOpen()

        val lateDatabase = AppDatabase.inMemoryBuilder(context).allowMainThreadQueries().build()
        try {
            assertThrows(IllegalStateException::class.java) {
                registry.register(lateDatabase)
            }
        } finally {
            lateDatabase.close()
        }
    }
}
