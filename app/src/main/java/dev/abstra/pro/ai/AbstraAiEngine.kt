package dev.abstra.pro.ai

import android.content.Context
import dev.abstra.pro.ai.model.AudioFeatures
import dev.abstra.pro.ai.model.DayType
import dev.abstra.pro.ai.model.GeneratedPlaylist
import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.RecommendationReason
import dev.abstra.pro.ai.model.RecommendedTrack
import dev.abstra.pro.ai.model.ScoringContext
import dev.abstra.pro.ai.model.SequenceInput
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.ai.model.TimeOfDay
import dev.abstra.pro.data.db.AppDatabase
import dev.abstra.pro.data.db.entities.MarkovTransitionEntity
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
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
 * On-device, fully offline music intelligence engine.
 *
 * One deterministic pipeline for every mix:
 *
 *   library + overrides + profiles + telemetry + markov
 *       -> SongProfileAnalyzer
 *       -> AffinityModel (normalized preferences)
 *       -> PlaylistScorer -> PlaylistSequencer
 *       -> GeneratedPlaylist (song ids + scores + reason codes)
 *
 * The engine never controls playback and never touches Compose types.
 * Metadata analysis is the foundation; playback behaviour provides
 * personalization; audio analysis is an optional refinement.
 */
class AbstraAiEngine(
    context: Context,
    private val db: AppDatabase
) {
    private val appContext = context.applicationContext
    private val telemetryDao = db.telemetryDao()
    private val profileDao = db.songProfileDao()
    private val prefs = appContext.getSharedPreferences("abstra_prefs", Context.MODE_PRIVATE)

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mixCache = MixCache(appContext)
    private val audioExtractor = AudioFeatureExtractor(appContext)

    private val _mixes = MutableStateFlow<List<GeneratedPlaylist>>(emptyList())
    val mixes: StateFlow<List<GeneratedPlaylist>> = _mixes.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // ── Shared state (guarded) ──
    private val telemetryCache = ConcurrentHashMap<String, PlaybackTelemetryEntity>()
    private val profileCache = ConcurrentHashMap<String, SongProfile>()
    private val telemetryMutex = Mutex()
    private val profileMutex = Mutex()
    private val mixMutex = Mutex()

    @Volatile
    private var telemetryLoaded = false

    @Volatile
    private var profilesLoaded = false

    @Volatile
    private var closed = false

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

        if (!meaningful && treatAsSkip && isFastSkip(s)) {
            updateTelemetry(s.songId) { cur ->
                cur.copy(
                    fastSkips = cur.fastSkips + 1,
                    consecutiveSkips = cur.consecutiveSkips + 1
                )
            }
        }

        if (completed) {
            updateTelemetry(s.songId) { cur ->
                cur.copy(fullCompletions = cur.fullCompletions + 1, consecutiveSkips = 0)
            }
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
            val fresh = existing != null &&
                    existing.metadataFingerprint == fingerprint &&
                    existing.analysisVersion == SongProfileAnalyzer.ANALYSIS_VERSION
            val profile = if (fresh && existing != null) {
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
        engineScope.launch { runCatching { profileDao.deleteProfile(songId) } }
    }

    fun onSongsDeleted(songIds: List<String>) {
        songIds.forEach { profileCache.remove(it) }
        engineScope.launch {
            songIds.forEach { id -> runCatching { profileDao.deleteProfile(id) } }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 3. DAILY MIX GENERATION
    // ═════════════════════════════════════════════════════════════

    fun generateDailyMixes(allSongs: List<Song>, forceRefresh: Boolean = false) {
        if (allSongs.isEmpty() || closed) return
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
        val aggregates = AffinityModel.buildAggregates(allSongs, telemetryCache, profiles)
        val transitions = loadTransitions()
        val recentlyPlayed = readRecentlyPlayedIds()
        val now = System.currentTimeMillis()
        val context = ScoringContext(
            nowMs = now,
            timeOfDay = TimeOfDay.current(),
            isWeekend = DayType.current() == DayType.WEEKEND,
            artistAffinity = aggregates.artist,
            genreAffinity = aggregates.genre,
            tagAffinity = aggregates.tag,
            recentlyPlayedIds = recentlyPlayed
        )

        // One base scoring pass over the library; mood is combined per preset.
        val bases = allSongs.map { song ->
            PlaylistScorer.scoreBase(
                song = song,
                profile = profiles.getValue(song.id),
                telemetry = telemetryCache[song.id],
                context = context
            )
        }

        val songById = allSongs.associateBy { it.id }
        val fingerprint = libraryFingerprint(allSongs)
        val dateKey = todayKey()
        val cache = mixCache.load()

        // Stable daily mixes: reuse the cached selection when the day and the
        // library have not changed.
        if (!forceRefresh && cache != null &&
            cache.dateKey == dateKey &&
            cache.libraryFingerprint == fingerprint
        ) {
            val restored = restoreFromCache(cache, songById)
            if (restored.isNotEmpty()) {
                enqueueAudioCandidates(restored, songById)
                return restored
            }
        }

        // A forced refresh on the same day bumps the seed; otherwise the seed
        // is derived from the date + library so generation is reproducible.
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
                bases = bases,
                preset = preset,
                request = request,
                nowMs = now,
                transitionCounts = transitions.first,
                outgoingTotals = transitions.second,
                recentlyPlayedIds = recentlyPlayed,
                songById = songById
            ) ?: continue
            generated += playlist
        }

        if (generated.isNotEmpty()) {
            mixCache.save(
                MixCacheData(
                    dateKey = dateKey,
                    libraryFingerprint = fingerprint,
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
            enqueueAudioCandidates(generated, songById)
        }
        return generated
    }

    /** Generates a single playlist for an explicit request. */
    suspend fun generatePlaylist(allSongs: List<Song>, request: PlaylistRequest): GeneratedPlaylist? =
        withContext(Dispatchers.Default) {
            if (allSongs.isEmpty() || closed) return@withContext null
            mixMutex.withLock {
                ensureTelemetryLoaded()
                val profiles = ensureProfiles(allSongs)
                val aggregates = AffinityModel.buildAggregates(allSongs, telemetryCache, profiles)
                val transitions = loadTransitions()
                val recentlyPlayed = readRecentlyPlayedIds()
                val context = ScoringContext(
                    nowMs = System.currentTimeMillis(),
                    timeOfDay = TimeOfDay.current(),
                    isWeekend = DayType.current() == DayType.WEEKEND,
                    artistAffinity = aggregates.artist,
                    genreAffinity = aggregates.genre,
                    tagAffinity = aggregates.tag,
                    recentlyPlayedIds = recentlyPlayed
                )
                val bases = allSongs.map { song ->
                    PlaylistScorer.scoreBase(song, profiles.getValue(song.id), telemetryCache[song.id], context)
                }
                val preset = MixPresetCatalog.byId(request.mood)
                computePlaylist(
                    bases = bases,
                    preset = preset,
                    request = request,
                    nowMs = System.currentTimeMillis(),
                    transitionCounts = transitions.first,
                    outgoingTotals = transitions.second,
                    recentlyPlayedIds = recentlyPlayed,
                    songById = allSongs.associateBy { it.id }
                )
            }
        }

    private fun computePlaylist(
        bases: List<BaseScores>,
        preset: dev.abstra.pro.ai.model.MixPreset,
        request: PlaylistRequest,
        nowMs: Long,
        transitionCounts: Map<String, Map<String, Int>>,
        outgoingTotals: Map<String, Int>,
        recentlyPlayedIds: Set<String>,
        songById: Map<String, Song>
    ): GeneratedPlaylist? {
        val scored = bases.map { PlaylistScorer.score(it, preset) }
        val input = SequenceInput(
            scored = scored,
            preset = preset,
            request = request,
            nowMs = nowMs,
            transitionCounts = transitionCounts,
            outgoingTotals = outgoingTotals,
            recentlyPlayedIds = recentlyPlayedIds
        )
        val tracks = PlaylistSequencer.sequence(input)
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
    // 4. OPTIONAL AUDIO INTELLIGENCE
    // ═════════════════════════════════════════════════════════════

    /** Off: metadata-only recommendations. On: profiles improve in the background. */
    fun setAudioIntelligenceEnabled(enabled: Boolean) {
        audioEnabled = enabled
        prefs.edit().putBoolean(PREF_AUDIO_INTELLIGENCE, enabled).apply()
        if (enabled) startAudioWorker() else stopAudioWorker()
    }

    fun enqueueAudioAnalysis(songs: List<Song>, priority: Int = 5) {
        if (!audioEnabled || closed) return
        songs.forEach { addAudioTask(it, priority) }
    }

    private fun addAudioTask(song: Song, priority: Int) {
        if (song.durationMs <= 0) return
        if (audioQueuedIds.add(song.id)) {
            audioQueue.add(AudioTask(song, priority))
        }
    }

    private fun enqueueAudioCandidates(
        mixes: List<GeneratedPlaylist>,
        songById: Map<String, Song>
    ) {
        if (!audioEnabled || closed) return
        var priority = 0
        for (mix in mixes) {
            mix.tracks.take(AUDIO_CANDIDATES_PER_MIX).forEach { track ->
                songById[track.songId]?.let { addAudioTask(it, priority) }
            }
            priority++
        }
    }

    private fun startAudioWorker() {
        if (audioJob?.isActive == true || closed) return
        audioJob = engineScope.launch {
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
                    delay(60_000)
                    continue
                }
                val task = audioQueue.pollFirst()
                if (task == null) {
                    delay(4_000)
                    continue
                }
                audioQueuedIds.remove(task.song.id)
                val features = audioExtractor.extract(task.song)
                if (features != null) {
                    blendAndPersist(task.song, features)
                    recordAudioAnalysis()
                }
            }
        }
    }

    private fun stopAudioWorker() {
        audioJob?.cancel()
        audioJob = null
        audioQueue.clear()
        audioQueuedIds.clear()
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

    /**
     * Blends fresh audio measurements with the metadata-only profile.
     * Re-analysis of the same metadata does not compound: the base for
     * blending is always the metadata profile.
     */
    private suspend fun blendAndPersist(song: Song, features: AudioFeatures) {
        val existing = profileCache[song.id]
        val fingerprint = SongProfileAnalyzer.fingerprint(song)
        val needsMetadataBase = existing == null ||
                existing.profileSource != SongProfileAnalyzer.PROFILE_SOURCE_BLENDED ||
                existing.metadataFingerprint != fingerprint ||
                existing.analysisVersion != SongProfileAnalyzer.ANALYSIS_VERSION
        if (!needsMetadataBase) return

        val metadataProfile = SongProfileAnalyzer.analyze(song)
        val blended = SongProfileAnalyzer.blendFeatures(metadataProfile, features)
        profileCache[song.id] = blended
        runCatching {
            profileDao.upsert(blended.toEntity(System.currentTimeMillis(), features.analyzedAtMs))
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 5. LIFECYCLE
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

        /** Strict daily cap to protect battery and thermals. */
        private const val MAX_AUDIO_ANALYSIS_PER_DAY = 60

        private const val AUDIO_CANDIDATES_PER_MIX = 12
        private const val PROFILE_BATCH_SIZE = 200

        private const val PROGRESS_TICK_MAX_MS = 20_000L
        private const val FAST_SKIP_MS = 15_000L
        private const val FAST_SKIP_RATIO = 0.12f
        private const val REPLAY_WINDOW_MS = 120_000L
    }
}
