package dev.abstra.pro.ai.playlist

import dev.abstra.pro.ai.audio.AudioSimilarity
import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.RecommendedTrack
import dev.abstra.pro.ai.model.MixPreset
import dev.abstra.pro.ai.recommendation.PlaylistRanker
import dev.abstra.pro.ai.SongProfileAnalyzer
import dev.abstra.pro.data.model.Song
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Maximal Marginal Relevance for candidate-pool diversity.
 * Balances relevance against similarity to already-selected candidates.
 */
object Mmr {

    /** Relevance/variance trade-off. Higher = more relevance. */
    const val DEFAULT_LAMBDA = 0.75f

    /**
     * Selects a diverse shortlist from scored candidates.
     * Deterministic: ties break by song id.
     */
    fun <T> select(
        candidates: List<T>,
        relevanceOf: (T) -> Float,
        idOf: (T) -> String,
        similarityOf: (T, T) -> Float,
        targetSize: Int,
        lambda: Float = DEFAULT_LAMBDA
    ): List<T> {
        if (candidates.size <= targetSize) return candidates.sortedByDescending(relevanceOf)
        val remaining = candidates.toMutableList()
        val selected = mutableListOf<T>()

        // Start with the most relevant candidate.
        remaining.sortWith(compareByDescending(relevanceOf).thenBy(idOf))
        selected += remaining.removeAt(0)

        while (selected.size < targetSize && remaining.isNotEmpty()) {
            var best: T? = null
            var bestValue = Float.MIN_VALUE
            var bestIndex = -1
            for ((index, candidate) in remaining.withIndex()) {
                var maxSim = 0f
                for (chosen in selected) {
                    val sim = similarityOf(candidate, chosen)
                    if (sim > maxSim) maxSim = sim
                }
                val value = lambda * relevanceOf(candidate) - (1 - lambda) * maxSim
                if (value > bestValue + 1e-9f) {
                    bestValue = value
                    best = candidate
                    bestIndex = index
                }
            }
            if (best == null || bestIndex < 0) break
            selected += remaining.removeAt(bestIndex)
        }
        return selected
    }
}

/**
 * Beam-search playlist sequencer (V2).
 *
 * Evaluates multiple possible sequences instead of picking a locally
 * attractive next song: every beam state tracks cumulative relevance,
 * transition quality, energy-curve fit and diversity, and the final path is
 * chosen by a normalized playlist-wide objective.
 *
 * Deterministic for identical inputs (library, profiles, history, preset,
 * refresh seed).
 */
object BeamSearchSequencer {

    const val BEAM_WIDTH = 12
    const val EXPANSIONS_PER_STATE = 18
    const val CANDIDATE_POOL_MIN = 80
    const val CANDIDATE_POOL_MAX = 120

    private const val W_RELEVANCE = 0.45f
    private const val W_TRANSITION = 0.35f
    private const val W_ENERGY = 0.20f

    private const val ARTIST_WINDOW = 5
    private const val ARTIST_WINDOW_MAX = 2
    private const val ALBUM_WINDOW = 3
    private const val ALBUM_WINDOW_MAX = 2

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    data class TransitionContext(
        val signatures: Map<String, AudioSignature>,
        val scaler: RobustScaler?,
        val transitionCounts: Map<String, Map<String, Int>> = emptyMap(),
        val outgoingTotals: Map<String, Int> = emptyMap()
    )

    data class Input(
        val candidates: List<PlaylistRanker.Scored>,
        val preset: MixPreset,
        val request: PlaylistRequest,
        val songs: Map<String, Song>,
        val transitionContext: TransitionContext,
        val recentlyPlayedIds: Set<String> = emptySet()
    )

    /** Compact beam state — counters and cumulative sums, no graph copies. */
    private class State(
        val ordered: List<PlaylistRanker.Scored>,
        val totalDurationMs: Long,
        val relevanceSum: Float,
        val transitionSum: Float,
        val energySum: Float,
        val diversitySum: Float,
        val discoveryCount: Int,
        val artistWindow: List<String>,
        val albumWindow: List<String>,
        val usedIds: Set<String>,
        val pathScore: Float
    )

    fun sequence(input: Input): List<RecommendedTrack> {
        val candidates = input.candidates
        if (candidates.isEmpty()) return emptyList()
        val byId = candidates.associateBy { it.input.song.id }

        // Normalized metadata keys for the MMR metadata fallback — computed
        // once per sequencing run instead of once per compared pair.
        val metadataKeys = candidates.associate {
            it.input.song.id to AudioSimilarity.MetadataKey.of(it.input.song)
        }

        // MMR-diversified shortlist within the bounded pool.
        val poolTarget = min(
            max(CANDIDATE_POOL_MIN, candidates.size / 2),
            CANDIDATE_POOL_MAX
        )
        val shortlist = Mmr.select(
            candidates = candidates,
            relevanceOf = { it.score },
            idOf = { it.input.song.id },
            similarityOf = { a, b ->
                val sigA = input.transitionContext.signatures[a.input.song.id]
                val sigB = input.transitionContext.signatures[b.input.song.id]
                if (sigA != null && sigB != null) {
                    AudioSimilarity.similarity(sigA, sigB, input.transitionContext.scaler) ?: 0.5f
                } else {
                    AudioSimilarity.metadataSimilarity(
                        metadataKeys.getValue(a.input.song.id),
                        metadataKeys.getValue(b.input.song.id)
                    )
                }
            },
            targetSize = poolTarget
        )

        // Exclusion of recently played (relaxed when the library is small).
        var eligible = shortlist
        if (input.request.excludeRecentlyPlayed && input.recentlyPlayedIds.isNotEmpty()) {
            val filtered = eligible.filter { it.input.song.id !in input.recentlyPlayedIds }
            if (filtered.size >= 10) eligible = filtered
        }
        val available = eligible.associateBy { it.input.song.id }.toMutableMap()

        val targetMs = input.request.targetDurationMs.coerceAtLeast(5L * 60L * 1000L)
        val targetCount = estimateTargetCount(eligible, targetMs)
        val random = Random(input.request.refreshSeed)

        fun isDiscovery(s: PlaylistRanker.Scored): Boolean = s.isDiscovery

        // Seed handling.
        val first: PlaylistRanker.Scored? = input.request.seedSongId
            ?.let { id -> available[id] }
            ?: eligible.maxByOrNull { it.score }
        if (first == null) return emptyList()
        available.remove(first.input.song.id)

        val initialStates = listOf(
            State(
                ordered = listOf(first),
                totalDurationMs = durationMs(first),
                relevanceSum = first.score,
                transitionSum = 0f,
                energySum = energyFit(first, input, 0f),
                diversitySum = 1f,
                discoveryCount = if (isDiscovery(first)) 1 else 0,
                artistWindow = listOf(artistKey(first)),
                albumWindow = listOf(albumKey(first)),
                usedIds = setOf(first.input.song.id),
                pathScore = first.score
            )
        )

        var beam = initialStates
        var bestComplete: State? = if (beam[0].totalDurationMs >= targetMs ||
            beam[0].ordered.size >= targetCount
        ) beam[0] else null

        while (beam.isNotEmpty()) {
            val nextBeam = mutableListOf<State>()
            for (state in beam) {
                if (state.totalDurationMs >= targetMs || state.ordered.size >= targetCount ||
                    state.ordered.size >= input.preset.maxTracks
                ) {
                    // Complete: keep for final comparison.
                    if (bestComplete == null || state.pathScore > bestComplete.pathScore + 1e-9f) {
                        bestComplete = state
                    }
                    continue
                }
                val progress = (state.totalDurationMs.toFloat() / targetMs).coerceIn(0f, 1f)
                val expansions = candidateExpansions(state, available, input)
                var expansionBudget = EXPANSIONS_PER_STATE
                for (candidate in expansions) {
                    if (expansionBudget <= 0) break
                    if (candidate.input.song.id in state.usedIds) continue
                    expansionBudget--
                    val transition = transitionScore(
                        previous = state.ordered.last(),
                        candidate = candidate,
                        progress = progress,
                        input = input
                    )
                    val energyFit = energyFit(candidate, input, progress)
                    val diversity = diversityScore(state, candidate)

                    val count = state.ordered.size + 1
                    val relevanceSum = state.relevanceSum + candidate.score
                    val transitionSum = state.transitionSum + transition
                    val energySum = state.energySum + energyFit
                    val diversitySum = state.diversitySum + diversity
                    val pathScore = pathScore(
                        relevanceSum, transitionSum, energySum, diversitySum, count
                    )
                    val newArtistWindow = (state.artistWindow + artistKey(candidate))
                        .takeLast(ARTIST_WINDOW)
                    val newAlbumWindow = (state.albumWindow + albumKey(candidate))
                        .takeLast(ALBUM_WINDOW)

                    nextBeam += State(
                        ordered = state.ordered + candidate,
                        totalDurationMs = state.totalDurationMs + durationMs(candidate),
                        relevanceSum = relevanceSum,
                        transitionSum = transitionSum,
                        energySum = energySum,
                        diversitySum = diversitySum,
                        discoveryCount = state.discoveryCount + if (isDiscovery(candidate)) 1 else 0,
                        artistWindow = newArtistWindow,
                        albumWindow = newAlbumWindow,
                        usedIds = state.usedIds + candidate.input.song.id,
                        pathScore = pathScore
                    )
                }
            }

            if (nextBeam.isEmpty()) break

            // Seeded tie-breaking variation only when scores are effectively equal.
            // Jitter must be fixed per state BEFORE sorting: a comparator that
            // draws from the RNG during comparison violates sort contracts.
            val jitter = HashMap<State, Float>(nextBeam.size)
            for (state in nextBeam) jitter[state] = jitterFor(state, random)
            val jittered = nextBeam.sortedWith(
                compareByDescending<State> { it.pathScore + (jitter[it] ?: 0f) }
                    .thenBy { it.ordered.last().input.song.id }
            )
            beam = jittered.take(BEAM_WIDTH)

            for (state in beam) {
                val complete = state.totalDurationMs >= targetMs || state.ordered.size >= targetCount
                if (complete &&
                    (bestComplete == null || state.pathScore > bestComplete.pathScore + 1e-9f)
                ) {
                    bestComplete = state
                }
            }

            // If every beam state is complete, stop.
            if (beam.all {
                    it.totalDurationMs >= targetMs || it.ordered.size >= targetCount ||
                        it.ordered.size >= input.preset.maxTracks
                }
            ) break
        }

        val finalState = bestComplete
            ?: beam.maxByOrNull { it.pathScore }
            ?: return emptyList()

        val transitionCtx = input.transitionContext
        val tracks = finalState.ordered.mapIndexed { index, scored ->
            val transition = if (index == 0) null else transitionScore(
                previous = finalState.ordered[index - 1],
                candidate = scored,
                progress = index.toFloat() / finalState.ordered.size.coerceAtLeast(1),
                input = input
            )
            RecommendedTrack(
                songId = scored.input.song.id,
                score = scored.score,
                reasons = buildReasons(scored, transition, input)
            )
        }
        return tracks
    }

    // ─────────────────────────────────────────────────────────────
    // Objective components
    // ─────────────────────────────────────────────────────────────

    private fun pathScore(
        relevanceSum: Float,
        transitionSum: Float,
        energySum: Float,
        diversitySum: Float,
        count: Int
    ): Float {
        if (count <= 0) return 0f
        val meanRelevance = relevanceSum / count
        val meanTransition = transitionSum / max(1, count - 1)
        val meanEnergy = energySum / count
        val meanDiversity = diversitySum / count
        return W_RELEVANCE * meanRelevance +
                W_TRANSITION * meanTransition +
                W_ENERGY * meanEnergy +
                0.15f * meanDiversity
    }

    private fun jitterFor(state: State, random: Random): Float {
        // Deterministic micro-jitter for tie-breaking; bounded to be noise-free.
        return random.nextFloat() * 0.005f
    }

    /**
     * Position-specific mood compatibility: re-evaluated at every step
     * against the preset's energy curve — never a static precomputed score.
     */
    private fun energyFit(
        candidate: PlaylistRanker.Scored,
        input: Input,
        progress: Float
    ): Float {
        val profile = candidate.input.profile
        val target = input.preset.energyAt(progress)
        if (profile.energyConfidence <= 0f) return 0.6f // neutral-ish, low information
        val distance = abs(profile.energy - target)
        val fit = 1f - min(1f, distance / max(input.preset.energyTolerance, 0.05f))
        return fit.coerceIn(0f, 1f)
    }

    private fun candidateExpansions(
        state: State,
        available: Map<String, PlaylistRanker.Scored>,
        input: Input
    ): List<PlaylistRanker.Scored> {
        val currentArtist = artistKey(state.ordered.last())
        val recentArtists = state.artistWindow.groupingBy { it }.eachCount()
        val recentAlbums = state.albumWindow.groupingBy { it }.eachCount()
        val currentAlbum = albumKey(state.ordered.last())

        val tiers = listOf(
            true to true,
            true to false,
            false to false
        )
        for ((enforceConsecutive, enforceWindows) in tiers) {
            val result = mutableListOf<PlaylistRanker.Scored>()
            for (candidate in available.values) {
                val candidateArtist = artistKey(candidate)
                val candidateAlbum = albumKey(candidate)
                if (enforceConsecutive && candidateArtist == currentArtist) continue
                if (enforceWindows) {
                    if ((recentArtists[candidateArtist] ?: 0) >= ARTIST_WINDOW_MAX) continue
                    if (candidateAlbum != currentAlbum &&
                        (recentAlbums[candidateAlbum] ?: 0) >= ALBUM_WINDOW_MAX
                    ) continue
                }
                result += candidate
                if (result.size >= EXPANSIONS_PER_STATE * 2) break
            }
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    /** Playlist-wide transition quality between two consecutive tracks. */
    fun transitionScore(
        previous: PlaylistRanker.Scored,
        candidate: PlaylistRanker.Scored,
        progress: Float,
        input: Input
    ): Float {
        val ctx = input.transitionContext
        val sigA = ctx.signatures[previous.input.song.id]
        val sigB = ctx.signatures[candidate.input.song.id]

        var value = 0f
        var weight = 0f

        if (sigA != null && sigB != null) {
            // Audio-aware transitions: timbre, chroma, tempo, rhythm, energy.
            val timbreFit = 1f - AudioSimilarity.mfccDistance(sigA, sigB)
            val chromaFit = 1f - AudioSimilarity.chromaDistance(sigA.chroma, sigB.chroma)
            val tempoFit = if (sigA.tempoConfidence >= 0.25f && sigB.tempoConfidence >= 0.25f) {
                1f - AudioSimilarity.tempoDistance(sigA.tempoBpm, sigB.tempoBpm)
            } else 0.6f
            val rhythmFit = 1f - abs(sigA.rhythmActivity - sigB.rhythmActivity).coerceIn(0f, 1f)
            val energyFit = 1f - abs(sigA.rmsEnergy - sigB.rmsEnergy).coerceIn(0f, 1f)

            value += 0.30f * timbreFit + 0.25f * chromaFit + 0.20f * tempoFit +
                    0.15f * rhythmFit + 0.10f * energyFit
            weight += 1f
        } else {
            // Profile fallback.
            val energyFit = 1f - min(
                1f,
                abs(previous.input.profile.energy - candidate.input.profile.energy) / 0.4f
            )
            val genreFit = SongProfileAnalyzer.genreCompatibility(
                previous.input.song.genre,
                candidate.input.song.genre
            )
            value += 0.6f * energyFit + 0.4f * genreFit
            weight += 1f
        }

        // Historical transitions: smoothed, supporting only.
        val count = ctx.transitionCounts[previous.input.song.id]?.get(candidate.input.song.id)
        if (count != null && count > 0) {
            val total = (ctx.outgoingTotals[previous.input.song.id] ?: 0).coerceAtLeast(1)
            val normalized = min(1f, count.toFloat() / total * 3f)
            value += 0.15f * normalized
            weight += 0.15f
        }

        return (value / weight).coerceIn(0f, 1f)
    }

    private fun diversityScore(
        state: State,
        candidate: PlaylistRanker.Scored
    ): Float {
        val recentArtists = state.artistWindow.groupingBy { it }.eachCount()
        val recentAlbums = state.albumWindow.groupingBy { it }.eachCount()
        val candidateArtist = artistKey(candidate)
        val candidateAlbum = albumKey(candidate)
        var diversity = 1f
        if (candidateArtist == artistKey(state.ordered.last())) diversity -= 0.5f
        if ((recentArtists[candidateArtist] ?: 0) >= 2) diversity -= 0.3f
        if (candidateAlbum == albumKey(state.ordered.last())) diversity -= 0.2f
        if ((recentAlbums[candidateAlbum] ?: 0) >= 2) diversity -= 0.15f
        return diversity.coerceIn(0f, 1f)
    }

    private fun buildReasons(
        scored: PlaylistRanker.Scored,
        transition: Float?,
        input: Input
    ): Set<dev.abstra.pro.ai.model.RecommendationReason> {
        val reasons = PlaylistRanker.toLegacyReasons(
            PlaylistRanker.diagnostics(scored)
        ).toMutableSet()
        if (transition != null && transition >= 0.7f) {
            reasons += dev.abstra.pro.ai.model.RecommendationReason.SMOOTH_TRANSITION
        }
        return reasons
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun durationMs(song: PlaylistRanker.Scored): Long =
        song.input.song.durationMs.coerceAtLeast(1000L)

    private fun artistKey(scored: PlaylistRanker.Scored): String =
        scored.input.song.artist.trim().lowercase()

    private fun albumKey(scored: PlaylistRanker.Scored): String =
        scored.input.song.album.trim().lowercase()

    private fun estimateTargetCount(
        shortlist: List<PlaylistRanker.Scored>,
        targetMs: Long
    ): Int {
        if (shortlist.isEmpty()) return 20
        val avg = shortlist.take(30)
            .map { durationMs(it) }
            .average().toFloat().coerceAtLeast(60_000f)
        return (targetMs / avg).roundToInt().coerceIn(6, 60)
    }
}
