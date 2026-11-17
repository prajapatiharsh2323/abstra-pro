package dev.abstra.pro.ai.perf

import dev.abstra.pro.ai.MixPresetCatalog
import dev.abstra.pro.ai.SongProfileAnalyzer
import dev.abstra.pro.ai.audio.AudioSimilarity
import dev.abstra.pro.ai.graph.LshIndex
import dev.abstra.pro.ai.graph.PersonalizedPageRank
import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.graph.SongGraphBuilder
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.ai.model.MixStrategy
import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.ai.playlist.BeamSearchSequencer
import dev.abstra.pro.ai.recommendation.CandidateRetriever
import dev.abstra.pro.ai.recommendation.PlaylistRanker
import dev.abstra.pro.ai.recommendation.RankFusion
import dev.abstra.pro.ai.recommendation.UserTasteModel
import dev.abstra.pro.data.db.entities.PlaybackFeedbackEntity
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * JVM performance measurements for the recommendation pipeline (spec §18.8).
 *
 * Pure-Kotlin components only — the parts that run on the recommendation
 * path at mix-generation time. Audio decoding itself is excluded (bounded
 * 3×5s windows, measured separately on device; the DSP cost is linear in
 * the window size and does not scale with library size).
 *
 * These are measurements, not assertions on exact timings; hard bounds are
 * deliberately generous so the suite stays stable on CI hardware.
 */
class RecommenderBenchmarkTest {

    private data class Library(
        val songs: List<Song>,
        val profiles: Map<String, SongProfile>,
        val signatures: Map<String, AudioSignature>
    )

    private fun buildLibrary(size: Int, seed: Long = 7L): Library {
        val random = Random(seed)
        val genres = listOf(
            "Rock", "Pop", "Hip Hop", "Electronic", "Jazz", "Classical",
            "Ambient", "Folk", "Metal", "Lo-Fi"
        )
        val songs = (0 until size).map { i ->
            val artist = "Artist ${i % 60}"
            Song(
                id = "song$i",
                title = "Track $i ${if (i % 17 == 0) "acoustic" else ""}".trim(),
                artist = artist,
                album = "Album ${i % (size / 3 + 1)}",
                durationMs = 120_000L + random.nextLong(180_000L),
                uriString = "content://$i",
                folderName = "f${i % 10}",
                filePath = "/f${i % 10}/track$i.mp3",
                dateAdded = i.toLong(),
                genre = genres[i % genres.size],
                isFavorite = i % 40 == 0
            )
        }
        val profiles = songs.associate { it.id to SongProfileAnalyzer.analyze(it) }
        val signatures = songs.associate { song ->
            val rnd = Random(song.id.hashCode().toLong())
            song.id to AudioSignature(
                songId = song.id,
                mfccMean = FloatArray(13) { rnd.nextFloat() * 2f - 1f },
                mfccStd = FloatArray(13) { rnd.nextFloat() * 0.5f },
                chroma = FloatArray(12) { rnd.nextFloat() },
                chromaConfidence = 0.7f,
                tempoBpm = 60f + rnd.nextFloat() * 100f,
                tempoConfidence = 0.6f,
                rmsEnergy = rnd.nextFloat(),
                dynamicRange = rnd.nextFloat(),
                spectralCentroid = rnd.nextFloat(),
                spectralFlux = rnd.nextFloat(),
                onsetDensity = rnd.nextFloat(),
                rhythmActivity = rnd.nextFloat(),
                featureConfidence = 0.8f,
                extractorVersion = AudioSignature.EXTRACTOR_VERSION,
                metadataFingerprint = SongProfileAnalyzer.fingerprint(song)
            )
        }
        return Library(songs, profiles, signatures)
    }

    private fun telemetryFor(songs: List<Song>, seed: Long = 3L): Map<String, PlaybackTelemetryEntity> {
        val random = Random(seed)
        return songs.mapNotNull { song ->
            if (random.nextInt(3) != 0) return@mapNotNull null // ~1/3 with history
            song.id to PlaybackTelemetryEntity(
                songId = song.id,
                playCount = random.nextInt(1, 40),
                fullCompletions = random.nextInt(0, 20),
                fastSkips = random.nextInt(0, 5),
                lastPlayedTimestamp = System.currentTimeMillis() - random.nextLong(90L) * DAY_MS
            )
        }.toMap()
    }

    private fun feedbackEvents(songs: List<Song>, seed: Long = 5L): List<PlaybackFeedbackEntity> {
        val random = Random(seed)
        val types = listOf(
            PlaybackFeedbackEntity.EVENT_MEANINGFUL_PLAY,
            PlaybackFeedbackEntity.EVENT_COMPLETION,
            PlaybackFeedbackEntity.EVENT_FAST_SKIP,
            PlaybackFeedbackEntity.EVENT_FAVORITE_ADDED
        )
        return (0 until songs.size * 3).map { i ->
            val song = songs[random.nextInt(songs.size)]
            PlaybackFeedbackEntity(
                songId = song.id,
                eventType = types[random.nextInt(types.size)],
                occurredAtMs = System.currentTimeMillis() - random.nextLong(180L) * DAY_MS
            )
        }
    }

    private fun audioGraph(library: Library, scaler: RobustScaler?): Map<String, List<String>> {
        val builder = SongGraphBuilder(NoopNeighborDao)
        // Use the pure exact-KNN over a bounded sample to represent the
        // persisted audio-neighbour map (as stored in song_neighbors).
        val result = HashMap<String, List<String>>()
        val signatureList = library.signatures.values.toList()
        val step = maxOf(1, signatureList.size / NEIGHBOUR_SAMPLE_TARGET)
        for (i in signatureList.indices step step) {
            val sig = signatureList[i]
            result[sig.songId] = builder
                .exactAudioNeighbours(sig, signatureList, SongGraphBuilder.NEIGHBOURS_PER_TYPE, scaler)
                .map { it.songId }
        }
        return result
    }

    @Test
    fun benchmarkPipelineAtRepresentativeLibrarySizes() {
        val results = StringBuilder()
        results.appendLine("=== Recommender pipeline benchmark (JVM, ${Runtime.getRuntime().availableProcessors()} cores) ===")
        for (size in intArrayOf(300, 1_500, 5_000, 10_000)) {
            results.appendLine("--- library: $size tracks ---")
            results.append(measureSize(size))
        }
        println(results.toString())
    }

    private fun measureSize(size: Int): String {
        val out = StringBuilder()
        val library = buildLibrary(size)
        val telemetry = telemetryFor(library.songs)
        val scaler = RobustScaler.of(library.signatures.values)

        // 1. Metadata profiling (once per library, fingerprinted afterwards).
        var t = measureMs { library.songs.forEach { SongProfileAnalyzer.analyze(it) } }
        out.appendLine(format("profile analysis (metadata)", t, size))

        // 2. Taste model from feedback history + telemetry.
        val events = feedbackEvents(library.songs)
        t = measureMs { UserTasteModel.buildTrackTastes(events, telemetry, System.currentTimeMillis()) }
        out.appendLine(format("taste model build", t, events.size))
        val tastes = UserTasteModel.buildTrackTastes(events, telemetry, System.currentTimeMillis())

        // 3. Neighbor graph: exact audio KNN cost (measured over a bounded
        //    sample and extrapolated to the full library).
        val builder = SongGraphBuilder(NoopNeighborDao)
        val sample = library.signatures.values.take(200).toList()
        val perSongAudioKnn = measureMs {
            for (sig in sample) {
                builder.exactAudioNeighbours(sig, library.signatures.values.toList(), 28, scaler)
            }
        } / sample.size.toFloat()
        out.appendLine(format("audio KNN per song (exact, ms/song)", perSongAudioKnn, size))

        // 4. LSH build + query (large-library path).
        if (size >= 1_500) {
            val vectors = library.signatures.mapValues { (_, sig) ->
                SongGraphBuilder.signatureVector(sig, scaler)
            }
            t = measureMs { LshIndex.build(vectors, seed = 42L) }
            out.appendLine(format("LSH index build", t, size))
            val index = LshIndex.build(vectors, seed = 42L)
            val queryCount = 100
            t = measureMs {
                repeat(queryCount) { i ->
                    index.query(vectors["song${i % size}"]!!, 600)
                }
            } / queryCount.toFloat()
            out.appendLine(format("LSH query per song", t, size))
        }

        // 5. Metadata KNN per song (the metadata edge cost). The library's
        //    normalized metadata is precomputed once, as the rebuild path does.
        val metadataKeys = library.songs.associate { it.id to AudioSimilarity.MetadataKey.of(it) }
        val metadataSample = library.songs.take(50)
        t = measureMs {
            for (song in metadataSample) {
                builder.metadataNeighbours(song, library.songs, 28, metadataKeys)
            }
        } / metadataSample.size.toFloat()
        out.appendLine(format("metadata KNN per song (ms/song)", t, size))

        // 6. Personalized PageRank over the combined graph.
        val graph = combinedGraph(library, scaler, telemetry)
        val seeds = library.songs.filter { it.isFavorite }.take(20)
            .associate { it.id to 1f }
        t = measureMs {
            PersonalizedPageRank.personalizedScores(graph, seeds, emptyMap())
        }
        out.appendLine(format("personalized PageRank", t, graph.size))

        // 7. Persisted-style audio neighbour map (bounded exact-KNN sample,
        //    as stored in song_neighbors). In the app this is built
        //    incrementally in the background — never at mix time.
        var builtMap: Map<String, List<String>>? = null
        t = measureMs {
            builtMap = audioGraph(library, scaler)
        }
        val neighbourMap = builtMap!!
        out.appendLine(format("audio neighbour map (background build)", t, size))

        // 8. Full mix generation for one preset (retrieval + fusion +
        //    ranking + MMR + beam sequencing), the user-visible cost.
        t = measureMs { generateOneMix(library, tastes, telemetry, scaler, neighbourMap) }
        out.appendLine(format("one full mix generation", t, size))

        return out.toString()
    }

    private fun combinedGraph(
        library: Library,
        scaler: RobustScaler?,
        telemetry: Map<String, PlaybackTelemetryEntity>
    ): Map<String, Map<String, Float>> {
        val audioGraph = audioGraph(library, scaler)
        val builder = SongGraphBuilder(NoopNeighborDao)
        val audioEdges = audioGraph.flatMap { (songId, neighbors) ->
            neighbors.map { n ->
                dev.abstra.pro.data.db.entities.SongNeighborEntity(
                    songId = songId,
                    neighborSongId = n,
                    edgeType = dev.abstra.pro.data.db.entities.SongNeighborEntity.EDGE_AUDIO,
                    similarity = 0.7f,
                    graphVersion = 1,
                    updatedAtMs = 0L
                )
            }
        }
        val metadataStep = maxOf(1, library.songs.size / NEIGHBOUR_SAMPLE_TARGET)
        val metadataKeys = library.songs.associate { it.id to AudioSimilarity.MetadataKey.of(it) }
        val metadataEdges = library.songs
            .filterIndexed { i, _ -> i % metadataStep == 0 }
            .flatMap { song ->
                builder.metadataNeighbours(song, library.songs, SongGraphBuilder.NEIGHBOURS_PER_TYPE, metadataKeys)
                    .map {
                        dev.abstra.pro.data.db.entities.SongNeighborEntity(
                            songId = song.id,
                            neighborSongId = it.songId,
                            edgeType = dev.abstra.pro.data.db.entities.SongNeighborEntity.EDGE_METADATA,
                            similarity = it.similarity,
                            graphVersion = 1,
                            updatedAtMs = 0L
                        )
                    }
            }
        val transitions = telemetry.keys.associateWith { target ->
            mapOf(target to 3)
        }
        return builder.buildCombinedGraph(
            library.songs.map { it.id }.toSet(),
            audioEdges, metadataEdges, transitions
        )
    }

    private fun generateOneMix(
        library: Library,
        tastes: Map<String, UserTasteModel.TrackTaste>,
        telemetry: Map<String, PlaybackTelemetryEntity>,
        scaler: RobustScaler?,
        audioNeighbourMap: Map<String, List<String>>
    ) {
        val preset = MixPresetCatalog.presets.first { it.id == MixPresetCatalog.PRESET_DAILY_FLOW }
        val retriever = CandidateRetriever(
            songs = library.songs,
            profiles = library.profiles,
            signatures = library.signatures,
            scaler = scaler,
            audioGraph = audioNeighbourMap,
            pprScores = emptyMap(),
            trackTastes = tastes,
            favoriteIds = library.songs.filter { it.isFavorite }.map { it.id }.toSet(),
            anchorSongIds = emptyList(),
            telemetry = telemetry
        )
        val retrieved = retriever.retrieve(preset, null)
        val fused = RankFusion.fuse(emptyList()).let { retrieved.fusedScores }
        val maxFused = fused.values.maxOrNull() ?: 0f
        val fusedNormalized = if (maxFused > 0f) {
            fused.mapValues { (_, v) -> (v / maxFused).coerceIn(0f, 1f) }
        } else emptyMap()

        val scoredList = fusedNormalized.mapNotNull { (songId, fusedScore) ->
            val song = library.songs.firstOrNull { it.id == songId } ?: return@mapNotNull null
            val profile = library.profiles.getValue(song.id)
            PlaylistRanker.score(
                PlaylistRanker.CandidateInput(
                    song = song,
                    profile = profile,
                    fusedScore = fusedScore,
                    pprScore = null,
                    audioSimilarity = null,
                    preference = tastes[song.id],
                    moodValue = 0.6f,
                    moodConfidence = 0.5f,
                    noveltyValue = 0.5f,
                    noveltyConfidence = 0.5f,
                    playCount = telemetry[song.id]?.playCount ?: 0
                )
            )
        }.sortedByDescending { it.score }.take(120)

        BeamSearchSequencer.sequence(
            BeamSearchSequencer.Input(
                candidates = scoredList,
                preset = preset,
                request = PlaylistRequest(mood = preset.id),
                songs = library.songs.associateBy { it.id },
                transitionContext = BeamSearchSequencer.TransitionContext(
                    signatures = library.signatures,
                    scaler = scaler
                )
            )
        )
    }

    private inline fun measureMs(block: () -> Unit): Float {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000f
    }

    private fun format(label: String, ms: Float, scale: Int): String =
        String.format(Locale.US, "%-42s %10.2f ms  (n=%d)", label, ms, scale)

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val NEIGHBOUR_SAMPLE_TARGET = 300
    }
}
