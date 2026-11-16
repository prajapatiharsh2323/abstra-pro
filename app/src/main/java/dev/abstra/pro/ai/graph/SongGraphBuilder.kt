package dev.abstra.pro.ai.graph

import dev.abstra.pro.ai.audio.AudioSimilarity
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.data.db.dao.SongNeighborDao
import dev.abstra.pro.data.db.entities.SongNeighborEntity
import dev.abstra.pro.data.model.Song
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Builds and maintains the sparse song similarity graph.
 *
 * Nodes are songs; edges are the strongest musical relationships per edge
 * type (AUDIO / METADATA). The full pairwise similarity matrix is never
 * materialized: small libraries use exact KNN, large libraries use a
 * lightweight random-projection LSH index with exact re-ranking of the
 * retrieved candidates.
 */
class SongGraphBuilder(private val neighborDao: SongNeighborDao) {

    // ═════════════════════════════════════════════════════════════
    // Pure graph construction
    // ═════════════════════════════════════════════════════════════

    data class Neighbour(val songId: String, val similarity: Float)

    /**
     * Exact audio KNN for one target song among all signature-bearing
     * candidates. Deterministic: ties break by song id.
     */
    fun exactAudioNeighbours(
        target: AudioSignature,
        candidates: List<AudioSignature>,
        k: Int,
        scaler: RobustScaler?
    ): List<Neighbour> {
        val result = mutableListOf<Neighbour>()
        for (candidate in candidates) {
            if (candidate.songId == target.songId) continue
            val similarity = AudioSimilarity.similarity(target, candidate, scaler) ?: continue
            result += Neighbour(candidate.songId, similarity)
        }
        return result
            .sortedWith(compareByDescending<Neighbour> { it.similarity }.thenBy { it.songId })
            .take(k)
    }

    /**
     * Audio KNN via LSH candidate retrieval + exact re-ranking. Used for
     * large libraries; must not silently degrade to arbitrary songs: if the
     * retrieved candidate set is too small the caller should fall back to
     * exact search or accept a sparser neighbourhood.
     */
    fun lshAudioNeighbours(
        target: AudioSignature,
        targetVector: FloatArray,
        index: LshIndex,
        signaturesById: Map<String, AudioSignature>,
        k: Int,
        scaler: RobustScaler?,
        minCandidates: Int = MIN_LSH_CANDIDATES
    ): List<Neighbour>? {
        val candidates = index.query(targetVector, CANDIDATE_CAP)
        if (candidates.size < minCandidates) return null
        val candidateSignatures = candidates.mapNotNull { signaturesById[it] }
        return exactAudioNeighbours(target, candidateSignatures, k, scaler)
    }

    /** Metadata KNN for one song. Always available (metadata is always there). */
    fun metadataNeighbours(
        target: Song,
        songs: List<Song>,
        k: Int
    ): List<Neighbour> {
        val result = mutableListOf<Neighbour>()
        for (candidate in songs) {
            if (candidate.id == target.id) continue
            result += Neighbour(candidate.id, AudioSimilarity.metadataSimilarity(target, candidate))
        }
        return result
            .sortedWith(compareByDescending<Neighbour> { it.similarity }.thenBy { it.songId })
            .take(k)
    }

    /**
     * Rebuilds the AUDIO neighbourhood of one song and persists it.
     * Returns the number of stored edges.
     */
    suspend fun refreshAudioNeighbours(
        song: Song,
        signature: AudioSignature,
        songs: List<Song>,
        signaturesById: Map<String, AudioSignature>,
        scaler: RobustScaler?,
        graphVersion: Int,
        nowMs: Long
    ): Int {
        val candidates = signaturesById.values.toList()
        val neighbours = if (candidates.size <= EXACT_THRESHOLD) {
            exactAudioNeighbours(signature, candidates, NEIGHBOURS_PER_TYPE, scaler)
        } else {
            val index = LshIndex.build(
                signaturesById.mapValues { (_, sig) -> signatureVector(sig, scaler) },
                seed = lshSeed(graphVersion)
            )
            val targetVector = signatureVector(signature, scaler)
            lshAudioNeighbours(signature, targetVector, index, signaturesById, NEIGHBOURS_PER_TYPE, scaler)
                // LSH found too few candidates: fall back to exact search so a
                // neighbourhood is never filled with arbitrary songs.
                ?: exactAudioNeighbours(signature, candidates, NEIGHBOURS_PER_TYPE, scaler)
        }
        val edges = neighbours.map {
            SongNeighborEntity(
                songId = song.id,
                neighborSongId = it.songId,
                edgeType = SongNeighborEntity.EDGE_AUDIO,
                similarity = it.similarity,
                graphVersion = graphVersion,
                updatedAtMs = nowMs
            )
        }
        neighborDao.replaceNeighborsFor(song.id, SongNeighborEntity.EDGE_AUDIO, edges)
        return edges.size
    }

    /** Rebuilds the METADATA neighbourhood of one song and persists it. */
    suspend fun refreshMetadataNeighbours(
        song: Song,
        songs: List<Song>,
        graphVersion: Int,
        nowMs: Long
    ): Int {
        val edges = metadataNeighbours(song, songs, NEIGHBOURS_PER_TYPE).map {
            SongNeighborEntity(
                songId = song.id,
                neighborSongId = it.songId,
                edgeType = SongNeighborEntity.EDGE_METADATA,
                similarity = it.similarity,
                graphVersion = graphVersion,
                updatedAtMs = nowMs
            )
        }
        neighborDao.replaceNeighborsFor(song.id, SongNeighborEntity.EDGE_METADATA, edges)
        return edges.size
    }

    /**
     * Full graph rebuild in bounded batches. Songs without valid signatures
     * get metadata edges only. Never stores self-edges.
     */
    suspend fun rebuildGraph(
        songs: List<Song>,
        signaturesById: Map<String, AudioSignature>,
        graphVersion: Int,
        nowMs: Long,
        batchSize: Int = GRAPH_BATCH_SIZE
    ) {
        neighborDao.clearAll()
        val scaler = RobustScaler.of(signaturesById.values)

        // Audio edges: exact for small libraries, LSH above the threshold.
        val analyzed = signaturesById.values.filter { it.isValid() && it.analysisStatus == AudioSignature.STATUS_SUCCESS }
        val analyzedIds = analyzed.map { it.songId }.toSet()
        val lshIndex = if (analyzed.size > EXACT_THRESHOLD) {
            LshIndex.build(
                analyzed.associate { it.songId to signatureVector(it, scaler) },
                seed = lshSeed(graphVersion)
            )
        } else null

        val audioTargets = songs.filter { it.id in analyzedIds }
        audioTargets.chunked(batchSize).forEach { batch ->
            for (song in batch) {
                val signature = signaturesById.getValue(song.id)
                val neighbours = if (lshIndex != null) {
                    val vector = signatureVector(signature, scaler)
                    lshAudioNeighbours(
                        signature, vector, lshIndex, signaturesById,
                        NEIGHBOURS_PER_TYPE, scaler
                    ) ?: exactAudioNeighbours(signature, analyzed, NEIGHBOURS_PER_TYPE, scaler)
                } else {
                    exactAudioNeighbours(signature, analyzed, NEIGHBOURS_PER_TYPE, scaler)
                }
                neighborDao.replaceNeighborsFor(
                    song.id,
                    SongNeighborEntity.EDGE_AUDIO,
                    neighbours.map {
                        SongNeighborEntity(
                            songId = song.id,
                            neighborSongId = it.songId,
                            edgeType = SongNeighborEntity.EDGE_AUDIO,
                            similarity = it.similarity,
                            graphVersion = graphVersion,
                            updatedAtMs = nowMs
                        )
                    }
                )
            }
        }

        // Metadata edges: cover every song, analyzed or not.
        songs.chunked(batchSize).forEach { batch ->
            for (song in batch) {
                neighborDao.replaceNeighborsFor(
                    song.id,
                    SongNeighborEntity.EDGE_METADATA,
                    metadataNeighbours(song, songs, NEIGHBOURS_PER_TYPE).map {
                        SongNeighborEntity(
                            songId = song.id,
                            neighborSongId = it.songId,
                            edgeType = SongNeighborEntity.EDGE_METADATA,
                            similarity = it.similarity,
                            graphVersion = graphVersion,
                            updatedAtMs = nowMs
                        )
                    }
                )
            }
        }
    }

    /** Removes every edge touching the given songs. */
    suspend fun removeSongs(songIds: List<String>) {
        for (id in songIds) neighborDao.deleteAllEdgesOf(id)
    }

    // ═════════════════════════════════════════════════════════════
    // Combined graph for Personalized PageRank
    // ═════════════════════════════════════════════════════════════

    /**
     * Combines audio edges, metadata edges and historical listening
     * transitions into one normalized sparse graph. Audio edges dominate
     * when signatures are reliable; transitions only support.
     */
    fun buildCombinedGraph(
        validSongIds: Set<String>,
        audioEdges: List<SongNeighborEntity>,
        metadataEdges: List<SongNeighborEntity>,
        transitions: Map<String, Map<String, Int>>
    ): Map<String, Map<String, Float>> {
        val combined = HashMap<String, MutableMap<String, Float>>()

        fun addEdge(from: String, to: String, weight: Float) {
            if (from == to) return
            if (from !in validSongIds || to !in validSongIds) return
            combined.getOrPut(from) { HashMap() }[to] =
                (combined[from]?.get(to) ?: 0f) + weight
        }

        for (edge in audioEdges) addEdge(edge.songId, edge.neighborSongId, EDGE_WEIGHT_AUDIO * edge.similarity)
        for (edge in metadataEdges) addEdge(edge.songId, edge.neighborSongId, EDGE_WEIGHT_METADATA * edge.similarity)
        for ((from, targets) in transitions) {
            val total = targets.values.sum().coerceAtLeast(1)
            for ((to, count) in targets) {
                // Smoothed probability: one historical pair can never dominate.
                val smoothed = (count.toFloat() / total).coerceIn(0f, 1f)
                addEdge(from, to, EDGE_WEIGHT_TRANSITION * smoothed)
            }
        }

        // Normalize outgoing weights per node.
        val normalized = HashMap<String, Map<String, Float>>(combined.size)
        for ((from, targets) in combined) {
            val total = targets.values.sum()
            if (total <= 0f) continue
            normalized[from] = targets.mapValues { (_, w) -> w / total }
        }
        return normalized
    }

    companion object {
        /** Strongest musical neighbours retained per song per edge type. */
        const val NEIGHBOURS_PER_TYPE = 28

        /** Up to this many tracks exact pairwise comparison is affordable. */
        const val EXACT_THRESHOLD = 1500

        /** Song updates processed per DB batch during a full rebuild. */
        const val GRAPH_BATCH_SIZE = 100

        private const val MIN_LSH_CANDIDATES = 40
        private const val CANDIDATE_CAP = 600

        const val EDGE_WEIGHT_AUDIO = 0.6f
        const val EDGE_WEIGHT_METADATA = 0.25f
        const val EDGE_WEIGHT_TRANSITION = 0.15f

        fun lshSeed(graphVersion: Int): Long = 0x5DEECE66D3L xor (graphVersion.toLong() * 0x9E3779B97F4A7C15uL.toLong())

        /**
         * Compact numeric feature vector for LSH indexing: MFCC means and
         * stds, chroma and normalized scalar features. Not a similarity
         * metric by itself — just a locality-preserving projection input.
         */
        fun signatureVector(signature: AudioSignature, scaler: RobustScaler?): FloatArray {
            val vector = FloatArray(
                AudioSignature.MFCC_COUNT * 2 + AudioSignature.CHROMA_COUNT +
                        RobustScaler.FEATURES.size
            )
            var i = 0
            for (v in signature.mfccMean) vector[i++] = v
            for (v in signature.mfccStd) vector[i++] = v
            val chromaScale = if (signature.chromaConfidence > 0f) 1f else 0f
            for (v in signature.chroma) vector[i++] = v * chromaScale * 4f // boost sparse dims
            for (feature in RobustScaler.FEATURES) {
                val raw = when (feature) {
                    "rmsEnergy" -> signature.rmsEnergy
                    "dynamicRange" -> signature.dynamicRange
                    "spectralCentroid" -> signature.spectralCentroid
                    "spectralFlux" -> signature.spectralFlux
                    "onsetDensity" -> signature.onsetDensity
                    "rhythmActivity" -> signature.rhythmActivity
                    else -> 0.5f
                }
                vector[i++] = if (scaler != null) scaler.normalize(feature, raw) else raw
            }
            return vector
        }
    }
}

/**
 * Random-projection LSH index over fixed-length feature vectors.
 * Multiple hash tables of random hyperplanes produce compact bit
 * signatures; queries probe the exact bucket plus one-bit Hamming
 * neighbours, then the caller re-ranks candidates exactly.
 */
class LshIndex private constructor(
    private val tables: List<Table>,
    private val dimensions: Int
) {

    class Table(
        val hyperplanes: Array<FloatArray>,
        val offsets: FloatArray,
        val buckets: HashMap<Long, MutableList<String>>
    )

    /** Bit signature of a vector for one table. */
    private fun signature(table: Table, vector: FloatArray): Long {
        var bits = 0L
        for (b in table.hyperplanes.indices) {
            val plane = table.hyperplanes[b]
            var dot = table.offsets[b].toDouble()
            for (d in 0 until dimensions) dot += plane[d].toDouble() * vector[d].toDouble()
            if (dot >= 0.0) bits = bits or (1L shl b)
        }
        return bits
    }

    fun insert(songId: String, vector: FloatArray) {
        for (table in tables) {
            val key = signature(table, vector)
            table.buckets.getOrPut(key) { mutableListOf() }.add(songId)
        }
    }

    /**
     * Retrieves candidate ids from the exact bucket and all one-bit
     * Hamming neighbours of it, capped at [limit]. Returns distinct ids.
     */
    fun query(vector: FloatArray, limit: Int): List<String> {
        val seen = HashSet<String>()
        for (table in tables) {
            val key = signature(table, vector)
            val probeKeys = ArrayList<Long>(table.hyperplanes.size + 1)
            probeKeys.add(key)
            for (b in table.hyperplanes.indices) probeKeys.add(key xor (1L shl b))
            for (probe in probeKeys) {
                val bucket = table.buckets[probe] ?: continue
                for (id in bucket) {
                    if (seen.size >= limit) return seen.toList()
                    seen.add(id)
                }
            }
        }
        return seen.toList()
    }

    companion object {
        const val DEFAULT_TABLES = 8
        const val DEFAULT_BITS = 16

        /** Deterministic index for a given seed (derived from graph version). */
        fun build(vectors: Map<String, FloatArray>, seed: Long): LshIndex {
            require(vectors.isNotEmpty()) { "LSH index needs at least one vector" }
            val dimensions = vectors.values.first().size
            val random = Random(seed)
            val tables = ArrayList<Table>(DEFAULT_TABLES)
            repeat(DEFAULT_TABLES) {
                val hyperplanes = Array(DEFAULT_BITS) {
                    FloatArray(dimensions) { random.nextFloat() - 0.5f }
                }
                val offsets = FloatArray(DEFAULT_BITS) { 0f }
                tables += Table(hyperplanes, offsets, HashMap())
            }
            val index = LshIndex(tables, dimensions)
            for ((id, vector) in vectors) index.insert(id, vector)
            return index
        }
    }
}
