package com.crasuna.medicalrecord

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

private const val DATABASE_ALIAS = "medical_record_database_key_v1"
private const val ATTACHMENT_ALIAS = "medical_record_attachment_key_v1"
private const val KEYSTORE_NAME = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_SIZE = 12

@Singleton
class SecurePassphraseManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("secure_keys", Context.MODE_PRIVATE)

    fun getDatabasePassphrase(): ByteArray {
        val existingPayload = prefs.getString("db_key_payload", null)
        val existingIv = prefs.getString("db_key_iv", null)
        if (existingPayload != null && existingIv != null) {
            return decryptWrappedKey(existingPayload, existingIv)
        }

        val keyMaterial = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(DATABASE_ALIAS))
        }
        val encrypted = cipher.doFinal(keyMaterial)
        prefs.edit()
            .putString("db_key_payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("db_key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return keyMaterial
    }

    private fun decryptWrappedKey(payloadBase64: String, ivBase64: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(DATABASE_ALIAS),
                javax.crypto.spec.GCMParameterSpec(128, Base64.decode(ivBase64, Base64.NO_WRAP)),
            )
        }
        return cipher.doFinal(Base64.decode(payloadBase64, Base64.NO_WRAP))
    }

    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}

@Singleton
class FileEncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val attachmentDirectory = File(context.filesDir, "attachments").apply { mkdirs() }
    private val previewDirectory = File(context.cacheDir, "preview").apply { mkdirs() }

    fun createEncryptedAttachmentFile(name: String): File {
        return File(attachmentDirectory, name)
    }

    fun createEncryptedThumbnailFile(name: String): File {
        return File(attachmentDirectory, "thumb_$name")
    }

    fun createPreviewCopy(name: String): File {
        return File(previewDirectory, name)
    }

    fun encryptInputToFile(input: InputStream, output: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateAttachmentKey())
        }
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { fileOut ->
            fileOut.write(cipher.iv)
            CipherOutputStream(fileOut, cipher).use { encryptedOut ->
                input.copyTo(encryptedOut)
            }
        }
    }

    fun decryptFileTo(file: File, destination: File): File {
        destination.parentFile?.mkdirs()
        FileInputStream(file).use { encryptedIn ->
            val iv = ByteArray(IV_SIZE)
            val read = encryptedIn.read(iv)
            require(read == IV_SIZE) { "Invalid encrypted file header." }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateAttachmentKey(),
                    javax.crypto.spec.GCMParameterSpec(128, iv),
                )
            }
            CipherInputStream(encryptedIn, cipher).use { decrypted ->
                FileOutputStream(destination).use { plainOut ->
                    decrypted.copyTo(plainOut)
                }
            }
        }
        return destination
    }

    fun decryptToBytes(file: File): ByteArray {
        val preview = createPreviewCopy("bytes_${file.name}")
        decryptFileTo(file, preview)
        return preview.readBytes().also { preview.delete() }
    }

    fun createImageThumbnailFromUri(uri: Uri, maxSize: Int = 512): ByteArray? {
        val data = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes()
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        return createScaledJpeg(bitmap, maxSize)
    }

    fun createPdfThumbnailAndPageCount(plainPdf: File, maxSize: Int = 512): Pair<ByteArray?, Int> {
        ParcelFileDescriptor.open(plainPdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = renderer.pageCount
                if (pageCount == 0) return null to 0
                renderer.openPage(0).use { page ->
                    val width = maxSize
                    val height = (page.height * (width.toFloat() / page.width)).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return createScaledJpeg(bitmap, maxSize) to pageCount
                }
            }
        }
    }

    fun storeEncryptedBytes(data: ByteArray, output: File) {
        encryptInputToFile(ByteArrayInputStream(data), output)
    }

    private fun createScaledJpeg(bitmap: Bitmap, maxSize: Int): ByteArray {
        val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== bitmap) {
                scaled.recycle()
            }
            out.toByteArray()
        }
    }

    fun deleteIfExists(path: String?) {
        if (path.isNullOrBlank()) return
        File(path).delete()
    }

    private fun getOrCreateAttachmentKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_NAME).apply { load(null) }
        (keyStore.getKey(ATTACHMENT_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME)
        val spec = KeyGenParameterSpec.Builder(
            ATTACHMENT_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}

fun Context.cacheUriFor(file: File): Uri = file.toUri()
