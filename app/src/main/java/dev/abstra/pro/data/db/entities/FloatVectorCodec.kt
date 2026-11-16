package dev.abstra.pro.data.db.entities

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compact little-endian Float32 encoding for feature vectors stored as
 * BLOBs in Room. One encoder/decoder pair keeps the on-disk format stable
 * and round-trip exact; no comma-separated numeric strings.
 */
object FloatVectorCodec {

    /** Encodes a float array as little-endian Float32 bytes. */
    fun encode(values: FloatArray): ByteArray {
        require(values.size <= MAX_ELEMENTS) { "vector too large: ${values.size}" }
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }

    /** Decodes little-endian Float32 bytes back into a float array. */
    fun decode(bytes: ByteArray?): FloatArray {
        if (bytes == null || bytes.isEmpty()) return FloatArray(0)
        require(bytes.size % 4 == 0) { "corrupt blob length: ${bytes.size}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.size / 4
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = buffer.float
        return out
    }

    const val MAX_ELEMENTS = 1024
}
