package dev.abstra.pro.ai.graph

import dev.abstra.pro.ai.model.AudioSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * KNN recall measurement: LSH candidate retrieval + exact re-ranking vs
 * pure exact search, on synthetic libraries in the 100..10k track range.
 *
 * Each song is planted with one true nearest neighbour at a small feature
 * offset, so recall@k can be measured honestly. Recall must not degrade
 * below a floor; timings are printed for the benchmark record (not
 * asserted — wall-clock varies between machines).
 *
 * These are JVM unit tests: absolute times overstate on-device costs (no
 * GC pressure from the app, warm JIT differences), but the recall numbers
 * and relative scaling are representative.
 */
class KnnRecallBenchmarkTest {

    private object NoopDao : dev.abstra.pro.data.db.dao.SongNeighborDao {
        override suspend fun getAllSync() = emptyList<dev.abstra.pro.data.db.entities.SongNeighborEntity>()
        override suspend fun getNeighborsFor(songId: String) = emptyList<dev.abstra.pro.data.db.entities.SongNeighborEntity>()
        override suspend fun getReverseNeighborsFor(songId: String) = emptyList<dev.abstra.pro.data.db.entities.SongNeighborEntity>()
        override suspend fun countAll() = 0
        override suspend fun insertAll(edges: List<dev.abstra.pro.data.db.entities.SongNeighborEntity>) {}
        override suspend fun deleteEdgesFor(songId: String, edgeType: String) {}
        override suspend fun deleteReverseEdgesFor(songId: String) {}
        override suspend fun deleteAllEdgesOf(songId: String) {}
        override suspend fun clearAll() {}
    }

    private val builder = SongGraphBuilder(NoopDao)

    private fun signature(songId: String, vector: FloatArray): AudioSignature {
        val mfccMean = vector.copyOfRange(0, 13)
        val mfccStd = vector.copyOfRange(13, 26)
        val chroma = vector.copyOfRange(26, 38)
        val scalars = vector.copyOfRange(38, 44)
        return AudioSignature(
            songId = songId,
            mfccMean = mfccMean,
            mfccStd = mfccStd,
            chroma = chroma,
            chromaConfidence = 0.9f,
            tempoBpm = 120f,
            tempoConfidence = 0.9f,
            rmsEnergy = scalars[0],
            dynamicRange = scalars[1],
            spectralCentroid = scalars[2],
            spectralFlux = scalars[3],
            onsetDensity = scalars[4],
            rhythmActivity = scalars[5],
            featureConfidence = 0.9f,
            extractorVersion = AudioSignature.EXTRACTOR_VERSION,
            metadataFingerprint = "fp-$songId"
        )
    }

    /** Builds a synthetic library with one planted neighbour per song. */
    private fun buildLibrary(
        size: Int,
        seed: Long
    ): Pair<Map<String, AudioSignature>, Map<String, String>> {
        val random = Random(seed)
        val signatures = LinkedHashMap<String, AudioSignature>(size)
        val planted = HashMap<String, String>(size)
        var index = 0
        while (index < size) {
            // Cluster base: sparse timbre vectors (like real MFCC data).
            val base = FloatArray(44) { 0f }
            repeat(10) { base[random.nextInt(26)] = random.nextFloat() * 0.8f + 0.1f }
            for (i in 26 until 44) base[i] = random.nextFloat()

            val aId = "s$index"
            signatures[aId] = signature(aId, base)
            if (index + 1 < size) {
                val bId = "s${index + 1}"
                // Non-negative perturbation: negative noise on a zero MFCC-std
                // dimension would make the signature structurally invalid.
                val nearVector = FloatArray(44) { base[it] + random.nextFloat() * 0.01f }
                signatures[bId] = signature(bId, nearVector)
                planted[aId] = bId
                planted[bId] = aId
            }
            index += 2
        }
        return signatures to planted
    }

    private fun recallOf(
        signatures: Map<String, AudioSignature>,
        planted: Map<String, String>,
        viaLsh: Boolean
    ): Double {
        val scaler = RobustScaler.of(signatures.values)
        val vectors = signatures.mapValues { (_, sig) -> SongGraphBuilder.signatureVector(sig, scaler) }
        val index = if (viaLsh) {
            LshIndex.build(vectors, seed = SongGraphBuilder.lshSeed(2))
        } else null

        var hits = 0
        var total = 0
        for ((songId, sig) in signatures) {
            val expected = planted[songId] ?: continue
            total++
            val neighbours = if (index != null) {
                builder.lshAudioNeighbours(sig, vectors.getValue(songId), index, signatures, k = 28, scaler = scaler)
                    // Too few candidates -> engine falls back to exact search.
                    ?: builder.exactAudioNeighbours(sig, signatures.values.toList(), 28, scaler)
            } else {
                builder.exactAudioNeighbours(sig, signatures.values.toList(), 28, scaler)
            }
            if (neighbours.any { it.songId == expected }) hits++
        }
        return hits.toDouble() / total.coerceAtLeast(1)
    }

    @Test
    fun `lsh recall stays high on a 1000 track library`() {
        val (signatures, planted) = buildLibrary(1000, seed = 2024L)

        val exactStart = System.nanoTime()
        val exactRecall = recallOf(signatures, planted, viaLsh = false)
        val exactMs = (System.nanoTime() - exactStart) / 1_000_000

        val lshStart = System.nanoTime()
        val lshRecall = recallOf(signatures, planted, viaLsh = true)
        val lshMs = (System.nanoTime() - lshStart) / 1_000_000

        println(
            "[knn-benchmark] 1000 tracks: exact recall=${"%.3f".format(exactRecall)} in ${exactMs}ms; " +
                    "lsh+rerank recall=${"%.3f".format(lshRecall)} in ${lshMs}ms"
        )

        // Exact search finds every planted neighbour by construction.
        assertEqualsDelta(1.0, exactRecall, 1e-9)
        // LSH must stay far above chance recall (1/999 ≈ 0.001).
        assertTrue("lsh recall too low: $lshRecall", lshRecall >= 0.6)
        assertTrue("lsh recall regressed vs exact: $lshRecall", lshRecall >= 0.9 * exactRecall)
    }

    @Test
    fun `lsh recall stays high on a 10000 track library`() {
        val (signatures, planted) = buildLibrary(10000, seed = 77L)

        val lshStart = System.nanoTime()
        val lshRecall = recallOf(signatures, planted, viaLsh = true)
        val lshMs = (System.nanoTime() - lshStart) / 1_000_000

        println("[knn-benchmark] 10000 tracks: lsh+rerank recall=${"%.3f".format(lshRecall)} in ${lshMs}ms")
        assertTrue("lsh recall too low at 10k: $lshRecall", lshRecall >= 0.6)
    }

    @Test
    fun `exact knn remains tractable at the configured threshold`() {
        // EXACT_THRESHOLD = 1500: full pairwise search must finish here
        // comfortably; this is the per-refresh cost for small libraries.
        val (signatures, planted) = buildLibrary(1500, seed = 5L)
        val scaler = RobustScaler.of(signatures.values)
        val targets = signatures.values.toList()

        val start = System.nanoTime()
        val neighbours = builder.exactAudioNeighbours(signatures.getValue("s0"), targets, 28, scaler)
        val ms = (System.nanoTime() - start) / 1_000_000

        println("[knn-benchmark] exact KNN over 1500 tracks: ${ms}ms")
        assertEquals(28, neighbours.size)
        assertEquals(planted.getValue("s0"), neighbours[0].songId)
    }

    private fun assertEqualsDelta(expected: Double, actual: Double, delta: Double) {
        assertTrue("expected $expected ±$delta but was $actual", kotlin.math.abs(expected - actual) <= delta)
    }
}
