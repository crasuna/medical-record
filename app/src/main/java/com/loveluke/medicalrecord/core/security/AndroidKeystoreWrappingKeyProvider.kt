package com.loveluke.medicalrecord.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android Keystore-backed non-exportable AES-256 wrapping key. */
class AndroidKeystoreWrappingKeyProvider(
    private val keyStoreFactory: () -> KeyStore = {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    },
) : WrappingKeyProvider {
    override fun getExisting(alias: String): SecretKey? {
        val keyStore = keyStoreFactory()
        if (!keyStore.containsAlias(alias)) return null
        return keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Wrapping key is unavailable.")
    }

    override fun create(alias: String): SecretKey {
        check(getExisting(alias) == null) { "Refusing to replace an existing wrapping key." }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    override fun delete(alias: String): Boolean {
        val keyStore = keyStoreFactory()
        if (!keyStore.containsAlias(alias)) return false
        keyStore.deleteEntry(alias)
        return true
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
