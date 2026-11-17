package dev.abstra.pro.ai.learning

import dev.abstra.pro.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the in-memory playback session state and decides what counts as
 * meaningful listening.
 *
 * Responsibilities:
 *  - track real listened time (progress ticks only — never seek jumps);
 *  - fire "meaningful play" exactly once per session at the threshold;
 *  - distinguish natural completion from fast skips from ordinary manual Next;
 *  - record the Markov transition from the immediately previous *meaningful*
 *    session (never from two starts ago, never from accidental playback);
 *  - never write to Room from the callback thread: all persistence is
 *    dispatched asynchronously through the [Sink].
 *
 * Concurrency: every state mutation happens synchronously inside a lock, so
 * the tracker is safe no matter which thread the player callbacks arrive on.
 * Only [Sink] calls are asynchronous, and each session's flags are updated
 * before the corresponding sink call is enqueued, so duplicate callbacks
 * cannot double-count.
 */
class PlaybackSessionTracker(
    private val scope: CoroutineScope,
    private val sink: Sink,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    interface Sink {
        /** One meaningful play (or replay) has been earned. */
        suspend fun onMeaningfulPlay(snapshot: SessionSnapshot, isReplay: Boolean)

        /** A fast skip: negative evidence, weak but real. */
        suspend fun onFastSkip(snapshot: SessionSnapshot)

        /** The track reached its natural end. */
        suspend fun onCompletion(snapshot: SessionSnapshot)

        /** Persist listened time without a full meaningful play (pause etc.). */
        suspend fun onListenedDelta(snapshot: SessionSnapshot, deltaMs: Long)

        /** Historical transition between two meaningfully listened tracks. */
        suspend fun onTransition(fromSongId: String, toSongId: String)
    }

    /** Immutable view of a session for persistence. */
    data class SessionSnapshot(
        val songId: String,
        val durationMs: Long,
        val startedAtMs: Long,
        val listenedMs: Long,
        val listenedRatio: Float
    )

    class Session internal constructor(
        val songId: String,
        val durationMs: Long,
        val startedAtMs: Long,
        var lastPositionMs: Long,
        var listenedMs: Long,
        var persistedListenedMs: Long,
        var meaningfulCounted: Boolean
    )

    // ── Session state (guarded by [lock]) ──
    private val lock = Any()
    private var session: Session? = null

    /** Last song that earned a meaningful listen — the only legal Markov source. */
    @Volatile
    private var lastMeaningfulSongId: String? = null

    @Volatile
    private var lastMeaningfulTimeMs: Long = 0L

    /** Duplicate-callback guard: identical terminal events in quick succession. */
    private var lastFastSkipSongId: String? = null
    private var lastFastSkipAtMs: Long = 0L
    private var lastCompletionSongId: String? = null
    private var lastCompletionAtMs: Long = 0L

    // ─────────────────────────────────────────────────────────────
    // Player callbacks
    // ─────────────────────────────────────────────────────────────

    fun onSongStarted(song: Song) {
        val prevMeaningful: String? = synchronized(lock) {
            // Finalize any dangling session defensively (e.g. manual replay,
            // rapid track change without a skip callback).
            session?.let { s -> enqueueSessionEnd(s, completed = false, treatAsSkip = true) }
            session = Session(
                songId = song.id,
                durationMs = song.durationMs,
                startedAtMs = clock(),
                lastPositionMs = 0L,
                listenedMs = 0L,
                persistedListenedMs = 0L,
                meaningfulCounted = false
            )
            // Markov transition is recorded only from the immediately previous
            // meaningful session. Playback starts alone never create a source.
            lastMeaningfulSongId
        }
        if (prevMeaningful != null && prevMeaningful != song.id) {
            scope.launch { sink.onTransition(prevMeaningful, song.id) }
        }
    }

    fun onSongProgress(song: Song, positionMs: Long, @Suppress("UNUSED_PARAMETER") durationMs: Long) {
        synchronized(lock) {
            val s = session ?: return
            if (s.songId != song.id) return
            val delta = positionMs - s.lastPositionMs
            // Progress ticks are a few seconds apart; larger jumps are seeks
            // and do not count as listening. A seek is never a dislike.
            if (delta in 1..PROGRESS_TICK_MAX_MS) s.listenedMs += delta
            s.lastPositionMs = positionMs
            if (!s.meaningfulCounted && s.listenedMs >= meaningfulThresholdMs(s.durationMs)) {
                countMeaningfulPlayLocked(s)
            }
        }
    }

    fun onSongCompleted(song: Song) {
        synchronized(lock) {
            val s = session
            if (s != null && s.songId == song.id) {
                // Reaching the end counts the remaining part as listened time.
                s.listenedMs += (s.durationMs - s.lastPositionMs).coerceAtLeast(0L)
                enqueueSessionEnd(s, completed = true, treatAsSkip = false)
            } else {
                // No live session (callbacks restarted); the reported song
                // still completed.
                scope.launch {
                    sink.onCompletion(
                        SessionSnapshot(
                            songId = song.id,
                            durationMs = song.durationMs,
                            startedAtMs = 0L,
                            listenedMs = song.durationMs,
                            listenedRatio = 1f
                        )
                    )
                }
            }
            session = null
        }
    }

    fun onSongSkipped(song: Song, playedSeconds: Long, totalDurationSeconds: Long) {
        synchronized(lock) {
            val s = session
            if (s != null && s.songId == song.id) {
                enqueueSessionEnd(s, completed = false, treatAsSkip = true)
            } else {
                // No live session (callbacks restarted); approximate from the
                // reported played time so fast skips still register.
                val durationMs = totalDurationSeconds * 1000L
                val approx = Session(
                    songId = song.id,
                    durationMs = durationMs,
                    startedAtMs = 0L,
                    lastPositionMs = playedSeconds * 1000L,
                    listenedMs = playedSeconds * 1000L,
                    persistedListenedMs = 0L,
                    meaningfulCounted = false
                )
                enqueueSessionEnd(approx, completed = false, treatAsSkip = true)
            }
            session = null
        }
    }

    fun onPlaybackPaused(song: Song) {
        synchronized(lock) {
            val s = session ?: return
            if (s.songId != song.id) return
            if (!s.meaningfulCounted && s.listenedMs >= meaningfulThresholdMs(s.durationMs)) {
                countMeaningfulPlayLocked(s)
            } else {
                enqueueListenedDelta(s)
            }
        }
    }

    /** Test/engine helper: current session snapshot, or null. */
    fun currentSnapshot(): SessionSnapshot? = synchronized(lock) {
        session?.let { s ->
            SessionSnapshot(
                songId = s.songId,
                durationMs = s.durationMs,
                startedAtMs = s.startedAtMs,
                listenedMs = s.listenedMs,
                listenedRatio = if (s.durationMs > 0) {
                    (s.listenedMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
                } else 0f
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Internals (lock is held by callers)
    // ─────────────────────────────────────────────────────────────

    /**
     * Duration-aware meaningful-listening threshold. Short tracks use a
     * proportional threshold (20% of duration, at least 8s) instead of
     * treating every track shorter than 20 seconds as an automatic skip;
     * everything else requires 30 seconds.
     */
    fun meaningfulThresholdMs(durationMs: Long): Long {
        return if (durationMs in 1..SHORT_TRACK_MAX_MS) {
            (durationMs * SHORT_TRACK_RATIO).toLong().coerceAtLeast(8_000L)
        } else {
            MEANINGFUL_MS
        }
    }

    private fun isFastSkip(s: Session): Boolean {
        val ratio = if (s.durationMs > 0) s.listenedMs.toFloat() / s.durationMs else 0f
        return s.listenedMs <= FAST_SKIP_MS || ratio < FAST_SKIP_RATIO
    }

    /** Must be called with [lock] held. */
    private fun countMeaningfulPlayLocked(s: Session) {
        if (s.meaningfulCounted) return
        val now = clock()
        val isReplay = lastMeaningfulSongId == s.songId &&
                (now - lastMeaningfulTimeMs) < REPLAY_WINDOW_MS
        s.meaningfulCounted = true
        s.persistedListenedMs = s.listenedMs
        lastMeaningfulSongId = s.songId
        lastMeaningfulTimeMs = now

        val snapshot = snapshotOf(s)
        scope.launch { sink.onMeaningfulPlay(snapshot, isReplay) }
    }

    /**
     * Must be called with [lock] held. Enqueues the end-of-session
     * persistence. The session's flags are updated synchronously so a
     * duplicate callback cannot double-count; only the sink call is async.
     */
    private fun enqueueSessionEnd(s: Session, completed: Boolean, treatAsSkip: Boolean) {
        val threshold = meaningfulThresholdMs(s.durationMs)
        val meaningful = completed || s.listenedMs >= threshold
        val snapshot = snapshotOf(s)

        if (meaningful && !s.meaningfulCounted) {
            countMeaningfulPlayLocked(s)
        } else if (s.listenedMs > s.persistedListenedMs) {
            val delta = s.listenedMs - s.persistedListenedMs
            s.persistedListenedMs = s.listenedMs
            scope.launch { sink.onListenedDelta(snapshot, delta) }
        }

        // A normal manual Next after meaningful listening is NOT a dislike.
        if (!meaningful && treatAsSkip && isFastSkip(s) && !isDuplicateFastSkip(s)) {
            lastFastSkipSongId = s.songId
            lastFastSkipAtMs = clock()
            scope.launch { sink.onFastSkip(snapshot) }
        }

        if (completed && !isDuplicateCompletion(s)) {
            lastCompletionSongId = s.songId
            lastCompletionAtMs = clock()
            scope.launch { sink.onCompletion(snapshot) }
        }
    }

    /** Same song skipped twice within a few ms is a duplicate callback. */
    private fun isDuplicateFastSkip(s: Session): Boolean {
        val now = clock()
        return s.songId == lastFastSkipSongId && now - lastFastSkipAtMs < DUPLICATE_EVENT_WINDOW_MS
    }

    private fun isDuplicateCompletion(s: Session): Boolean {
        val now = clock()
        return s.songId == lastCompletionSongId &&
                now - lastCompletionAtMs < DUPLICATE_EVENT_WINDOW_MS
    }

    /** Must be called with [lock] held. */
    private fun enqueueListenedDelta(s: Session) {
        val delta = (s.listenedMs - s.persistedListenedMs).coerceAtLeast(0L)
        if (delta <= 0L) return
        s.persistedListenedMs = s.listenedMs
        val snapshot = snapshotOf(s)
        scope.launch { sink.onListenedDelta(snapshot, delta) }
    }

    private fun snapshotOf(s: Session): SessionSnapshot = SessionSnapshot(
        songId = s.songId,
        durationMs = s.durationMs,
        startedAtMs = s.startedAtMs,
        listenedMs = s.listenedMs,
        listenedRatio = if (s.durationMs > 0) {
            (s.listenedMs.toFloat() / s.durationMs).coerceIn(0f, 1f)
        } else 0f
    )

    companion object {
        /** Progress ticks further apart than this are seeks. */
        const val PROGRESS_TICK_MAX_MS = 20_000L

        /** Listened time below this counts as a fast skip. */
        const val FAST_SKIP_MS = 15_000L

        /** ...or below this fraction of the track. */
        const val FAST_SKIP_RATIO = 0.12f

        /** A replay of the same song within this window is not a new opinion. */
        const val REPLAY_WINDOW_MS = 120_000L

        /** Tracks up to this length use the proportional threshold. */
        const val SHORT_TRACK_MAX_MS = 180_000L

        /** Proportional threshold share for short tracks. */
        const val SHORT_TRACK_RATIO = 0.2f

        /** Meaningful threshold for normal-length tracks. */
        const val MEANINGFUL_MS = 30_000L

        /** Terminal events for the same song within this window are duplicates. */
        const val DUPLICATE_EVENT_WINDOW_MS = 5_000L
    }
}
