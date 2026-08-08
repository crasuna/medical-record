package com.loveluke.medicalrecord.core.security

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns mutable secret bytes and erases them when closed.
 *
 * Callers only receive the live array inside [use]. They must not retain it.
 */
class SecretBytes private constructor(
    private val bytes: ByteArray,
) : AutoCloseable {
    private val destroyed = AtomicBoolean(false)

    val size: Int
        get() = bytes.size

    fun <T> use(block: (ByteArray) -> T): T {
        check(!destroyed.get()) { "Secret material is no longer available." }
        return block(bytes)
    }

    override fun close() {
        if (destroyed.compareAndSet(false, true)) {
            bytes.fill(0)
        }
    }

    companion object {
        fun copyOf(source: ByteArray): SecretBytes = SecretBytes(source.copyOf())

        internal fun takeOwnership(source: ByteArray): SecretBytes = SecretBytes(source)
    }
}
