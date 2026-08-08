package com.loveluke.medicalrecord.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureMaterialStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `blank installation provisions an isolated envelope`() {
        val keys = FakeWrappingKeyProvider()
        val namespace = InstallationNamespace("com.loveluke.medicalrecord.debug")
        val store = newStore(namespace, keys)

        val result = store.resolve(
            purpose = SecureMaterialPurpose.DATABASE_PASSPHRASE,
            sensitiveDataExists = false,
        )

        assertTrue(result is SecureMaterialResolution.Provisioned)
        result.closeSecret()
        assertTrue(store.envelopeFile(SecureMaterialPurpose.DATABASE_PASSPHRASE).isFile)
        assertTrue(store.envelopeFile(SecureMaterialPurpose.DATABASE_PASSPHRASE).name.startsWith(namespace.value))
        assertEquals(1, keys.createCount)
    }

    @Test
    fun `existing envelope with missing alias fails closed and never creates a replacement`() {
        val keys = FakeWrappingKeyProvider()
        val store = newStore(InstallationNamespace("com.loveluke.medicalrecord"), keys)
        store.resolve(SecureMaterialPurpose.ATTACHMENT_MASTER_KEY, sensitiveDataExists = false).closeSecret()
        keys.removeAlias()
        val createsBeforeResolve = keys.createCount

        val result = store.resolve(
            purpose = SecureMaterialPurpose.ATTACHMENT_MASTER_KEY,
            sensitiveDataExists = true,
        )

        assertEquals(
            SecureMaterialResolution.FailClosed(SecureMaterialFailure.WRAPPING_KEY_MISSING),
            result,
        )
        assertEquals(createsBeforeResolve, keys.createCount)
    }

    @Test
    fun `missing envelope while sensitive data exists fails closed`() {
        val keys = FakeWrappingKeyProvider()
        val store = newStore(InstallationNamespace("com.loveluke.medicalrecord"), keys)

        val result = store.resolve(
            purpose = SecureMaterialPurpose.DATABASE_PASSPHRASE,
            sensitiveDataExists = true,
        )

        assertEquals(
            SecureMaterialResolution.FailClosed(SecureMaterialFailure.ENVELOPE_MISSING_WITH_DATA),
            result,
        )
        assertEquals(0, keys.createCount)
    }

    @Test
    fun `tampered existing envelope fails closed`() {
        val keys = FakeWrappingKeyProvider()
        val store = newStore(InstallationNamespace("com.loveluke.medicalrecord"), keys)
        store.resolve(SecureMaterialPurpose.DATABASE_PASSPHRASE, sensitiveDataExists = false).closeSecret()
        val envelope = store.envelopeFile(SecureMaterialPurpose.DATABASE_PASSPHRASE)
        val bytes = envelope.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        envelope.writeBytes(bytes)

        val result = store.resolve(
            purpose = SecureMaterialPurpose.DATABASE_PASSPHRASE,
            sensitiveDataExists = true,
        )

        assertEquals(
            SecureMaterialResolution.FailClosed(SecureMaterialFailure.ENVELOPE_AUTHENTICATION_FAILED),
            result,
        )
        assertFalse(result is SecureMaterialResolution.Available)
    }

    @Test
    fun `existing envelope never invokes potentially expensive sensitive data probe`() {
        val keys = FakeWrappingKeyProvider()
        val store = newStore(InstallationNamespace("com.loveluke.medicalrecord"), keys)
        store.resolve(
            SecureMaterialPurpose.ATTACHMENT_MASTER_KEY,
            sensitiveDataExists = false,
        ).closeSecret()
        var probeCalls = 0

        val result = store.resolve(
            purpose = SecureMaterialPurpose.ATTACHMENT_MASTER_KEY,
            sensitiveDataExists = {
                probeCalls += 1
                true
            },
        )

        assertTrue(result is SecureMaterialResolution.Available)
        result.closeSecret()
        assertEquals(0, probeCalls)
    }

    @Test
    fun `missing envelope invokes sensitive data probe exactly once`() {
        val keys = FakeWrappingKeyProvider()
        val store = newStore(InstallationNamespace("com.loveluke.medicalrecord"), keys)
        var probeCalls = 0

        val result = store.resolve(
            purpose = SecureMaterialPurpose.ATTACHMENT_MASTER_KEY,
            sensitiveDataExists = {
                probeCalls += 1
                true
            },
        )

        assertEquals(
            SecureMaterialResolution.FailClosed(SecureMaterialFailure.ENVELOPE_MISSING_WITH_DATA),
            result,
        )
        assertEquals(1, probeCalls)
    }

    private fun newStore(
        namespace: InstallationNamespace,
        keys: FakeWrappingKeyProvider,
    ): SecureMaterialStore = SecureMaterialStore(
        noBackupFilesDir = temporaryFolder.root,
        installationNamespace = namespace,
        wrappingKeyProvider = keys,
    )

    private fun SecureMaterialResolution.closeSecret() {
        when (this) {
            is SecureMaterialResolution.Available -> secret.close()
            is SecureMaterialResolution.Provisioned -> secret.close()
            is SecureMaterialResolution.FailClosed -> Unit
        }
    }
}

private class FakeWrappingKeyProvider : WrappingKeyProvider {
    private var key: SecretKey? = null
    var createCount: Int = 0
        private set

    override fun getExisting(alias: String): SecretKey? = key

    override fun create(alias: String): SecretKey {
        createCount += 1
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    override fun delete(alias: String): Boolean {
        val existed = key != null
        key = null
        return existed
    }

    fun removeAlias() {
        key = null
    }
}
