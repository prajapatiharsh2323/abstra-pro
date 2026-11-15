package dev.abstra.pro.ai

import dev.abstra.pro.ai.model.PlaylistRequest
import dev.abstra.pro.ai.model.RecommendationReason
import dev.abstra.pro.ai.model.SongProfile
import dev.abstra.pro.data.db.entities.PlaybackTelemetryEntity
import dev.abstra.pro.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSequencerTest {

    private val preset = MixPresetCatalog.byId(MixPresetCatalog.PRESET_DAILY_FLOW)
    private val request = PlaylistRequest(
        mood = preset.id,
        targetDurationMs = 60L * 60L * 1000L,
        discoveryRatio = 0.2f,
        refreshSeed = 42L
    )

    private fun song(id: String, artist: String) = Song(
        id = id,
        title = "Song $id",
        artist = artist,
        album = "Album $artist",
        durationMs = 180_000L,
        uriString = "content://test/$id",
        folderName = "Music",
        filePath = "/storage/music/$id.mp3",
        dateAdded = 0L
    )

    private fun scored(songs: List<Song>): List<ScoredSong> {
        val context = ScoringContext(
            nowMs = 1_700_000_000_000L,
            timeOfDay = dev.abstra.pro.ai.model.TimeOfDay.AFTERNOON,
            isWeekend = false
        )
        return songs.map { s ->
            val profile = SongProfile(
                songId = s.id,
                energy = 0.55f,
                acousticness = 0.3f,
                instrumentalLikelihood = 0.1f,
                energyConfidence = 0.7f,
                metadataFingerprint = "fp",
                analysisVersion = 1
            )
            val base = PlaylistScorer.scoreBase(
                song = s,
                profile = profile,
                telemetry = PlaybackTelemetryEntity(songId = s.id),
                context = context
            )
            PlaylistScorer.score(base, preset)
        }
    }

    private fun sequence(
        scored: List<ScoredSong>,
        recentlyPlayed: Set<String> = emptySet(),
        seedSongId: String? = null
    ): List<dev.abstra.pro.ai.model.RecommendedTrack> {
        val input = SequenceInput(
            scored = scored,
            preset = preset,
            request = request.copy(seedSongId = seedSongId),
            nowMs = 1_700_000_000_000L,
            recentlyPlayedIds = recentlyPlayed
        )
        return PlaylistSequencer.sequence(input)
    }

    @Test
    fun `empty input produces empty playlist`() {
        assertTrue(sequence(scored = emptyList()).isEmpty())
    }

    @Test
    fun `no two consecutive tracks share an artist`() {
        val songs = (1..30).flatMap { i ->
            listOf(
                song("a$i", "Artist A"),
                song("b$i", "Artist B"),
                song("c$i", "Artist C")
            )
        }
        val tracks = sequence(scored(songs))

        assertTrue(tracks.size > 10)
        val artistsById = songs.associate { it.id to it.artist }
        for (i in 1 until tracks.size) {
            val prev = artistsById.getValue(tracks[i - 1].songId)
            val cur = artistsById.getValue(tracks[i].songId)
            assertTrue("consecutive same artist at $i", prev != cur)
        }
    }

    @Test
    fun `respects max track count`() {
        val songs = (1..80).map { song("s$it", "Artist ${it % 20}") }
        val tracks = sequence(scored(songs))

        assertTrue(tracks.size <= preset.maxTracks)
    }

    @Test
    fun `excludes recently played when alternatives exist`() {
        val songs = (1..40).map { song("s$it", "Artist ${it % 10}") }
        val tracks = sequence(scored(songs), recentlyPlayed = setOf("s1", "s2"))

        assertTrue(tracks.none { it.songId == "s1" || it.songId == "s2" })
    }

    @Test
    fun `seed song starts the playlist`() {
        val songs = (1..30).map { song("s$it", "Artist ${it % 8}") }
        val tracks = sequence(scored(songs), seedSongId = "s5")

        assertEquals("s5", tracks.first().songId)
    }

    @Test
    fun `generation is deterministic for the same seed`() {
        val songs = (1..40).map { song("s$it", "Artist ${it % 10}") }
        val a = sequence(scored(songs)).map { it.songId }
        val b = sequence(scored(songs)).map { it.songId }

        assertEquals(a, b)
    }

    @Test
    fun `unplayed tracks get fresh discovery reason`() {
        val songs = (1..20).map { song("s$it", "Artist $it") }
        val tracks = sequence(scored(songs))

        assertTrue(tracks.any { RecommendationReason.FRESH_DISCOVERY in it.reasons })
    }
}
