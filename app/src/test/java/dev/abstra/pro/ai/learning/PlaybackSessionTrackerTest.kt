package dev.abstra.pro.ai.learning

import dev.abstra.pro.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the meaningful-listening semantics of [PlaybackSessionTracker]:
 * real listened time, threshold math, skip classification, replay handling,
 * duplicate callbacks and the Markov transition source.
 */
class PlaybackSessionTrackerTest {

    private companion object {
        /** Larger than the tracker's duplicate-event window. */
        const val DUPLICATE_GAP_MS = 10_000L
    }

    private class RecordingSink : PlaybackSessionTracker.Sink {
        val meaningful = mutableListOf<Pair<String, Boolean>>() // songId to isReplay
        val fastSkips = mutableListOf<String>()
        val completions = mutableListOf<String>()
        val listenedDeltas = mutableListOf<Pair<String, Long>>()
        val transitions = mutableListOf<Pair<String, String>>()

        override suspend fun onMeaningfulPlay(
            snapshot: PlaybackSessionTracker.SessionSnapshot,
            isReplay: Boolean
        ) {
            meaningful += snapshot.songId to isReplay
        }

        override suspend fun onFastSkip(snapshot: PlaybackSessionTracker.SessionSnapshot) {
            fastSkips += snapshot.songId
        }

        override suspend fun onCompletion(snapshot: PlaybackSessionTracker.SessionSnapshot) {
            completions += snapshot.songId
        }

        override suspend fun onListenedDelta(
            snapshot: PlaybackSessionTracker.SessionSnapshot,
            deltaMs: Long
        ) {
            listenedDeltas += snapshot.songId to deltaMs
        }

        override suspend fun onTransition(fromSongId: String, toSongId: String) {
            transitions += fromSongId to toSongId
        }
    }

    private var nowMs = 1_000_000L
    private val sink = RecordingSink()
    private val tracker = PlaybackSessionTracker(
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        sink = sink,
        clock = { nowMs }
    )

    private fun song(id: String, durationMs: Long = 200_000L) = Song(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        album = "Album",
        durationMs = durationMs,
        uriString = "content://$id",
        folderName = "f",
        filePath = "/f/$id.mp3",
        dateAdded = 0L
    )

    // ── Meaningful threshold ──

    @Test
    fun `meaningful threshold is 30s for normal tracks and proportional for short tracks`() {
        assertEquals(30_000L, tracker.meaningfulThresholdMs(200_000L))
        assertEquals(30_000L, tracker.meaningfulThresholdMs(180_001L))
        // Short track: 20% of duration, at least 8s.
        assertEquals(16_000L, tracker.meaningfulThresholdMs(80_000L))
        assertEquals(10_000L, tracker.meaningfulThresholdMs(50_000L))
        assertEquals(8_000L, tracker.meaningfulThresholdMs(20_000L))
        assertEquals(8_000L, tracker.meaningfulThresholdMs(5_000L))
    }

    @Test
    fun `a meaningful play produces exactly one positive event`() {
        tracker.onSongStarted(song("A"))
        // Advance in ~5s progress ticks to the 30s threshold.
        for (i in 1..7) tracker.onSongProgress(song("A"), i * 5_000L, 200_000L)
        // 35s listened — threshold (30s) crossed exactly once.
        assertEquals(listOf("A" to false), sink.meaningful)
        // Duplicate progress callbacks must not double-count.
        tracker.onSongProgress(song("A"), 35_000L, 200_000L)
        tracker.onSongProgress(song("A"), 36_000L, 200_000L)
        assertEquals(1, sink.meaningful.size)
    }

    @Test
    fun `seeks do not count as listening time`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("A"), 5_000L, 200_000L) // 5s listened
        tracker.onSongProgress(song("A"), 150_000L, 200_000L) // seek jump — ignored
        tracker.onSongSkipped(song("A"), playedSeconds = 150, totalDurationSeconds = 200)
        // Only 5s listened: a fast skip, but no meaningful play and no
        // fabricated 150s of listening.
        assertTrue(sink.meaningful.isEmpty())
        assertEquals(listOf("A"), sink.fastSkips)
        val fastDelta = sink.listenedDeltas.filter { it.first == "A" }.sumOf { it.second }
        assertTrue("listened time must be ~5s but was $fastDelta", fastDelta <= 5_000L)
    }

    @Test
    fun `natural completion produces stronger evidence than a meaningful play`() {
        tracker.onSongStarted(song("A", durationMs = 200_000L))
        tracker.onSongProgress(song("A"), 100_000L, 200_000L) // meaningful at 30s
        tracker.onSongCompleted(song("A"))
        assertEquals(listOf("A" to false), sink.meaningful)
        assertEquals(listOf("A"), sink.completions)
        assertTrue(sink.fastSkips.isEmpty())
    }

    @Test
    fun `completion without a live session still records the completion`() {
        tracker.onSongCompleted(song("A"))
        assertEquals(listOf("A"), sink.completions)
        assertTrue(sink.meaningful.isEmpty())
    }

    // ── Skip semantics ──

    @Test
    fun `a very early skip produces negative evidence`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("A"), 3_000L, 200_000L)
        tracker.onSongSkipped(song("A"), playedSeconds = 3, totalDurationSeconds = 200)
        assertEquals(listOf("A"), sink.fastSkips)
        assertTrue(sink.meaningful.isEmpty())
    }

    @Test
    fun `manual Next after meaningful listening is not a fast skip`() {
        tracker.onSongStarted(song("A"))
        // Realistic progress ticks (~5s apart) up to 60s of listening.
        for (i in 1..12) tracker.onSongProgress(song("A"), i * 5_000L, 200_000L)
        tracker.onSongSkipped(song("A"), playedSeconds = 60, totalDurationSeconds = 200)
        assertTrue("manual next after 60s must not be a dislike", sink.fastSkips.isEmpty())
        assertEquals(listOf("A" to false), sink.meaningful)
    }

    @Test
    fun `pause is never negative feedback and persists listened time`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("A"), 10_000L, 200_000L)
        tracker.onPlaybackPaused(song("A"))
        assertTrue(sink.fastSkips.isEmpty())
        assertTrue(sink.meaningful.isEmpty())
        assertEquals(listOf("A" to 10_000L), sink.listenedDeltas)
    }

    @Test
    fun `pause after reaching the threshold counts as meaningful`() {
        tracker.onSongStarted(song("A"))
        for (i in 1..7) tracker.onSongProgress(song("A"), i * 5_000L, 200_000L)
        tracker.onPlaybackPaused(song("A"))
        assertEquals(listOf("A" to false), sink.meaningful)
        assertTrue(sink.fastSkips.isEmpty())
    }

    @Test
    fun `a repeated skip produces more negative events than one skip`() {
        for (n in 1..3) {
            nowMs += DUPLICATE_GAP_MS // separate the skips beyond the dedup window
            tracker.onSongStarted(song("A"))
            tracker.onSongProgress(song("A"), 2_000L, 200_000L)
            tracker.onSongSkipped(song("A"), playedSeconds = 2, totalDurationSeconds = 200)
        }
        assertEquals(3, sink.fastSkips.size)
    }

    // ── Replay ──

    @Test
    fun `replaying within the window is recorded as a replay`() {
        tracker.onSongStarted(song("A"))
        for (i in 1..7) tracker.onSongProgress(song("A"), i * 5_000L, 200_000L)
        nowMs += 60_000L // within the 2-minute replay window
        tracker.onSongStarted(song("A"))
        for (i in 1..7) tracker.onSongProgress(song("A"), i * 5_000L, 200_000L)
        assertEquals(2, sink.meaningful.size)
        assertEquals("A" to false, sink.meaningful[0])
        assertEquals("A" to true, sink.meaningful[1])
    }

    // ── Markov transitions ──

    @Test
    fun `A-B-C produces exactly A-B and B-C`() {
        // A is meaningfully listened to, then B starts: A -> B.
        tracker.onSongStarted(song("A"))
        for (i in 1..7) tracker.onSongProgress(song("A"), i * 5_000L, 200_000L)
        tracker.onSongStarted(song("B"))
        // B is meaningfully listened to, then C starts: B -> C.
        for (i in 1..7) tracker.onSongProgress(song("B"), i * 5_000L, 200_000L)
        tracker.onSongStarted(song("C"))

        assertEquals(
            listOf("A" to "B", "B" to "C"),
            sink.transitions
        )
        assertFalse(sink.transitions.any { it.first == "A" && it.second == "C" })
    }

    @Test
    fun `no transition is recorded from an unmeaningful playback start`() {
        // A starts but is skipped immediately — never meaningful.
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("A"), 2_000L, 200_000L)
        tracker.onSongStarted(song("B"))
        for (i in 1..7) tracker.onSongProgress(song("B"), i * 5_000L, 200_000L)
        tracker.onSongStarted(song("C"))
        // B was meaningful, A was not: only B -> C may exist.
        assertEquals(listOf("B" to "C"), sink.transitions)
    }

    @Test
    fun `rapid track changes without meaningful listening record no transitions`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongStarted(song("B"))
        tracker.onSongStarted(song("C"))
        assertTrue(sink.transitions.isEmpty())
    }

    @Test
    fun `no self transition is recorded for an immediate repeat`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("A"), 35_000L, 200_000L)
        tracker.onSongStarted(song("A"))
        assertTrue(sink.transitions.isEmpty())
    }

    // ── Duplicate / interleaved callbacks ──

    @Test
    fun `duplicate skip callback after a completed session does not double count`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("A"), 3_000L, 200_000L)
        tracker.onSongSkipped(song("A"), playedSeconds = 3, totalDurationSeconds = 200)
        tracker.onSongSkipped(song("A"), playedSeconds = 3, totalDurationSeconds = 200)
        assertEquals(1, sink.fastSkips.size)
    }

    @Test
    fun `progress for another song does not affect the active session`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("B"), 120_000L, 200_000L)
        // B's progress must not corrupt A's listened time.
        assertEquals(0L, tracker.currentSnapshot()?.listenedMs)
        tracker.onSongProgress(song("A"), 5_000L, 200_000L)
        assertEquals(5_000L, tracker.currentSnapshot()?.listenedMs)
    }

    @Test
    fun `listened time accumulates across pause and resume`() {
        tracker.onSongStarted(song("A"))
        tracker.onSongProgress(song("A"), 10_000L, 200_000L)
        tracker.onPlaybackPaused(song("A")) // persists 10s
        tracker.onSongProgress(song("A"), 20_000L, 200_000L) // resumes to 20s
        tracker.onSongCompleted(song("A")) // remaining ~180s counts
        assertEquals(listOf("A"), sink.completions)
        // The tracker's final snapshot reported to the sink covers everything.
        assertTrue(sink.meaningful.isNotEmpty())
    }
}
