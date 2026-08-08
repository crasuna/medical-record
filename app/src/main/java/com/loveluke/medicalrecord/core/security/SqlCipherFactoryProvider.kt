package com.loveluke.medicalrecord.core.security

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

sealed interface SqlCipherFactoryResolution {
    data class Ready(
        val factory: SupportSQLiteOpenHelper.Factory,
        val newlyProvisioned: Boolean,
    ) : SqlCipherFactoryResolution

    data class FailClosed(
        val reason: SecureMaterialFailure,
    ) : SqlCipherFactoryResolution
}

/**
 * Resolves the database passphrase and constructs the only permitted Room open-helper factory.
 *
 * The envelope-owned [SecretBytes] is closed immediately. The one copy retained by SQLCipher is
 * erased after the first database-open attempt, matching the one-shot lifetime expected by Room.
 */
class SqlCipherFactoryProvider private constructor(
    private val passphraseResolver: (File) -> SecureMaterialResolution,
    private val libraryLoader: () -> Unit,
    private val factoryBuilder: (ByteArray) -> SupportSQLiteOpenHelper.Factory,
) {
    constructor(secureMaterialManager: SecureMaterialManager) : this(
        passphraseResolver = secureMaterialManager::resolveDatabasePassphrase,
        libraryLoader = { System.loadLibrary(SQLCIPHER_LIBRARY_NAME) },
        factoryBuilder = { passphrase -> SupportOpenHelperFactory(passphrase) },
    )

    fun create(databaseFile: File): SqlCipherFactoryResolution {
        try {
            libraryLoader()
        } catch (_: LinkageError) {
            return SqlCipherFactoryResolution.FailClosed(
                SecureMaterialFailure.SQLCIPHER_LIBRARY_UNAVAILABLE,
            )
        } catch (_: SecurityException) {
            return SqlCipherFactoryResolution.FailClosed(
                SecureMaterialFailure.SQLCIPHER_LIBRARY_UNAVAILABLE,
            )
        } catch (_: RuntimeException) {
            return SqlCipherFactoryResolution.FailClosed(
                SecureMaterialFailure.SQLCIPHER_LIBRARY_UNAVAILABLE,
            )
        }

        val material = when (val resolved = passphraseResolver(databaseFile)) {
            is SecureMaterialResolution.FailClosed -> return SqlCipherFactoryResolution.FailClosed(
                resolved.reason,
            )

            is SecureMaterialResolution.Available -> ResolvedPassphrase(
                secret = resolved.secret,
                newlyProvisioned = false,
            )

            is SecureMaterialResolution.Provisioned -> ResolvedPassphrase(
                secret = resolved.secret,
                newlyProvisioned = true,
            )
        }

        var factoryPassphrase: ByteArray? = null
        return try {
            factoryPassphrase = material.secret.use(ByteArray::copyOf)
            val delegateFactory = factoryBuilder(requireNotNull(factoryPassphrase))
            val clearingFactory = PassphraseClearingOpenHelperFactory(
                delegate = delegateFactory,
                passphrase = requireNotNull(factoryPassphrase),
            )
            factoryPassphrase = null // Ownership moved into clearingFactory.
            SqlCipherFactoryResolution.Ready(
                factory = clearingFactory,
                newlyProvisioned = material.newlyProvisioned,
            )
        } catch (_: LinkageError) {
            factoryPassphrase?.fill(0)
            SqlCipherFactoryResolution.FailClosed(
                SecureMaterialFailure.SQLCIPHER_FACTORY_CREATION_FAILED,
            )
        } catch (_: RuntimeException) {
            factoryPassphrase?.fill(0)
            SqlCipherFactoryResolution.FailClosed(
                SecureMaterialFailure.SQLCIPHER_FACTORY_CREATION_FAILED,
            )
        } finally {
            material.secret.close()
        }
    }

    private data class ResolvedPassphrase(
        val secret: SecretBytes,
        val newlyProvisioned: Boolean,
    )

    companion object {
        private const val SQLCIPHER_LIBRARY_NAME = "sqlcipher"

        internal fun forTesting(
            passphraseResolver: (File) -> SecureMaterialResolution,
            libraryLoader: () -> Unit,
            factoryBuilder: (ByteArray) -> SupportSQLiteOpenHelper.Factory,
        ): SqlCipherFactoryProvider = SqlCipherFactoryProvider(
            passphraseResolver = passphraseResolver,
            libraryLoader = libraryLoader,
            factoryBuilder = factoryBuilder,
        )
    }
}

/**
 * SQLCipher 4.17's SupportOpenHelperFactory retains the caller's byte array. This wrapper ensures
 * that retained array is erased after the first open attempt and prevents unsafe factory reuse.
 */
private class PassphraseClearingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
    private val passphrase: ByteArray,
) : SupportSQLiteOpenHelper.Factory {
    private val created = AtomicBoolean(false)

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(created.compareAndSet(false, true)) { "SQLCipher factory is single-use." }
        return try {
            PassphraseClearingOpenHelper(
                delegate = delegate.create(configuration),
                passphrase = passphrase,
            )
        } catch (error: Throwable) {
            passphrase.fill(0)
            throw error
        }
    }
}

private class PassphraseClearingOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
    private val passphrase: ByteArray,
) : SupportSQLiteOpenHelper {
    private val cleared = AtomicBoolean(false)

    override val databaseName: String?
        get() = delegate.databaseName

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        delegate.setWriteAheadLoggingEnabled(enabled)
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = openOnce { delegate.writableDatabase }

    override val readableDatabase: SupportSQLiteDatabase
        get() = openOnce { delegate.readableDatabase }

    override fun close() {
        try {
            delegate.close()
        } finally {
            clearPassphrase()
        }
    }

    private inline fun <T> openOnce(block: () -> T): T = try {
        block()
    } finally {
        clearPassphrase()
    }

    private fun clearPassphrase() {
        if (cleared.compareAndSet(false, true)) {
            passphrase.fill(0)
        }
    }
}
