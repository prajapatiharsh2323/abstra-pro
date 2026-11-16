package dev.abstra.pro.ai

import android.content.Context
import dev.abstra.pro.ai.audio.AudioFeatureExtractor
import dev.abstra.pro.ai.audio.AudioSimilarity
import dev.abstra.pro.ai.graph.PersonalizedPageRank
import dev.abstra.pro.ai.graph.RobustScaler
import dev.abstra.pro.ai.graph.SongGraphBuilder
import dev.abstra.pro.ai.model.AudioSignature
import dev.abstra.pro.ai.model.DayType
import dev.abstra.pro.ai.model.GeneratedPlaylist
import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.RecommendationReason
import dev.abstra.pro.ai.model.RecommendedTrack
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.ai.model.TimeOfDay
import dev.abstra.pro.ai.playlist.BeamSearchSequencer
import dev.abstra.pro.ai.recommendation.CandidateRetriever
import dev.abstra.pro.ai.recommendation.PlaylistRanker
import dev.abstra.pro.ai.recommendation.UserTasteModel
import dev.abstra.pro.data.db.AppDatabase
import dev.abstra.pro.data.db.entities.MarkovTransitionEntity
import dev.abstra.pro.data.db.entities.PlaybackFeedbackEntity
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.db.entities.SongNeighborEntity
import dev.abstra.pro.data.db.entities.toDomain
import dev.abstra.pro.data.db.entities.toEntity
import dev.abstra.pro.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * On-device, fully offline music intelligence engine (V2).
 *
 * Pipeline:
 *
 *   library + overrides + profiles
 *       -> bounded audio analysis (optional) -> AudioSignature
 *       -> sparse SongGraph (audio + metadata edges, persisted)
 *       -> UserTasteModel (feedback history + time decay)
 *       -> PersonalizedPageRank over the combined graph
 *       -> CandidateRetriever (audio neighbours / PPR / mood / discovery)
 *       -> Reciprocal Rank Fusion
 *       -> PlaylistRanker (confidence-aware final scores)
 *       -> MMR + BeamSearchSequencer
 *       -> GeneratedPlaylist (ordered song ids + scores + reasons)
 *
 * The engine never controls playback and never touches Compose types.
 * Metadata analysis is the foundation; playback behaviour personalizes;
 * audio analysis is an optional refinement. Everything degrades
 * gracefully: no signatures means metadata graph only; no history means
 * cold-start behaviour, never fabricated preference.
 */
class AbstraAiEngine(
    context: Context,
    private val db: AppDatabase
) {
    private val appContext = context.applicationContext
    private val telemetryDao = db.telemetryDao()
    private val profileDao = db.songProfileDao()
    private val signatureDao = db.audioSignatureDao()
    private val neighborDao = db.songNeighborDao()
    private val feedbackDao = db.playbackFeedbackDao()
    private val prefs = appContext.getSharedPreferences("abstra_prefs", Context.MODE_PRIVATE)

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mixCache = MixCache(appContext)
    private val audioExtractor = AudioFeatureExtractor(appContext)
    private val graphBuilder = SongGraphBuilder(neighborDao)

    private val _mixes = MutableStateFlow<List<GeneratedPlaylist>>(emptyList())
    val mixes: StateFlow<List<GeneratedPlaylist>> = _mixes.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    /** Lightweight audio-analysis progress for optional UI display. */
    data class AudioAnalysisProgress(val queued: Int, val analyzedCount: Int)

    private val _audioAnalysisProgress =
        MutableStateFlow(AudioAnalysisProgress(0, 0))
    val audioAnalysisProgress: StateFlow<AudioAnalysisProgress> =
        _audioAnalysisProgress.asStateFlow()

    // ── Shared state (guarded) ──
    private val telemetryCache = ConcurrentHashMap<String, PlaybackTelemetryEntity>()
    private val profileCache = ConcurrentHashMap<String, SongProfile>()
    private val signatureCache = ConcurrentHashMap<String, AudioSignature>()
    private val telemetryMutex = Mutex()
    private val profileMutex = Mutex()
    private val mixMutex = Mutex()
    private val graphMutex = Mutex()
    private val tasteMutex = Mutex()

    @Volatile
    private var telemetryLoaded = false

    @Volatile
    private var profilesLoaded = false

    @Volatile
    private var signaturesLoaded = false

    @Volatile
    private var scaler: RobustScaler? = null

    @Volatile
    private var combinedGraph: Map<String, Map<String, Float>> = emptyMap()

    @Volatile
    private var audioNeighbourMap: Map<String, List<String>> = emptyMap()

    @Volatile
    private var graphDirty = true

    /** Songs whose metadata edges need an incremental refresh. */
    private val pendingMetadataRefresh = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var tasteCache: Map<String, UserTasteModel.TrackTaste> = emptyMap()

    @Volatile
    private var tasteCacheRevision: Long = -1L

    @Volatile
    private var closed = false

    /** Last library seen by generateDailyMixes — enables internal refreshes. */
    @Volatile
    private var lastSongs: List<Song>? = null

    @Volatile
    private var pendingTasteEvents = 0

    // ── Playback session tracking (meaningful plays, real listened time) ──
    private class PlaybackSession(
        val songId: String,
        val durationMs: Long,
        val startedAtMs: Long,
        var lastPositionMs: Long,
        var listenedMs: Long,
        var persistedListenedMs: Long,
        var meaningfulCounted: Boolean
    )

    private var session: PlaybackSession? = null
    private var lastMeaningfulSongId: String? = null
    private var lastMeaningfulTimeMs: Long = 0L

    // ── Optional audio intelligence ──
    @Volatile
    private var audioEnabled = false

    @Volatile
    private var playbackActive = false
    private var audioJob: Job? = null
    private val audioQueue = ConcurrentSkipListSet<AudioTask>(compareBy({ it.priority }, { it.song.id }))
    private val audioQueuedIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private class AudioTask(val song: Song, val priority: Int)

    init {
        audioEnabled = prefs.getBoolean(PREF_AUDIO_INTELLIGENCE, false)
    }

    // ═════════════════════════════════════════════════════════════
    // 1. TELEMETRY — meaningful listening, not just pressing Play
    // ═════════════════════════════════════════════════════════════

    fun onSongStarted(song: Song) {
        if (closed) return
        // Finalize any dangling session defensively (e.g. manual replay).
        session?.let { s -> engineScope.launch { applySessionEnd(s, completed = false, treatAsSkip = true) } }
        session = PlaybackSession(
            songId = song.id,
            durationMs = song.durationMs,
            startedAtMs = System.currentTimeMillis(),
            lastPositionMs = 0L,
            listenedMs = 0L,
            persistedListenedMs = 0L,
            meaningfulCounted = false
        )

        // Markov transition recorded only after a meaningful previous session.
        val prev = lastMeaningfulSongId
        if (prev != null && prev != song.id) {
            engineScope.launch { recordTransition(prev, song.id) }
        }
    }

    fun onSongProgress(song: Song, positionMs: Long, durationMs: Long) {
        if (closed) return
        val s = session ?: return
        if (s.songId != song.id) return
        val delta = positionMs - s.lastPositionMs
        // Progress ticks are ~5s apart; larger jumps are seeks and do not
        // count as listening. A normal seek is never a dislike.
        if (delta in 1..PROGRESS_TICK_MAX_MS) s.listenedMs += delta
        s.lastPositionMs = positionMs
        if (!s.meaningfulCounted && s.listenedMs >= meaningfulThresholdMs(s.durationMs)) {
            engineScope.launch { countMeaningfulPlay(s) }
        }
    }

    fun onSongCompleted(song: Song) {
        if (closed) return
        val s = session
        if (s != null && s.songId == song.id) {
            // Reaching the end counts the remaining part as listened time.
            s.listenedMs += (s.durationMs - s.lastPositionMs).coerceAtLeast(0L)
            engineScope.launch {
                applySessionEnd(s, completed = true, treatAsSkip = false)
            }
        } else {
            engineScope.launch {
                updateTelemetry(song.id) { cur ->
                    cur.copy(
                        fullCompletions = cur.fullCompletions + 1,
                        consecutiveSkips = 0
                    )
                }
                recordFeedback(song.id, PlaybackFeedbackEntity.EVENT_COMPLETION)
            }
        }
        session = null
    }

    fun onSongSkipped(song: Song, playedSeconds: Long, totalDurationSeconds: Long) {
        if (closed) return
        val s = session
        engineScope.launch {
            if (s != null && s.songId == song.id) {
                applySessionEnd(s, completed = false, treatAsSkip = true)
            } else {
                // No live session (callbacks restarted); approximate from the
                // reported played time so fast skips still register.
                val approx = PlaybackSession(
                    songId = song.id,
                    durationMs = totalDurationSeconds * 1000L,
                    startedAtMs = 0L,
                    lastPositionMs = playedSeconds * 1000L,
                    listenedMs = playedSeconds * 1000L,
                    persistedListenedMs = 0L,
                    meaningfulCounted = false
                )
                applySessionEnd(approx, completed = false, treatAsSkip = true)
            }
        }
        session = null
    }

    fun onPlaybackPaused(song: Song) {
        if (closed) return
        val s = session ?: return
        if (s.songId != song.id) return
        engineScope.launch {
            if (!s.meaningfulCounted && s.listenedMs >= meaningfulThresholdMs(s.durationMs)) {
                countMeaningfulPlay(s)
            } else {
                persistListenedDelta(s)
            }
        }
    }

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    /** Favorites are strong immediate signals — recorded and reflected at once. */
    fun onFavoriteToggled(song: Song, isFavorite: Boolean) {
        if (closed) return
        engineScope.launch {
            recordFeedback(
                song.id,
                if (isFavorite) {
                    PlaybackFeedbackEntity.EVENT_FAVORITE_ADDED
                } else {
                    PlaybackFeedbackEntity.EVENT_FAVORITE_REMOVED
                },
                bumpTasteRevision = true
            )
        }
    }

    /** Adding to a user playlist is positive evidence about the track. */
    fun onAddedToUserPlaylist(songIds: List<String>) {
        if (closed) return
        engineScope.launch {
            for (id in songIds.distinct()) {
                recordFeedback(
                    id,
                    PlaybackFeedbackEntity.EVENT_ADDED_TO_USER_PLAYLIST,
                    bumpTasteRevision = true
                )
            }
        }
    }

    private fun meaningfulThresholdMs(durationMs: Long): Long {
        // Short tracks: 20% of duration (at least 8s). Everything else: 30s.
        return if (durationMs in 1..180_000) {
            (durationMs * 0.2f).toLong().coerceAtLeast(8_000L)
        } else {
            30_000L
        }
    }

    private fun isFastSkip(s: PlaybackSession): Boolean {
        val ratio = if (s.durationMs > 0) s.listenedMs.toFloat() / s.durationMs else 0f
        return s.listenedMs <= FAST_SKIP_MS || ratio < FAST_SKIP_RATIO
    }

    private suspend fun countMeaningfulPlay(s: PlaybackSession) {
        if (s.meaningfulCounted) return
        val now = System.currentTimeMillis()
        val isReplay = lastMeaningfulSongId == s.songId &&
                (now - lastMeaningfulTimeMs) < REPLAY_WINDOW_MS
        val listenedDelta = (s.listenedMs - s.persistedListenedMs).coerceAtLeast(0L)
        val timeOfDay = TimeOfDay.current()
        val isWeekend = DayType.current() == DayType.WEEKEND
        updateTelemetry(s.songId) { cur ->
            cur.copy(
                playCount = cur.playCount + 1,
                repeatCount = if (isReplay) cur.repeatCount + 1 else cur.repeatCount,
                lastPlayedTimestamp = now,
                totalPlayedMs = cur.totalPlayedMs + listenedDelta,
                consecutiveSkips = 0,
                morningPlays = if (timeOfDay == TimeOfDay.MORNING) cur.morningPlays + 1 else cur.morningPlays,
                afternoonPlays = if (timeOfDay == TimeOfDay.AFTERNOON) cur.afternoonPlays + 1 else cur.afternoonPlays,
                eveningPlays = if (timeOfDay == TimeOfDay.EVENING) cur.eveningPlays + 1 else cur.eveningPlays,
                nightPlays = if (timeOfDay == TimeOfDay.NIGHT) cur.nightPlays + 1 else cur.nightPlays,
                weekdayPlays = if (!isWeekend) cur.weekdayPlays + 1 else cur.weekdayPlays,
                weekendPlays = if (isWeekend) cur.weekendPlays + 1 else cur.weekendPlays
            )
        }
        s.persistedListenedMs = s.listenedMs
        s.meaningfulCounted = true
        lastMeaningfulSongId = s.songId
        lastMeaningfulTimeMs = now

        recordFeedback(
            s.songId,
            if (isReplay) {
                PlaybackFeedbackEntity.EVENT_REPLAY
            } else {
                PlaybackFeedbackEntity.EVENT_MEANINGFUL_PLAY
            },
            listenedMs = s.listenedMs,
            durationMs = s.durationMs
        )
    }

    private suspend fun persistListenedDelta(s: PlaybackSession) {
        val delta = (s.listenedMs - s.persistedListenedMs).coerceAtLeast(0L)
        if (delta <= 0L) return
        updateTelemetry(s.songId) { cur ->
            cur.copy(totalPlayedMs = cur.totalPlayedMs + delta)
        }
        s.persistedListenedMs = s.listenedMs
    }

    private suspend fun applySessionEnd(
        s: PlaybackSession,
        completed: Boolean,
        treatAsSkip: Boolean
    ) {
        val threshold = meaningfulThresholdMs(s.durationMs)
        val meaningful = completed || s.listenedMs >= threshold

        if (meaningful && !s.meaningfulCounted) {
            countMeaningfulPlay(s)
        } else {
            persistListenedDelta(s)
        }

        // A normal manual Next after meaningful listening is NOT a dislike.
        if (!meaningful && treatAsSkip && isFastSkip(s)) {
            updateTelemetry(s.songId) { cur ->
                cur.copy(
                    fastSkips = cur.fastSkips + 1,
                    consecutiveSkips = cur.consecutiveSkips + 1
                )
            }
            recordFeedback(
                s.songId,
                PlaybackFeedbackEntity.EVENT_FAST_SKIP,
                listenedMs = s.listenedMs,
                durationMs = s.durationMs
            )
        }

        if (completed) {
            updateTelemetry(s.songId) { cur ->
                cur.copy(fullCompletions = cur.fullCompletions + 1, consecutiveSkips = 0)
            }
            recordFeedback(
                s.songId,
                PlaybackFeedbackEntity.EVENT_COMPLETION,
                listenedMs = s.listenedMs,
                durationMs = s.durationMs,
                bumpTasteRevision = false
            )
        }
    }

    private suspend fun updateTelemetry(
        songId: String,
        transform: (PlaybackTelemetryEntity) -> PlaybackTelemetryEntity
    ) {
        telemetryMutex.withLock {
            val current = telemetryCache[songId]
                ?: telemetryDao.getTelemetry(songId)
                ?: PlaybackTelemetryEntity(songId = songId)
            val updated = transform(current)
            telemetryCache[songId] = updated
            runCatching { telemetryDao.saveTelemetry(updated) }
        }
    }

    private suspend fun recordTransition(fromSongId: String, toSongId: String) {
        telemetryMutex.withLock {
            runCatching {
                val existing = telemetryDao.getTransition(fromSongId, toSongId)
                val newCount = (existing?.transitionCount ?: 0) + 1
                telemetryDao.recordTransition(
                    MarkovTransitionEntity(fromSongId, toSongId, newCount)
                )
            }
        }
    }

    /**
     * Records one bounded feedback event. Retention: rolling 180-day window
     * with a hard cap on stored events. Taste revision is bumped only for
     * immediate (favorite / playlist) signals or in batches.
     */
    private suspend fun recordFeedback(
        songId: String,
        eventType: String,
        listenedMs: Long = 0L,
        durationMs: Long = 0L,
        bumpTasteRevision: Boolean = false
    ) {
        runCatching {
            feedbackDao.insert(
                PlaybackFeedbackEntity(
                    songId = songId,
                    eventType = eventType,
                    occurredAtMs = System.currentTimeMillis(),
                    listenedMs = listenedMs,
                    durationMs = durationMs
                )
            )
            val count = feedbackDao.countAll()
            if (count % FEEDBACK_PRUNE_INTERVAL == 0) {
                feedbackDao.deleteOlderThan(System.currentTimeMillis() - FEEDBACK_RETENTION_MS)
                feedbackDao.keepNewest(FEEDBACK_MAX_EVENTS)
            }
        }
        synchronized(this) {
            pendingTasteEvents++
            val shouldBump = bumpTasteRevision || pendingTasteEvents >= TASTE_BATCH_EVENTS
            if (shouldBump) {
                pendingTasteEvents = 0
                val revision = prefs.getLong(PREF_TASTE_REVISION, 0L) + 1
                prefs.edit().putLong(PREF_TASTE_REVISION, revision).apply()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 2. SONG PROFILES — analyze once, cache, invalidate on change
    // ═════════════════════════════════════════════════════════════

    private suspend fun ensureProfiles(songs: List<Song>): Map<String, SongProfile> {
        if (!profilesLoaded) {
            profileMutex.withLock {
                if (!profilesLoaded) {
                    runCatching {
                        profileDao.getAllSync().forEach { profileCache[it.songId] = it.toDomain() }
                    }
                    profilesLoaded = true
                }
            }
        }

        val now = System.currentTimeMillis()
        val changed = mutableListOf<SongProfile>()
        val result = HashMap<String, SongProfile>(songs.size * 2)
        for (song in songs) {
            val existing = profileCache[song.id]
            val fingerprint = SongProfileAnalyzer.fingerprint(song)
            val profile: SongProfile = if (existing != null &&
                existing.metadataFingerprint == fingerprint &&
                existing.analysisVersion == SongProfileAnalyzer.ANALYSIS_VERSION
            ) {
                existing
            } else {
                val analyzed = SongProfileAnalyzer.analyze(song)
                profileCache[song.id] = analyzed
                changed += analyzed
                analyzed
            }
            result[song.id] = profile
        }

        if (changed.isNotEmpty()) {
            profileMutex.withLock {
                changed.chunked(PROFILE_BATCH_SIZE).forEach { chunk ->
                    runCatching {
                        profileDao.upsertAll(chunk.map { it.toEntity(now) })
                    }
                }
            }
        }
        return result
    }

    /** Metadata (or override) changed → the cached profile is stale. */
    fun onSongMetadataChanged(songId: String) {
        profileCache.remove(songId)
        pendingMetadataRefresh.add(songId)
        engineScope.launch {
            runCatching {
                profileDao.deleteProfile(songId)
                // The audio signature describes the file, which did not change,
                // but metadata graph edges must be rebuilt.
                graphDirty = true
            }
        }
    }

    fun onSongsDeleted(songIds: List<String>) {
        songIds.forEach { profileCache.remove(it); signatureCache.remove(it) }
        engineScope.launch {
            runCatching {
                graphBuilder.removeSongs(songIds)
                songIds.forEach { id ->
                    profileDao.deleteProfile(id)
                    signatureDao.deleteSignature(id)
                    feedbackDao.deleteEventsForSong(id)
                }
                graphDirty = true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 3. AUDIO SIGNATURES — load, persist, keep the graph fresh
    // ═════════════════════════════════════════════════════════════

    private suspend fun ensureSignaturesLoaded(songs: List<Song>) {
        if (signaturesLoaded) return
        signatureCache.clear()
        runCatching {
            signatureDao.getAllSync().forEach { entity ->
                val signature = entity.toDomain()
                if (signature.isValid()) signatureCache[signature.songId] = signature
            }
        }
        signaturesLoaded = true
        rebuildScaler()
    }

    private fun rebuildScaler() {
        scaler = RobustScaler.of(signatureCache.values)
    }

    /** Persists one fresh signature and updates the affected graph part. */
    private suspend fun onSignatureExtracted(song: Song, fingerprint: String, signature: AudioSignature) {
        val stamped = AudioSignature(
            songId = signature.songId,
            mfccMean = signature.mfccMean,
            mfccStd = signature.mfccStd,
            chroma = signature.chroma,
            chromaConfidence = signature.chromaConfidence,
            tempoBpm = signature.tempoBpm,
            tempoConfidence = signature.tempoConfidence,
            rmsEnergy = signature.rmsEnergy,
            dynamicRange = signature.dynamicRange,
            spectralCentroid = signature.spectralCentroid,
            spectralFlux = signature.spectralFlux,
            onsetDensity = signature.onsetDensity,
            rhythmActivity = signature.rhythmActivity,
            featureConfidence = signature.featureConfidence,
            extractorVersion = signature.extractorVersion,
            metadataFingerprint = fingerprint,
            analysisStatus = signature.analysisStatus,
            analyzedAtMs = signature.analyzedAtMs
        )
        runCatching {
            signatureDao.upsert(stamped.toEntity())
        }
        signatureCache[song.id] = stamped
        rebuildScaler()

        // Update this song's audio neighbourhood incrementally (bounded).
        runCatching {
            val allSongs = lastSongs ?: emptyList()
            if (allSongs.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val version = currentGraphVersion()
                graphBuilder.refreshAudioNeighbours(
                    song, stamped, allSongs, signatureCache, scaler, version, now
                )
            }
        }
        graphDirty = true
    }

    private fun currentGraphVersion(): Int = AudioSignature.EXTRACTOR_VERSION

    // ═════════════════════════════════════════════════════════════
    // 4. GRAPH — build once, refresh incrementally
    // ═════════════════════════════════════════════════════════════

    private suspend fun ensureGraphBuilt(songs: List<Song>) {
        if (!graphDirty && combinedGraph.isNotEmpty()) return
        graphMutex.withLock {
            if (!graphDirty && combinedGraph.isNotEmpty()) return

            val libraryFp = libraryFingerprint(songs)
            val storedVersion = prefs.getInt(PREF_GRAPH_VERSION, -1)
            val storedFp = prefs.getString(PREF_GRAPH_LIBRARY_FP, null)
            val version = currentGraphVersion()
            val needsFullRebuild = storedVersion != version || storedFp != libraryFp ||
                    runCatching { neighborDao.countAll() }.getOrDefault(0) == 0

            val songById = songs.associateBy { it.id }
            val validIds = songs.map { it.id }.toSet()

            if (needsFullRebuild && songs.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    graphBuilder.rebuildGraph(
                        songs = songs,
                        signaturesById = signatureCache.filterKeys { it in validIds },
                        graphVersion = version,
                        nowMs = System.currentTimeMillis()
                    )
                }
                prefs.edit()
                    .putInt(PREF_GRAPH_VERSION, version)
                    .putString(PREF_GRAPH_LIBRARY_FP, libraryFp)
                    .apply()
            } else if (songs.isNotEmpty()) {
                // Incremental: refresh metadata neighbourhoods only for songs
                // whose effective metadata changed (bounded batch). Audio
                // neighbourhoods are maintained by the audio worker.
                val toRefresh = pendingMetadataRefresh
                    .filter { it in validIds }
                    .take(METADATA_REFRESH_BATCH)
                toRefresh.chunked(GRAPH_BATCH_SIZE).forEach { batch ->
                    withContext(Dispatchers.Default) {
                        for (id in batch) {
                            val song = songById[id] ?: continue
                            graphBuilder.refreshMetadataNeighbours(
                                song, songs, version, System.currentTimeMillis()
                            )
                        }
                    }
                }
                if (toRefresh.size >= pendingMetadataRefresh.size) {
                    pendingMetadataRefresh.clear()
                } else {
                    pendingMetadataRefresh.removeAll(toRefresh.toSet())
                }
            }

            // Load edges and build the combined graph + audio neighbour map.
            val edges = runCatching { neighborDao.getAllSync() }.getOrDefault(emptyList())
                .filter { it.songId in validIds && it.neighborSongId in validIds && it.songId != it.neighborSongId }
            val audioEdges = edges.filter { it.edgeType == SongNeighborEntity.EDGE_AUDIO }
            val metadataEdges = edges.filter { it.edgeType == SongNeighborEntity.EDGE_METADATA }

            audioNeighbourMap = audioEdges
                .groupBy { it.songId }
                .mapValues { (_, list) ->
                    list.sortedByDescending { it.similarity }.map { it.neighborSongId }
                }

            val transitions = loadTransitions().first
            combinedGraph = graphBuilder.buildCombinedGraph(
                validIds, audioEdges, metadataEdges, transitions
            )
            graphDirty = false
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 5. DAILY MIX GENERATION (V2)
    // ═════════════════════════════════════════════════════════════

    fun generateDailyMixes(allSongs: List<Song>, forceRefresh: Boolean = false) {
        if (allSongs.isEmpty() || closed) return
        lastSongs = allSongs
        engineScope.launch {
            mixMutex.withLock {
                _isGenerating.value = true
                try {
                    _mixes.value = generateMixesInternal(allSongs, forceRefresh)
                } finally {
                    _isGenerating.value = false
                }
            }
        }
    }

    private suspend fun generateMixesInternal(
        allSongs: List<Song>,
        forceRefresh: Boolean
    ): List<GeneratedPlaylist> {
        ensureTelemetryLoaded()
        val profiles = ensureProfiles(allSongs)
        ensureSignaturesLoaded(allSongs)

        val songById = allSongs.associateBy { it.id }
        val validIds = allSongs.map { it.id }.toSet()

        // Graph (persisted, incremental).
        ensureGraphBuilt(allSongs)

        // Taste model.
        val tasteRevision = prefs.getLong(PREF_TASTE_REVISION, 0L)
        val trackTastes = ensureTrackTastes(tasteRevision)
        val artistTastes = UserTasteModel.buildAggregates(allSongs, trackTastes) { song ->
            listOf(AffinityModel.normalizeArtistKey(song)).filter { it.isNotBlank() }
        }
        val genreTastes = UserTasteModel.buildAggregates(allSongs, trackTastes) { song ->
            AffinityModel.normalizeGenres(song.genre)
        }

        // Personalized PageRank over the combined graph.
        val positiveSeeds = buildPositiveSeeds(allSongs, trackTastes)
        val negativeSeeds = UserTasteModel.buildNegativeSeeds(trackTastes)
        val pprScores = if (positiveSeeds.isNotEmpty()) {
            PersonalizedPageRank.personalizedScores(combinedGraph, positiveSeeds, negativeSeeds)
        } else emptyMap()

        val favoriteIds = allSongs.filter { it.isFavorite }.map { it.id }.toSet()
        val recentlyPlayed = readRecentlyPlayedIds()

        // Daily cache: reuse while every fingerprint matches.
        val fingerprint = libraryFingerprint(allSongs)
        val metadataFp = metadataFingerprint(allSongs, profiles)
        val dateKey = todayKey()
        val cache = mixCache.load()

        if (!forceRefresh && cache != null &&
            cache.dateKey == dateKey &&
            cache.libraryFingerprint == fingerprint &&
            cache.metadataFingerprint == metadataFp &&
            cache.graphVersion == currentGraphVersion() &&
            cache.algorithmVersion == ALGORITHM_VERSION &&
            cache.presetVersion == PRESET_VERSION &&
            cache.tasteRevision == tasteRevision
        ) {
            val restored = restoreFromCache(cache, songById)
            if (restored.isNotEmpty()) {
                enqueueLibraryAnalysis(allSongs)
                return restored
            }
        }

        // Seed: forced refresh bumps; otherwise date + library derived.
        val previousBaseSeed = cache?.takeIf {
            !forceRefresh || it.libraryFingerprint == fingerprint
        }?.baseSeed ?: 0L
        val baseSeed = if (forceRefresh) previousBaseSeed + 1 else stableSeed(dateKey, fingerprint)

        val generated = mutableListOf<GeneratedPlaylist>()
        for (preset in MixPresetCatalog.presets) {
            val request = PlaylistRequest(
                mood = preset.id,
                targetDurationMs = preset.targetDurationMs,
                discoveryRatio = preset.discoveryRatio,
                excludeRecentlyPlayed = preset.excludeRecentlyPlayed,
                seedSongId = null,
                refreshSeed = MixPresetCatalog.seedFor(preset, baseSeed)
            )
            val playlist = computePlaylist(
                allSongs = allSongs,
                profiles = profiles,
                preset = preset,
                request = request,
                trackTastes = trackTastes,
                artistTastes = artistTastes,
                genreTastes = genreTastes,
                pprScores = pprScores,
                favoriteIds = favoriteIds,
                recentlyPlayed = recentlyPlayed
            ) ?: continue
            generated += playlist
        }

        if (generated.isNotEmpty()) {
            mixCache.save(
                MixCacheData(
                    dateKey = dateKey,
                    libraryFingerprint = fingerprint,
                    metadataFingerprint = metadataFp,
                    graphVersion = currentGraphVersion(),
                    algorithmVersion = ALGORITHM_VERSION,
                    presetVersion = PRESET_VERSION,
                    tasteRevision = tasteRevision,
                    baseSeed = baseSeed,
                    playlists = generated.map { gp ->
                        CachedPlaylist(
                            presetId = gp.presetId,
                            songIds = gp.tracks.map { it.songId },
                            reasons = gp.tracks.associate { track ->
                                track.songId to track.reasons.map { it.name }
                            },
                            generatedAtMs = gp.generatedAtMs,
                            refreshSeed = gp.refreshSeed,
                            totalDurationMs = gp.totalDurationMs
                        )
                    }
                )
            )
        }
        enqueueLibraryAnalysis(allSongs)
        return generated
    }

    /** Generates a single playlist for an explicit request. */
    suspend fun generatePlaylist(allSongs: List<Song>, request: PlaylistRequest): GeneratedPlaylist? =
        withContext(Dispatchers.Default) {
            if (allSongs.isEmpty() || closed) return@withContext null
            mixMutex.withLock {
                ensureTelemetryLoaded()
                val profiles = ensureProfiles(allSongs)
                ensureSignaturesLoaded(allSongs)
                ensureGraphBuilt(allSongs)

                val tasteRevision = prefs.getLong(PREF_TASTE_REVISION, 0L)
                val trackTastes = ensureTrackTastes(tasteRevision)
                val artistTastes = UserTasteModel.buildAggregates(allSongs, trackTastes) { song ->
                    listOf(AffinityModel.normalizeArtistKey(song)).filter { it.isNotBlank() }
                }
                val genreTastes = UserTasteModel.buildAggregates(allSongs, trackTastes) { song ->
                    AffinityModel.normalizeGenres(song.genre)
                }
                val positiveSeeds = buildPositiveSeeds(allSongs, trackTastes)
                val negativeSeeds = UserTasteModel.buildNegativeSeeds(trackTastes)
                val pprScores = if (positiveSeeds.isNotEmpty()) {
                    PersonalizedPageRank.personalizedScores(combinedGraph, positiveSeeds, negativeSeeds)
                } else emptyMap()

                val preset = MixPresetCatalog.byId(request.mood)
                computePlaylist(
                    allSongs = allSongs,
                    profiles = profiles,
                    preset = preset,
                    request = request,
                    trackTastes = trackTastes,
                    artistTastes = artistTastes,
                    genreTastes = genreTastes,
                    pprScores = pprScores,
                    favoriteIds = allSongs.filter { it.isFavorite }.map { it.id }.toSet(),
                    recentlyPlayed = readRecentlyPlayedIds()
                )
            }
        }

    private suspend fun computePlaylist(
        allSongs: List<Song>,
        profiles: Map<String, SongProfile>,
        preset: dev.abstra.pro.ai.model.MixPreset,
        request: PlaylistRequest,
        trackTastes: Map<String, UserTasteModel.TrackTaste>,
        artistTastes: Map<String, UserTasteModel.AggregateTaste>,
        genreTastes: Map<String, UserTasteModel.AggregateTaste>,
        pprScores: Map<String, Float>,
        favoriteIds: Set<String>,
        recentlyPlayed: Set<String>
    ): GeneratedPlaylist? {
        val nowMs = System.currentTimeMillis()
        val songById = allSongs.associateBy { it.id }
        val transitions = loadTransitions()
        val transitionContext = BeamSearchSequencer.TransitionContext(
            signatures = signatureCache,
            scaler = scaler,
            transitionCounts = transitions.first,
            outgoingTotals = transitions.second
        )

        // Anchor songs for retrieval: favorites and current-session songs.
        val sessionAnchors = recentlyPlayed.take(3)

        val retriever = CandidateRetriever(
            songs = allSongs,
            profiles = profiles,
            signatures = signatureCache,
            scaler = scaler,
            audioGraph = audioNeighbourMap,
            pprScores = pprScores,
            trackTastes = trackTastes,
            favoriteIds = favoriteIds,
            anchorSongIds = sessionAnchors
        )
        val retrieved = retriever.retrieve(preset, request.seedSongId)

        // Normalize fused scores against the pool max.
        val maxFused = retrieved.fusedScores.values.maxOrNull() ?: 0f
        val fusedNormalized = if (maxFused > 0f) {
            retrieved.fusedScores.mapValues { (_, v) -> (v / maxFused).coerceIn(0f, 1f) }
        } else emptyMap()

        // Rank every candidate with the confidence-aware V2 ranker.
        val scoredList = mutableListOf<PlaylistRanker.Scored>()
        for ((songId, fused) in fusedNormalized) {
            val song = songById[songId] ?: continue
            val profile = profiles.getValue(song.id)
            val taste = trackTastes[songId]
            val ppr = pprScores[songId]

            // Max audio similarity to anchors (favorites / session / seed).
            val audioSim = maxAnchorAudioSimilarity(songId, favoriteIds, sessionAnchors, request.seedSongId)

            val artistTaste = artistTastes[AffinityModel.normalizeArtistKey(song)]
            val genreTaste = genreTastes.entries
                .filter { AffinityModel.normalizeGenres(song.genre).contains(it.key) }
                .maxByOrNull { it.value.confidence }?.value

            val artistGenreParts = mutableListOf<Pair<Float, Float>>()
            artistTaste?.let { artistGenreParts += it.value to it.confidence }
            genreTaste?.let { artistGenreParts += it.value to it.confidence }
            val artistGenreValue = if (artistGenreParts.isEmpty()) 0.5f
            else artistGenreParts.map { it.first }.average().toFloat()
            val artistGenreConfidence = artistGenreParts.maxOfOrNull { it.second } ?: 0f

            // Mood at the preset's base target (beam search refines per position).
            val moodMatch = moodMatchScore(song, profile, preset)

            // Novelty: honest and subordinate.
            val telemetry = telemetryCache[songId]
            val (noveltyValue, noveltyConfidence) = noveltyScore(song, telemetry, nowMs)

            scoredList += PlaylistRanker.score(
                PlaylistRanker.CandidateInput(
                    song = song,
                    profile = profile,
                    fusedScore = fused,
                    pprScore = ppr,
                    audioSimilarity = audioSim,
                    preference = taste,
                    artistGenreValue = artistGenreValue,
                    artistGenreConfidence = artistGenreConfidence,
                    moodValue = moodMatch.first,
                    moodConfidence = moodMatch.second,
                    noveltyValue = noveltyValue,
                    noveltyConfidence = noveltyConfidence,
                    sourceRanks = retrieved.sourceRanks.mapNotNull { (source, ranks) ->
                        ranks[songId]?.let { source to it }
                    }.toMap(),
                    playCount = telemetry?.playCount ?: 0
                )
            )
        }

        val ranked = scoredList
            .sortedWith(compareByDescending<PlaylistRanker.Scored> { it.score }.thenBy { it.input.song.id })
            .take(BEAM_CANDIDATE_LIMIT)

        if (ranked.isEmpty()) return null

        val tracks = BeamSearchSequencer.sequence(
            BeamSearchSequencer.Input(
                candidates = ranked,
                preset = preset,
                request = request,
                songs = allSongs.associateBy { it.id },
                transitionContext = transitionContext,
                recentlyPlayedIds = recentlyPlayed
            )
        )
        if (tracks.isEmpty()) return null

        val totalDurationMs = tracks.sumOf { songById[it.songId]?.durationMs ?: 0L }
        return GeneratedPlaylist(
            presetId = preset.id,
            title = preset.title,
            subtitle = preset.subtitle,
            description = preset.description,
            tracks = tracks,
            generatedAtMs = nowMs,
            refreshSeed = request.refreshSeed,
            totalDurationMs = totalDurationMs
        )
    }

    private fun maxAnchorAudioSimilarity(
        songId: String,
        favoriteIds: Set<String>,
        sessionAnchors: List<String>,
        seedSongId: String?
    ): Float? {
        val signature = signatureCache[songId] ?: return null
        val anchors = (favoriteIds + sessionAnchors + listOfNotNull(seedSongId)).filter { it != songId }
        var best: Float? = null
        for (anchorId in anchors) {
            val anchorSignature = signatureCache[anchorId] ?: continue
            val sim = AudioSimilarity.similarity(signature, anchorSignature, scaler) ?: continue
            if (best == null || sim > best) best = sim
        }
        return best
    }

    private fun moodMatchScore(
        song: Song,
        profile: SongProfile,
        preset: dev.abstra.pro.ai.model.MixPreset
    ): Pair<Float, Float> {
        val signature = signatureCache[song.id]
        var value = 0f
        var confidence = 0f

        val energyMatch = gaussian(profile.energy, preset.targetEnergy, preset.energyTolerance)
        value += energyMatch * 0.6f
        confidence += 0.6f * profile.energyConfidence

        preset.targetRhythmActivity?.let { target ->
            val rhythm = signature?.rhythmActivity ?: profile.rhythmActivity
            if (rhythm != null) {
                value += gaussian(rhythm, target, preset.rhythmTolerance) * 0.2f
                confidence += 0.14f
            }
        }
        preset.targetAcousticness?.let { target ->
            if (profile.acousticConfidence > 0f) {
                value += gaussian(profile.acousticness, target, preset.acousticnessTolerance) * 0.15f
                confidence += 0.15f * profile.acousticConfidence
            }
        }
        preset.targetInstrumental?.let { target ->
            if (profile.instrumentalConfidence > 0f) {
                value += gaussian(profile.instrumentalLikelihood, target, 0.35f) * 0.05f
                confidence += 0.05f * profile.instrumentalConfidence
            }
        }
        preset.targetBrightness?.let { target ->
            val brightness = signature?.spectralCentroid
            if (brightness != null) {
                value += gaussian(brightness, target, 0.35f) * 0.1f
                confidence += 0.07f * signature.featureConfidence
            }
        }
        // Tempo window: bonus only when the estimate is reliable enough.
        val tempoMin = preset.targetTempoMinBpm
        val tempoMax = preset.targetTempoMaxBpm
        if (tempoMin != null && tempoMax != null && signature != null &&
            signature.tempoConfidence >= 0.35f && signature.tempoBpm > 0f
        ) {
            val inRange = signature.tempoBpm in tempoMin..tempoMax ||
                    signature.tempoBpm * 2 in tempoMin..tempoMax ||
                    signature.tempoBpm / 2 in tempoMin..tempoMax
            value += if (inRange) 0.05f else 0f
            confidence += 0.03f
        }

        return if (confidence <= 0f) 0.5f to 0f
        else (value / maxOf(confidence, 0.85f)).coerceIn(0f, 1f) to confidence.coerceIn(0f, 1f)
    }

    /**
     * Novelty is honest: unplayed means unknown (neutral, low confidence),
     * recently played is penalized, forgotten favorites earn a modest bonus.
     */
    private fun noveltyScore(
        song: Song,
        telemetry: PlaybackTelemetryEntity?,
        nowMs: Long
    ): Pair<Float, Float> {
        if (telemetry == null || telemetry.playCount <= 0) {
            return 0.5f to 0f // unknown ≠ liked
        }
        val last = telemetry.lastPlayedTimestamp
        if (last <= 0L) return 0.5f to 0f
        val days = ((nowMs - last).coerceAtLeast(0L)) / DAY_MS
        return when {
            days <= 2 -> 0.15f to 0.9f // recent repetition penalty
            days <= 7 -> (0.15f + (days - 2) * 0.06f) to 0.7f
            days <= 21 -> (0.45f + (days - 7) * 0.025f) to 0.6f
            else -> {
                // Forgotten favourite: modest discovery bonus, not dominance.
                val taste = 0.7f
                taste to 0.5f
            }
        }
    }

    private fun gaussian(x: Float, target: Float, sigma: Float): Float {
        if (sigma <= 0f) return if (x == target) 1f else 0f
        val d = (x - target) / sigma
        return kotlin.math.exp(-0.5f * d * d)
    }

    // ── Seeds and taste ──

    private suspend fun ensureTrackTastes(revision: Long): Map<String, UserTasteModel.TrackTaste> {
        tasteMutex.withLock {
            if (revision == tasteCacheRevision && tasteCache.isNotEmpty()) return tasteCache
            val events = runCatching { feedbackDao.getAllSync() }.getOrDefault(emptyList())
            tasteCache = UserTasteModel.buildTrackTastes(
                events, telemetryCache, System.currentTimeMillis()
            )
            tasteCacheRevision = revision
            return tasteCache
        }
    }

    /** Positive seeds: favorites, strong taste, completions, recent plays. */
    private suspend fun buildPositiveSeeds(
        allSongs: List<Song>,
        trackTastes: Map<String, UserTasteModel.TrackTaste>
    ): Map<String, Float> {
        val now = System.currentTimeMillis()
        val seeds = HashMap<String, Float>()
        val validIds = allSongs.map { it.id }.toSet()

        fun addSeed(songId: String, weight: Float) {
            if (songId !in validIds) return
            seeds[songId] = (seeds[songId] ?: 0f) + weight
        }

        for (song in allSongs) {
            if (song.isFavorite) addSeed(song.id, 1.5f)
            val taste = trackTastes[song.id] ?: continue
            if (taste.confidence <= 0f) continue
            val tasteWeight = taste.positive * taste.confidence
            if (tasteWeight >= 0.3f) addSeed(song.id, tasteWeight * 3f)
            val telemetry = telemetryCache[song.id]
            if (telemetry != null && telemetry.lastPlayedTimestamp > 0L &&
                now - telemetry.lastPlayedTimestamp <= 7L * DAY_MS && telemetry.playCount > 0
            ) {
                addSeed(song.id, 1f)
            }
        }

        // Cap each seed so one hyper-played track cannot dominate; keep the
        // distinct taste clusters alive (PPR normalizes the total).
        return seeds.mapValues { (_, weight) -> minOf(weight, MAX_SEED_WEIGHT) }
    }

    private fun restoreFromCache(
        cache: MixCacheData,
        songById: Map<String, Song>
    ): List<GeneratedPlaylist> = cache.playlists.mapNotNull { cached ->
        val preset = MixPresetCatalog.byId(cached.presetId)
        val tracks = cached.songIds.mapNotNull { id ->
            val song = songById[id] ?: return@mapNotNull null
            val reasons = cached.reasons[id]
                ?.mapNotNull { runCatching { RecommendationReason.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?: emptySet()
            RecommendedTrack(songId = id, score = 0.5f, reasons = reasons)
        }
        if (tracks.isEmpty()) null
        else GeneratedPlaylist(
            presetId = preset.id,
            title = preset.title,
            subtitle = preset.subtitle,
            description = preset.description,
            tracks = tracks,
            generatedAtMs = cached.generatedAtMs,
            refreshSeed = cached.refreshSeed,
            totalDurationMs = cached.totalDurationMs
        )
    }

    private suspend fun ensureTelemetryLoaded() {
        if (telemetryLoaded) return
        runCatching {
            telemetryDao.getAllTelemetrySync().forEach { telemetryCache[it.songId] = it }
        }
        telemetryLoaded = true
    }

    /** Recently played ids are stored as a comma-separated string by the UI layer. */
    private fun readRecentlyPlayedIds(): Set<String> =
        prefs.getString("recently_played_ids", "")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    private suspend fun loadTransitions(): Pair<Map<String, Map<String, Int>>, Map<String, Int>> {
        val all = runCatching { telemetryDao.getAllTransitionsSync() }.getOrDefault(emptyList())
        val counts = HashMap<String, MutableMap<String, Int>>()
        val totals = HashMap<String, Int>()
        for (t in all) {
            counts.getOrPut(t.fromSongId) { HashMap() }[t.toSongId] = t.transitionCount
            totals[t.fromSongId] = (totals[t.fromSongId] ?: 0) + t.transitionCount
        }
        return counts to totals
    }

    private fun libraryFingerprint(songs: List<Song>): String {
        var hash = 1125899906842597L
        for (song in songs) {
            hash = hash * 31L + song.id.hashCode()
            hash = hash * 31L + song.fileSize.hashCode()
        }
        return "$hash-${songs.size}"
    }

    /** Hash over the effective metadata profiles of the whole library. */
    private fun metadataFingerprint(songs: List<Song>, profiles: Map<String, SongProfile>): String {
        val sorted = songs.sortedBy { it.id }
        var hash = 1469598103934665603L
        for (song in sorted) {
            hash = hash * 31L + (profiles[song.id]?.metadataFingerprint?.hashCode() ?: 0)
        }
        return hash.toString()
    }

    private fun stableSeed(dateKey: String, fingerprint: String): Long {
        var hash = 1469598103934665603L
        for (c in (dateKey + "|" + fingerprint)) {
            hash = hash * 1099511628211L + c.code
        }
        return hash
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ═════════════════════════════════════════════════════════════
    // 6. OPTIONAL AUDIO INTELLIGENCE — full library, tiered priorities
    // ═════════════════════════════════════════════════════════════

    /** Off: metadata-only recommendations. On: profiles improve in the background. */
    fun setAudioIntelligenceEnabled(enabled: Boolean) {
        audioEnabled = enabled
        prefs.edit().putBoolean(PREF_AUDIO_INTELLIGENCE, enabled).apply()
        if (enabled) {
            signaturesLoaded = false
            startAudioWorker()
            lastSongs?.let { enqueueLibraryAnalysis(it) }
        } else {
            // Stop new analysis safely; existing cached signatures are kept.
            stopAudioWorker()
        }
    }

    fun enqueueAudioAnalysis(songs: List<Song>, priority: Int = 5) {
        if (!audioEnabled || closed) return
        songs.forEach { addAudioTask(it, priority) }
        lastSongs?.let { enqueueLibraryAnalysis(it) }
    }

    /**
     * Progressive library coverage with tiered priorities:
     *  1. new/modified songs without a valid signature,
     *  2. frequently played or favorited but unanalyzed,
     *  3. other unprocessed tracks (bounded per pass),
     *  4. re-analysis of stale signatures.
     */
    private fun enqueueLibraryAnalysis(songs: List<Song>) {
        if (!audioEnabled || closed || songs.isEmpty()) return
        val now = System.currentTimeMillis()

        // Tier 1+2: songs missing a valid signature.
        val missing = songs.filter { song ->
            val signature = signatureCache[song.id]
            signature == null || !signature.isValid() ||
                    signature.extractorVersion < AudioSignature.EXTRACTOR_VERSION
        }
        if (missing.isEmpty()) {
            updateProgressSnapshot()
            return
        }

        val favored = missing.filter { it.isFavorite }
        val played = missing.filter { (telemetryCache[it.id]?.playCount ?: 0) >= 3 && !it.isFavorite }
        val newOrModified = missing.filter { song ->
            val signature = signatureCache[song.id]
            signature == null || signature.extractorVersion < AudioSignature.EXTRACTOR_VERSION
        }
        val rest = missing.filter { it !in favored && it !in played && it !in newOrModified }

        newOrModified.take(ANALYSIS_TIER_LIMIT).forEach { addAudioTask(it, 0) }
        favored.take(ANALYSIS_TIER_LIMIT).forEach { addAudioTask(it, 1) }
        played.take(ANALYSIS_TIER_LIMIT).forEach { addAudioTask(it, 2) }
        rest.take(ANALYSIS_TIER_LIMIT * 2).forEach { addAudioTask(it, 3) }

        // Tier 4: stale-but-valid signatures are refreshed at low priority.
        val stale = songs.filter { song ->
            val signature = signatureCache[song.id]
            signature != null && signature.isValid() &&
                    (signature.extractorVersion < AudioSignature.EXTRACTOR_VERSION ||
                            now - signature.analyzedAtMs > SIGNATURE_STALE_MS)
        }
        stale.take(ANALYSIS_TIER_LIMIT).forEach { addAudioTask(it, 8) }

        updateProgressSnapshot()
    }

    private fun addAudioTask(song: Song, priority: Int) {
        if (song.durationMs <= 0) return
        if (audioQueuedIds.add(song.id)) {
            audioQueue.add(AudioTask(song, priority))
        }
    }

    private fun startAudioWorker() {
        if (audioJob?.isActive == true || closed) return
        audioJob = engineScope.launch {
            var analyzedSinceRegeneration = 0
            while (isActive) {
                if (!audioEnabled) {
                    delay(2_500)
                    continue
                }
                if (playbackActive) {
                    // Never analyze during active music playback.
                    delay(3_000)
                    continue
                }
                if (!audioBudgetAvailable()) {
                    // Daily budget exhausted; flush one regeneration if the
                    // graph changed, then wait.
                    if (analyzedSinceRegeneration > 0) {
                        analyzedSinceRegeneration = 0
                        regenerateMixesIfNeeded()
                    }
                    delay(60_000)
                    continue
                }
                val task = audioQueue.pollFirst()
                if (task == null) {
                    if (analyzedSinceRegeneration > 0) {
                        analyzedSinceRegeneration = 0
                        regenerateMixesIfNeeded()
                    }
                    updateProgressSnapshot()
                    delay(4_000)
                    continue
                }
                audioQueuedIds.remove(task.song.id)
                val fingerprint = SongProfileAnalyzer.fingerprint(task.song)
                val existing = signatureCache[task.song.id]
                val needsAnalysis = existing == null ||
                        existing.extractorVersion < AudioSignature.EXTRACTOR_VERSION ||
                        existing.metadataFingerprint != fingerprint
                if (needsAnalysis) {
                    val signature = audioExtractor.extract(task.song)
                    if (signature != null && signature.isValid()) {
                        onSignatureExtracted(task.song, fingerprint, signature)
                        recordAudioAnalysis()
                        analyzedSinceRegeneration++
                        if (analyzedSinceRegeneration >= ANALYSIS_REGENERATION_BATCH) {
                            analyzedSinceRegeneration = 0
                            regenerateMixesIfNeeded()
                        }
                    } else {
                        // Persist UNSUPPORTED/FAILED so hopeless files are
                        // never retried forever.
                        runCatching {
                            signatureDao.upsert(
                                AudioSignature(
                                    songId = task.song.id,
                                    mfccMean = FloatArray(AudioSignature.MFCC_COUNT),
                                    mfccStd = FloatArray(AudioSignature.MFCC_COUNT),
                                    chroma = FloatArray(AudioSignature.CHROMA_COUNT),
                                    chromaConfidence = 0f,
                                    tempoBpm = 0f,
                                    tempoConfidence = 0f,
                                    rmsEnergy = 0f,
                                    dynamicRange = 0f,
                                    spectralCentroid = 0f,
                                    spectralFlux = 0f,
                                    onsetDensity = 0f,
                                    rhythmActivity = 0f,
                                    featureConfidence = 0f,
                                    extractorVersion = AudioSignature.EXTRACTOR_VERSION,
                                    metadataFingerprint = fingerprint,
                                    analysisStatus = AudioSignature.STATUS_FAILED,
                                    analyzedAtMs = System.currentTimeMillis()
                                ).toEntity()
                            )
                        }
                        signatureCache[task.song.id] =
                            AudioSignature(
                                songId = task.song.id,
                                mfccMean = FloatArray(AudioSignature.MFCC_COUNT),
                                mfccStd = FloatArray(AudioSignature.MFCC_COUNT),
                                chroma = FloatArray(AudioSignature.CHROMA_COUNT),
                                chromaConfidence = 0f,
                                tempoBpm = 0f,
                                tempoConfidence = 0f,
                                rmsEnergy = 0f,
                                dynamicRange = 0f,
                                spectralCentroid = 0f,
                                spectralFlux = 0f,
                                onsetDensity = 0f,
                                rhythmActivity = 0f,
                                featureConfidence = 0f,
                                extractorVersion = AudioSignature.EXTRACTOR_VERSION,
                                metadataFingerprint = fingerprint,
                                analysisStatus = AudioSignature.STATUS_FAILED,
                                analyzedAtMs = System.currentTimeMillis()
                            )
                    }
                    updateProgressSnapshot()
                }
            }
        }
    }

    /** One regeneration after an analysis batch — never per track. */
    private fun regenerateMixesIfNeeded() {
        if (closed || !audioEnabled) return
        if (!graphDirty) return
        val songs = lastSongs ?: return
        if (playbackActive) return
        engineScope.launch {
            mixMutex.withLock {
                _isGenerating.value = true
                try {
                    _mixes.value = generateMixesInternal(songs, forceRefresh = false)
                } finally {
                    _isGenerating.value = false
                }
            }
        }
    }

    private fun stopAudioWorker() {
        audioJob?.cancel()
        audioJob = null
        audioQueue.clear()
        audioQueuedIds.clear()
        _audioAnalysisProgress.value = AudioAnalysisProgress(0, 0)
    }

    private fun audioBudgetAvailable(): Boolean {
        val today = todayKey()
        val day = prefs.getString(PREF_AUDIO_ANALYSIS_DAY, null)
        val count = prefs.getInt(PREF_AUDIO_ANALYSIS_COUNT, 0)
        if (day != today) return true
        return count < MAX_AUDIO_ANALYSIS_PER_DAY
    }

    private fun recordAudioAnalysis() {
        val today = todayKey()
        val day = prefs.getString(PREF_AUDIO_ANALYSIS_DAY, null)
        val count = if (day == today) prefs.getInt(PREF_AUDIO_ANALYSIS_COUNT, 0) else 0
        prefs.edit()
            .putString(PREF_AUDIO_ANALYSIS_DAY, today)
            .putInt(PREF_AUDIO_ANALYSIS_COUNT, count + 1)
            .apply()
    }

    private fun updateProgressSnapshot() {
        engineScope.launch {
            val analyzed = runCatching { signatureDao.countSuccess() }.getOrDefault(0)
            _audioAnalysisProgress.value =
                AudioAnalysisProgress(audioQueue.size, analyzed)
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 7. LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    /** Cancels all background work; called when the engine's owner is destroyed. */
    fun close() {
        closed = true
        stopAudioWorker()
        engineScope.cancel()
    }

    companion object {
        private const val PREF_AUDIO_INTELLIGENCE = "audio_intelligence_enabled"
        private const val PREF_AUDIO_ANALYSIS_DAY = "audio_analysis_day"
        private const val PREF_AUDIO_ANALYSIS_COUNT = "audio_analysis_count"
        private const val PREF_TASTE_REVISION = "ai_taste_revision"
        private const val PREF_GRAPH_VERSION = "ai_graph_version"
        private const val PREF_GRAPH_LIBRARY_FP = "ai_graph_library_fp"

        /** Strict daily cap to protect battery and thermals. */
        private const val MAX_AUDIO_ANALYSIS_PER_DAY = 60

        /** Per-tier cap of enqueued tracks per library pass. */
        private const val ANALYSIS_TIER_LIMIT = 120

        /** Regenerate mixes after this many fresh signatures (batched). */
        private const val ANALYSIS_REGENERATION_BATCH = 10

        private const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Signatures older than this are eligible for refresh. */
        private const val SIGNATURE_STALE_MS = 90L * DAY_MS

        private const val PROFILE_BATCH_SIZE = 200
        private const val BEAM_CANDIDATE_LIMIT = 120
        private const val GRAPH_BATCH_SIZE = 100
        private const val METADATA_REFRESH_BATCH = 50

        /** Feedback retention: rolling window + hard cap. */
        private const val FEEDBACK_RETENTION_MS = 180L * DAY_MS
        private const val FEEDBACK_MAX_EVENTS = 10_000
        private const val FEEDBACK_PRUNE_INTERVAL = 200

        /** Taste revision bumps in batches of this many events (or immediately). */
        private const val TASTE_BATCH_EVENTS = 3

        private const val MAX_SEED_WEIGHT = 4f

        /** Recommendation algorithm version — bump to invalidate cached mixes. */
        const val ALGORITHM_VERSION = 2

        /** Preset configuration version — bump when presets materially change. */
        const val PRESET_VERSION = 2

        private const val PROGRESS_TICK_MAX_MS = 20_000L
        private const val FAST_SKIP_MS = 15_000L
        private const val FAST_SKIP_RATIO = 0.12f
        private const val REPLAY_WINDOW_MS = 120_000L
    }
}
