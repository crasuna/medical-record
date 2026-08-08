package com.loveluke.medicalrecord.core.attachment

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream

/** Adapter shared by Photo Picker, SAF, and FileProvider camera Uris. */
class AndroidUriAttachmentInputSource(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    declaredMimeTypeOverride: String? = null,
    displayNameOverride: String? = null,
) : AttachmentInputSource {
    override val declaredMimeType: String? = declaredMimeTypeOverride ?: runCatching {
        contentResolver.getType(uri)
    }.getOrNull()

    override val displayName: String? = displayNameOverride ?: readDisplayName()

    override fun openStream(): InputStream =
        contentResolver.openInputStream(uri) ?: throw IOException("Attachment source is unavailable.")

    override fun checkParseability(mediaType: AttachmentMediaType): AttachmentSourceParseResult {
        return try {
            when (mediaType) {
                AttachmentMediaType.PDF -> {
                    val pageCount = parsePdfPageCount()
                    if (pageCount != null) {
                        AttachmentSourceParseResult.Passed(pageCount)
                    } else {
                        AttachmentSourceParseResult.Failed
                    }
                }
                AttachmentMediaType.JPEG,
                AttachmentMediaType.PNG,
                AttachmentMediaType.WEBP,
                AttachmentMediaType.HEIC,
                AttachmentMediaType.HEIF,
                -> if (isParseableImage()) {
                    AttachmentSourceParseResult.Passed()
                } else {
                    AttachmentSourceParseResult.Failed
                }
            }
        } catch (_: Exception) {
            AttachmentSourceParseResult.Failed
        }
    }

    private fun isParseableImage(): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { input -> BitmapFactory.decodeStream(input, null, options) }
        return options.outWidth > 0 && options.outHeight > 0 && !options.outMimeType.isNullOrBlank()
    }

    private fun parsePdfPageCount(): Int? {
        val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return null
        return descriptor.use { parcelFileDescriptor ->
            PdfRenderer(parcelFileDescriptor).use { renderer -> renderer.pageCount.takeIf { it > 0 } }
        }
    }

    private fun readDisplayName(): String? = try {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
        }
    } catch (_: Exception) {
        null
    }
}
