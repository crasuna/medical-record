package com.loveluke.medicalrecord.core.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SqlCipherFactoryProviderTest {
    @Test
    fun `fail closed material is returned without constructing a factory`() {
        var builderCalled = false
        val provider = SqlCipherFactoryProvider.forTesting(
            passphraseResolver = {
                SecureMaterialResolution.FailClosed(SecureMaterialFailure.WRAPPING_KEY_MISSING)
            },
            libraryLoader = {},
            factoryBuilder = {
                builderCalled = true
                throw AssertionError("Factory must not be built.")
            },
        )

        val result = provider.create(File("medical-record.db"))

        assertEquals(
            SqlCipherFactoryResolution.FailClosed(SecureMaterialFailure.WRAPPING_KEY_MISSING),
            result,
        )
        assertFalse(builderCalled)
    }

    @Test
    fun `native load failure does not resolve or provision secret material`() {
        var resolverCalled = false
        val provider = SqlCipherFactoryProvider.forTesting(
            passphraseResolver = {
                resolverCalled = true
                throw AssertionError("Secret resolution must not run.")
            },
            libraryLoader = { throw UnsatisfiedLinkError("unavailable") },
            factoryBuilder = { throw AssertionError("Factory must not be built.") },
        )

        val result = provider.create(File("medical-record.db"))

        assertEquals(
            SqlCipherFactoryResolution.FailClosed(
                SecureMaterialFailure.SQLCIPHER_LIBRARY_UNAVAILABLE,
            ),
            result,
        )
        assertFalse(resolverCalled)
    }

    @Test
    fun `provider closes source secret and clears factory copy after first open`() {
        val passphrase = ByteArray(32) { index -> (index + 1).toByte() }
        val sourceSecret = SecretBytes.copyOf(passphrase)
        lateinit var factoryOwnedPassphrase: ByteArray
        val database = fakeDatabase()
        val provider = SqlCipherFactoryProvider.forTesting(
            passphraseResolver = { SecureMaterialResolution.Provisioned(sourceSecret) },
            libraryLoader = {},
            factoryBuilder = { received ->
                factoryOwnedPassphrase = received
                SupportSQLiteOpenHelper.Factory { FakeOpenHelper(database) }
            },
        )

        val result = provider.create(File("medical-record.db"))

        assertTrue(result is SqlCipherFactoryResolution.Ready)
        result as SqlCipherFactoryResolution.Ready
        assertTrue(result.newlyProvisioned)
        assertThrows(IllegalStateException::class.java) {
            sourceSecret.use { throw AssertionError("Destroyed source secret must not be exposed.") }
        }
        assertArrayEquals(passphrase, factoryOwnedPassphrase)

        val helper = result.factory.create(configuration())
        helper.writableDatabase

        assertArrayEquals(ByteArray(passphrase.size), factoryOwnedPassphrase)
    }

    @Test
    fun `factory construction failure clears every secret copy`() {
        val sourceSecret = SecretBytes.copyOf(ByteArray(32) { 7 })
        lateinit var attemptedFactoryCopy: ByteArray
        val provider = SqlCipherFactoryProvider.forTesting(
            passphraseResolver = { SecureMaterialResolution.Available(sourceSecret) },
            libraryLoader = {},
            factoryBuilder = { received ->
                attemptedFactoryCopy = received
                throw IllegalStateException("construction failed")
            },
        )

        val result = provider.create(File("medical-record.db"))

        assertEquals(
            SqlCipherFactoryResolution.FailClosed(
                SecureMaterialFailure.SQLCIPHER_FACTORY_CREATION_FAILED,
            ),
            result,
        )
        assertThrows(IllegalStateException::class.java) {
            sourceSecret.use { throw AssertionError("Destroyed source secret must not be exposed.") }
        }
        assertArrayEquals(ByteArray(32), attemptedFactoryCopy)
    }

    private fun configuration(): SupportSQLiteOpenHelper.Configuration {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("factory-test.db")
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()
    }

    private fun fakeDatabase(): SupportSQLiteDatabase = Proxy.newProxyInstance(
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
}

private class FakeOpenHelper(
    private val database: SupportSQLiteDatabase,
) : SupportSQLiteOpenHelper {
    override val databaseName: String = "factory-test.db"

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit

    override val writableDatabase: SupportSQLiteDatabase
        get() = database

    override val readableDatabase: SupportSQLiteDatabase
        get() = database

    override fun close() = Unit
}
