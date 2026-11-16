package dev.abstra.pro.ai.graph

import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.data.db.dao.SongNeighborDao
import dev.abstra.pro.data.db.entities.SongNeighborEntity
import dev.abstra.pro.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Song graph construction: exact KNN, LSH candidate recall with exact
 * re-ranking, and combined edge weighting for Personalized PageRank.
 */
class SongGraphBuilderTest {

    private val builder = SongGraphBuilder(NoopDao)

    private object NoopDao : SongNeighborDao {
        override suspend fun getAllSync(): List<SongNeighborEntity> = emptyList()
        override suspend fun getNeighborsFor(songId: String): List<SongNeighborEntity> = emptyList()
        override suspend fun getReverseNeighborsFor(songId: String): List<SongNeighborEntity> = emptyList()
        override suspend fun countAll(): Int = 0
        override suspend fun insertAll(edges: List<SongNeighborEntity>) {}
        override suspend fun deleteEdgesFor(songId: String, edgeType: String) {}
        override suspend fun deleteReverseEdgesFor(songId: String) {}
        override suspend fun deleteAllEdgesOf(songId: String) {}
        override suspend fun clearAll() {}
    }

    private fun signature(songId: String, mfccValue: Float, tempoBpm: Float = 120f): AudioSignature =
        AudioSignature(
            songId = songId,
            mfccMean = FloatArray(13) { mfccValue },
            mfccStd = FloatArray(13) { 0.05f },
            chroma = FloatArray(12) { 1f / 12f },
            chromaConfidence = 0.9f,
            tempoBpm = tempoBpm,
            tempoConfidence = 0.9f,
            rmsEnergy = 0.5f,
            dynamicRange = 0.5f,
            spectralCentroid = 0.5f,
            spectralFlux = 0.5f,
            onsetDensity = 0.5f,
            rhythmActivity = 0.5f,
            featureConfidence = 0.9f,
            extractorVersion = AudioSignature.EXTRACTOR_VERSION,
            metadataFingerprint = "fp-$songId"
        )

    private fun song(id: String, artist: String = "A", album: String = "Al", genre: String? = "Rock") =
        Song(
            id = id, title = "Song $id", artist = artist, album = album,
            durationMs = 200_000L, uriString = "content://x/$id", folderName = "Music",
            filePath = "/storage/$id.mp3", dateAdded = 0L, genre = genre
        )

    // ── Exact KNN ──

    @Test
    fun `exact knn excludes self and keeps the strongest neighbours`() {
        val target = signature("target", 0.1f)
        val candidates = listOf(
            signature("close", 0.11f),
            signature("far", 0.9f),
            signature("mid", 0.15f),
            signature("target", 0.1f) // self must be dropped
        )

        val neighbours = builder.exactAudioNeighbours(target, candidates, k = 2, scaler = null)

        assertEquals(2, neighbours.size)
        assertEquals("close", neighbours[0].songId)
        assertEquals("mid", neighbours[1].songId)
        assertTrue(neighbours[0].similarity > neighbours[1].similarity)
    }

    @Test
    fun `exact knn is deterministic on ties`() {
        val target = signature("target", 0.1f)
        val candidates = listOf(signature("z", 0.3f), signature("a", 0.3f))
        val first = builder.exactAudioNeighbours(target, candidates, 2, null)
        val second = builder.exactAudioNeighbours(target, candidates, 2, null)

        assertEquals(first, second)
        assertEquals("a", first[0].songId) // ties break by song id
    }

    @Test
    fun `metadata knn ranks shared artist album and genre first`() {
        val target = song("t")
        val songs = listOf(song("same", artist = "A", album = "Al", genre = "Rock"), song("diff", artist = "B", album = "X", genre = "Jazz"))

        val neighbours = builder.metadataNeighbours(target, songs, k = 2)

        assertEquals(2, neighbours.size)
        assertEquals("same", neighbours[0].songId)
        assertTrue(neighbours[0].similarity > neighbours[1].similarity)
    }

    // ── LSH ──

    @Test
    fun `lsh recalls the true nearest neighbour among distractors`() {
        val random = Random(7)
        val dimensions = 44
        val target = FloatArray(dimensions) { random.nextFloat() * 2f - 1f }
        val near = FloatArray(dimensions) { target[it] + random.nextFloat() * 0.02f - 0.01f }
        val vectors = mutableMapOf("target" to target, "near" to near)
        repeat(50) { i ->
            vectors["far$i"] = FloatArray(dimensions) { random.nextFloat() * 2f - 1f }
        }

        val index = LshIndex.build(vectors, seed = 42)
        val candidates = index.query(target, limit = 600)

        assertTrue("candidates: $candidates", "near" in candidates)
        // The exact bucket also contains the query itself.
        assertTrue("target" in candidates)
    }

    @Test
    fun `lsh is deterministic for a given seed`() {
        val random = Random(3)
        val vectors = (0 until 30).associate { i ->
            "v$i" to FloatArray(44) { random.nextFloat() }
        }

        val a = LshIndex.build(vectors, seed = 123).query(vectors.values.first(), 600)
        val b = LshIndex.build(vectors, seed = 123).query(vectors.values.first(), 600)
        val c = LshIndex.build(vectors, seed = 456).query(vectors.values.first(), 600)

        assertEquals(a, b)
    }

    @Test
    fun `lsh neighbours return null when candidate set is too small`() {
        val signatures = (0 until 10).associate { "s$it" to signature("s$it", it * 0.1f) }
        val vectors = signatures.mapValues { (_, sig) -> SongGraphBuilder.signatureVector(sig, null) }
        val index = LshIndex.build(vectors, seed = 1)
        val target = signatures.getValue("s0")

        assertNull(
            builder.lshAudioNeighbours(
                target, vectors.getValue("s0"), index, signatures, k = 5, scaler = null
            )
        )
    }

    @Test
    fun `lsh neighbours rerank exactly and rank the closest first`() {
        // Distractors are near copies of the target (small perturbation on
        // one MFCC dimension) so they stay inside the LSH probe radius; the
        // candidate floor of 40 must be met and exact re-ranking must put
        // the true nearest neighbour first.
        val target = signature("target", 0.1f)
        val signatures = LinkedHashMap<String, AudioSignature>()
        signatures["target"] = target
        signatures["near"] = signature("near", 0.101f)
        repeat(100) { i ->
            val perturbed = FloatArray(13) { 0.1f }.also { it[i % 13] += 0.1f + (i / 13) * 0.02f }
            signatures["distractor$i"] = signature("distractor$i", 0.1f).withMfccMean(perturbed)
        }
        val vectors = signatures.mapValues { (_, sig) -> SongGraphBuilder.signatureVector(sig, null) }
        val index = LshIndex.build(vectors, seed = 5)

        val neighbours = builder.lshAudioNeighbours(
            signatures.getValue("target"), vectors.getValue("target"), index, signatures, k = 10, scaler = null
        )

        assertNotNull(neighbours)
        assertEquals("near", neighbours!![0].songId)
        assertTrue(neighbours.size <= 10)
    }

    private fun AudioSignature.withMfccMean(values: FloatArray): AudioSignature =
        AudioSignature(
            songId = songId,
            mfccMean = values,
            mfccStd = mfccStd,
            chroma = chroma,
            chromaConfidence = chromaConfidence,
            tempoBpm = tempoBpm,
            tempoConfidence = tempoConfidence,
            rmsEnergy = rmsEnergy,
            dynamicRange = dynamicRange,
            spectralCentroid = spectralCentroid,
            spectralFlux = spectralFlux,
            onsetDensity = onsetDensity,
            rhythmActivity = rhythmActivity,
            featureConfidence = featureConfidence,
            extractorVersion = extractorVersion,
            metadataFingerprint = metadataFingerprint,
            analysisStatus = analysisStatus,
            analyzedAtMs = analyzedAtMs
        )

    // ── Combined graph ──

    @Test
    fun `combined graph weights audio metadata and transitions then normalizes`() {
        val edge = { from: String, to: String, type: String, sim: Float ->
            SongNeighborEntity(from, to, type, sim, graphVersion = 1, updatedAtMs = 0L)
        }
        val audioEdges = listOf(edge("A", "B", SongNeighborEntity.EDGE_AUDIO, 1f))
        val metadataEdges = listOf(edge("A", "B", SongNeighborEntity.EDGE_METADATA, 1f))
        val transitions = mapOf("A" to mapOf("B" to 1))

        val graph = builder.buildCombinedGraph(setOf("A", "B"), audioEdges, metadataEdges, transitions)

        // 0.6 * 1 + 0.25 * 1 + 0.15 * 1 = 1.0 -> normalized to 1.0.
        assertEquals(1f, graph["A"]!!["B"]!!, 1e-4f)
    }

    @Test
    fun `combined graph drops self edges and unknown songs`() {
        val edge = { from: String, to: String, sim: Float ->
            SongNeighborEntity(from, to, SongNeighborEntity.EDGE_AUDIO, sim, 1, 0L)
        }
        val audioEdges = listOf(
            edge("A", "A", 1f),      // self edge
            edge("A", "ghost", 1f),  // invalid target
            edge("A", "B", 0.5f)
        )

        val graph = builder.buildCombinedGraph(setOf("A", "B"), audioEdges, emptyList(), emptyMap())

        val targets = graph["A"]!!.keys
        assertEquals(setOf("B"), targets)
        assertEquals(1f, graph["A"]!!["B"]!!, 1e-4f) // only edge left -> normalized
    }

    @Test
    fun `historical transitions are smoothed so one pair cannot dominate`() {
        val transitions = mapOf("A" to mapOf("B" to 1, "C" to 9))

        val graph = builder.buildCombinedGraph(setOf("A", "B", "C"), emptyList(), emptyList(), transitions)

        val aTargets = graph["A"]!!
        // B: 0.15 * 0.1, C: 0.15 * 0.9 -> C is stronger but not unlimited.
        assertTrue(aTargets["C"]!! > aTargets["B"]!!)
        assertEquals(1f, aTargets.values.sum(), 1e-4f)
    }

    // ── Persistence plumbing (pure, with the no-op DAO) ──

    @Test
    fun `refreshAudioNeighbours persists one edge per kept neighbour`() = runBlocking {
        val song = song("t")
        val signature = signature("t", 0.1f)
        val signaturesById = mapOf(
            "t" to signature,
            "n" to signature("n", 0.11f),
            "f" to signature("f", 0.9f)
        )

        //RecordingDao captures what would be written.
        val dao = RecordingDao()
        val recordingBuilder = SongGraphBuilder(dao)
        val edges = recordingBuilder.refreshAudioNeighbours(
            song, signature, listOf(song), signaturesById, null, graphVersion = 3, nowMs = 99L
        )

        assertEquals(2, edges)
        assertEquals(2, dao.inserted.size)
        assertTrue(dao.inserted.all { it.edgeType == SongNeighborEntity.EDGE_AUDIO && it.graphVersion == 3 })
        assertTrue(dao.inserted.any { it.neighborSongId == "n" })
        assertTrue(dao.inserted.none { it.neighborSongId == "t" })
        // Replacement semantics: edges are written through replaceNeighborsFor,
        // which deletes then inserts (default DAO method) — no full clear.
        assertTrue(dao.cleared.isEmpty())
    }

    private class RecordingDao : SongNeighborDao {
        val inserted = mutableListOf<SongNeighborEntity>()
        val cleared = mutableListOf<Boolean>()

        override suspend fun getAllSync(): List<SongNeighborEntity> = inserted.toList()
        override suspend fun getNeighborsFor(songId: String): List<SongNeighborEntity> = emptyList()
        override suspend fun getReverseNeighborsFor(songId: String): List<SongNeighborEntity> = emptyList()
        override suspend fun countAll(): Int = inserted.size
        override suspend fun insertAll(edges: List<SongNeighborEntity>) {
            inserted += edges
        }
        override suspend fun deleteEdgesFor(songId: String, edgeType: String) {}
        override suspend fun deleteReverseEdgesFor(songId: String) {}
        override suspend fun deleteAllEdgesOf(songId: String) {}
        override suspend fun clearAll() {
            cleared += true
        }
    }
}
