package dev.abstra.pro.data.db

import dev.abstra.pro.data.db.entities.FloatVectorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Round-trip and robustness guarantees of the Float32 blob codec. */
class FloatVectorCodecTest {

    @Test
    fun `round trip is exact for ordinary vectors`() {
        val vector = floatArrayOf(0f, 0.25f, -0.75f, 1f, 1234.5f, -9876.25f)
        val decoded = FloatVectorCodec.decode(FloatVectorCodec.encode(vector))
        assertEquals(vector.size, decoded.size)
        vector.forEachIndexed { i, v -> assertEquals(v, decoded[i], 0f) }
    }

    @Test
    fun `round trip is exact for special float values`() {
        val vector = floatArrayOf(
            Float.MAX_VALUE, Float.MIN_VALUE, -1f, -0f,
            Float.POSITIVE_INFINITY, Float.NaN
        )
        val decoded = FloatVectorCodec.decode(FloatVectorCodec.encode(vector))
        assertEquals(Float.POSITIVE_INFINITY, decoded[4], 0f)
        assertEquals(-0f, decoded[3], 0f)
        assertTrue(decoded[5].isNaN())
        vector.take(4).forEachIndexed { i, v -> assertEquals(v, decoded[i], 0f) }
    }

    @Test
    fun `long deterministic vector survives the round trip`() {
        val vector = FloatArray(FloatVectorCodec.MAX_ELEMENTS) { i ->
            sin(2.0 * PI * i / 64.0).toFloat()
        }
        val decoded = FloatVectorCodec.decode(FloatVectorCodec.encode(vector))
        vector.forEachIndexed { i, v -> assertEquals(v, decoded[i], 0f) }
    }

    @Test
    fun `empty vector encodes to empty blob and decodes back`() {
        val blob = FloatVectorCodec.encode(FloatArray(0))
        assertEquals(0, blob.size)
        assertEquals(0, FloatVectorCodec.decode(blob).size)
        assertEquals(0, FloatVectorCodec.decode(null).size)
    }

    @Test
    fun `blob length must be a multiple of four`() {
        assertThrows(IllegalArgumentException::class.java) {
            FloatVectorCodec.decode(byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun `oversized vectors are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FloatVectorCodec.encode(FloatArray(FloatVectorCodec.MAX_ELEMENTS + 1))
        }
        // Exactly at the limit is fine.
        FloatVectorCodec.encode(FloatArray(FloatVectorCodec.MAX_ELEMENTS))
    }
}
