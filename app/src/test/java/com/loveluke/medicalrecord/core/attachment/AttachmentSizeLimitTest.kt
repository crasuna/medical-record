package com.loveluke.medicalrecord.core.attachment

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

class AttachmentSizeLimitTest {
    @Test
    fun `stream one byte over 50 MiB is rejected without buffering it in memory`() {
        val source = SyntheticInputStream(MAX_ATTACHMENT_BYTES + 1)

        assertThrows(AttachmentSizeLimitExceededException::class.java) {
            copyWithLimit(source, OutputStream.nullOutputStream(), MAX_ATTACHMENT_BYTES)
        }
    }
}

private class SyntheticInputStream(
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        remaining -= 1
        return 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val read = minOf(remaining, length.toLong()).toInt()
        java.util.Arrays.fill(buffer, offset, offset + read, 0.toByte())
        remaining -= read
        return read
    }
}
