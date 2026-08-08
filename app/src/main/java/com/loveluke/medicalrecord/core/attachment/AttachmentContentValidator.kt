package com.loveluke.medicalrecord.core.attachment

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AttachmentMediaType(val canonicalMimeType: String) {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    HEIC("image/heic"),
    HEIF("image/heif"),
}

enum class AttachmentValidationFailure {
    MISSING_MIME,
    UNSUPPORTED_MIME,
    MIME_MAGIC_MISMATCH,
    MALFORMED_CONTENT,
}

sealed interface AttachmentValidationResult {
    data class Accepted(val mediaType: AttachmentMediaType) : AttachmentValidationResult
    data class Rejected(val reason: AttachmentValidationFailure) : AttachmentValidationResult
}

class AttachmentContentInspection internal constructor(
    val totalBytes: Long,
    internal val head: ByteArray,
    internal val tail: ByteArray,
)

/** Captures only bounded head/tail probes while plaintext flows directly into encryption. */
class InspectingInputStream(
    source: InputStream,
) : FilterInputStream(source) {
    private val head = ByteArray(PROBE_HEAD_BYTES)
    private var headCount = 0
    private val tail = ByteArray(PROBE_TAIL_BYTES)
    private var tailCount = 0
    private var totalBytes = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) capture(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) capture(buffer, offset, read)
        return read
    }

    fun snapshot(): AttachmentContentInspection = AttachmentContentInspection(
        totalBytes = totalBytes,
        head = head.copyOf(headCount),
        tail = tail.copyOf(tailCount),
    )

    private fun capture(buffer: ByteArray, offset: Int, length: Int) {
        totalBytes += length
        if (headCount < head.size) {
            val headWrite = minOf(length, head.size - headCount)
            buffer.copyInto(head, headCount, offset, offset + headWrite)
            headCount += headWrite
        }

        if (length >= tail.size) {
            buffer.copyInto(tail, 0, offset + length - tail.size, offset + length)
            tailCount = tail.size
        } else {
            val retained = minOf(tailCount, tail.size - length)
            if (retained > 0) {
                tail.copyInto(tail, 0, tailCount - retained, tailCount)
            }
            buffer.copyInto(tail, retained, offset, offset + length)
            tailCount = retained + length
        }
    }

    private companion object {
        const val PROBE_HEAD_BYTES = 64 * 1024
        const val PROBE_TAIL_BYTES = 4 * 1024
    }
}

object AttachmentContentValidator {
    fun validate(
        declaredMimeType: String?,
        inspection: AttachmentContentInspection,
    ): AttachmentValidationResult {
        val declared = declaredMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)
            ?: return AttachmentValidationResult.Rejected(AttachmentValidationFailure.MISSING_MIME)
        val declaredType = when (declared) {
            "application/pdf" -> AttachmentMediaType.PDF
            "image/jpeg", "image/jpg" -> AttachmentMediaType.JPEG
            "image/png" -> AttachmentMediaType.PNG
            "image/webp" -> AttachmentMediaType.WEBP
            "image/heic" -> AttachmentMediaType.HEIC
            "image/heif" -> AttachmentMediaType.HEIF
            else -> return AttachmentValidationResult.Rejected(AttachmentValidationFailure.UNSUPPORTED_MIME)
        }
        val detected = detect(inspection)
            ?: return AttachmentValidationResult.Rejected(AttachmentValidationFailure.MIME_MAGIC_MISMATCH)
        val mimeMatches = declaredType == detected ||
            (declaredType == AttachmentMediaType.HEIF && detected == AttachmentMediaType.HEIC)
        if (!mimeMatches) {
            return AttachmentValidationResult.Rejected(AttachmentValidationFailure.MIME_MAGIC_MISMATCH)
        }
        if (!isStructurallyValid(detected, inspection)) {
            return AttachmentValidationResult.Rejected(AttachmentValidationFailure.MALFORMED_CONTENT)
        }
        return AttachmentValidationResult.Accepted(detected)
    }

    private fun detect(inspection: AttachmentContentInspection): AttachmentMediaType? {
        val head = inspection.head
        return when {
            head.indexOf(PDF_MAGIC, maximumStart = 1_024) >= 0 -> AttachmentMediaType.PDF
            head.startsWith(JPEG_MAGIC) -> AttachmentMediaType.JPEG
            head.startsWith(PNG_MAGIC) -> AttachmentMediaType.PNG
            head.size >= 16 && head.sliceEquals(0, RIFF) && head.sliceEquals(8, WEBP) -> AttachmentMediaType.WEBP
            head.size >= 16 && head.sliceEquals(4, FTYP) -> detectHeifType(head)
            else -> null
        }
    }

    private fun isStructurallyValid(
        mediaType: AttachmentMediaType,
        inspection: AttachmentContentInspection,
    ): Boolean = when (mediaType) {
        AttachmentMediaType.PDF -> isValidPdf(inspection)
        AttachmentMediaType.JPEG -> isValidJpeg(inspection)
        AttachmentMediaType.PNG -> isValidPng(inspection)
        AttachmentMediaType.WEBP -> isValidWebp(inspection)
        AttachmentMediaType.HEIC,
        AttachmentMediaType.HEIF,
        -> isValidHeif(inspection)
    }

    private fun isValidPdf(inspection: AttachmentContentInspection): Boolean {
        val headerAt = inspection.head.indexOf(PDF_MAGIC, maximumStart = 1_024)
        if (headerAt < 0 || inspection.head.size < headerAt + 8) return false
        val major = inspection.head[headerAt + 5].toInt().toChar()
        val dot = inspection.head[headerAt + 6].toInt().toChar()
        val minor = inspection.head[headerAt + 7].toInt().toChar()
        return major.isDigit() && dot == '.' && minor.isDigit() && inspection.tail.indexOf(PDF_EOF) >= 0
    }

    private fun isValidJpeg(inspection: AttachmentContentInspection): Boolean {
        if (!inspection.head.startsWith(JPEG_MAGIC) || !inspection.tail.endsWith(JPEG_END)) return false
        var offset = 2
        while (offset + 3 < inspection.head.size) {
            while (offset < inspection.head.size && inspection.head[offset] != 0xFF.toByte()) offset += 1
            while (offset < inspection.head.size && inspection.head[offset] == 0xFF.toByte()) offset += 1
            if (offset >= inspection.head.size) return false
            val marker = inspection.head[offset].toInt() and 0xFF
            offset += 1
            if (marker == 0xD9 || marker == 0xDA) return false
            if (marker in 0xD0..0xD8 || marker == 0x01) continue
            if (offset + 1 >= inspection.head.size) return false
            val segmentLength = inspection.head.unsignedShortAt(offset)
            if (segmentLength < 2 || offset + segmentLength > inspection.head.size) return false
            if (marker in JPEG_START_OF_FRAME_MARKERS) {
                if (segmentLength < 7) return false
                val height = inspection.head.unsignedShortAt(offset + 3)
                val width = inspection.head.unsignedShortAt(offset + 5)
                return width > 0 && height > 0
            }
            offset += segmentLength
        }
        return false
    }

    private fun isValidPng(inspection: AttachmentContentInspection): Boolean {
        val head = inspection.head
        if (head.size < 33 || !head.startsWith(PNG_MAGIC)) return false
        if (head.intAt(8) != 13 || !head.sliceEquals(12, IHDR)) return false
        val width = head.intAt(16)
        val height = head.intAt(20)
        return width > 0 && height > 0 && inspection.tail.endsWith(PNG_IEND)
    }

    private fun isValidWebp(inspection: AttachmentContentInspection): Boolean {
        val head = inspection.head
        if (head.size < 16 || !head.sliceEquals(0, RIFF) || !head.sliceEquals(8, WEBP)) return false
        val declaredPayloadSize = head.unsignedIntLittleEndianAt(4)
        if (declaredPayloadSize + 8L != inspection.totalBytes) return false
        return WEBP_VARIANTS.any { head.sliceEquals(12, it) }
    }

    private fun isValidHeif(inspection: AttachmentContentInspection): Boolean {
        val head = inspection.head
        if (head.size < 16 || !head.sliceEquals(4, FTYP)) return false
        val boxSize = head.unsignedIntAt(0)
        if (boxSize < 16 || boxSize > inspection.totalBytes || boxSize > head.size) return false
        return readBrands(head, boxSize.toInt()).any { it in HEIF_BRANDS }
    }

    private fun detectHeifType(head: ByteArray): AttachmentMediaType? {
        val boxSize = head.unsignedIntAt(0).toInt()
        if (boxSize !in 16..head.size) return null
        val brands = readBrands(head, boxSize)
        return when {
            brands.any { it in HEIC_BRANDS } -> AttachmentMediaType.HEIC
            brands.any { it in HEIF_BRANDS } -> AttachmentMediaType.HEIF
            else -> null
        }
    }

    private fun readBrands(head: ByteArray, boxSize: Int): List<String> = buildList {
        if (boxSize < 16) return@buildList
        add(head.asciiAt(8))
        var offset = 16
        while (offset + 4 <= boxSize) {
            add(head.asciiAt(offset))
            offset += 4
        }
    }

    private val PDF_MAGIC = "%PDF-".encodeToByteArray()
    private val PDF_EOF = "%%EOF".encodeToByteArray()
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_IEND = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    private val RIFF = "RIFF".encodeToByteArray()
    private val WEBP = "WEBP".encodeToByteArray()
    private val WEBP_VARIANTS = listOf("VP8 ", "VP8L", "VP8X").map(String::encodeToByteArray)
    private val FTYP = "ftyp".encodeToByteArray()
    private val IHDR = "IHDR".encodeToByteArray()
    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis")
    private val HEIF_BRANDS = HEIC_BRANDS + setOf("mif1", "msf1")
    private val JPEG_START_OF_FRAME_MARKERS = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = sliceEquals(0, prefix)

private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
    size >= suffix.size && sliceEquals(size - suffix.size, suffix)

private fun ByteArray.sliceEquals(offset: Int, expected: ByteArray): Boolean {
    if (offset < 0 || offset + expected.size > size) return false
    return expected.indices.all { index -> this[offset + index] == expected[index] }
}

private fun ByteArray.indexOf(expected: ByteArray, maximumStart: Int = size): Int {
    val lastStart = minOf(size - expected.size, maximumStart)
    for (offset in 0..lastStart) {
        if (sliceEquals(offset, expected)) return offset
    }
    return -1
}

private fun ByteArray.unsignedShortAt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.intAt(offset: Int): Int =
    ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int

private fun ByteArray.unsignedIntAt(offset: Int): Long = intAt(offset).toLong() and 0xFFFF_FFFFL

private fun ByteArray.unsignedIntLittleEndianAt(offset: Int): Long =
    ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

private fun ByteArray.asciiAt(offset: Int): String =
    copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
