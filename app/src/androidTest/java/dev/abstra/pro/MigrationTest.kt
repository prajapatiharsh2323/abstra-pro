package dev.abstra.pro

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.abstra.pro.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the Room v4 -> v5 upgrade path (song_profiles table + songs
 * fileSize/lastModified columns + app_settings normalization) and the
 * v5 -> v6 upgrade path (V2 recommendation tables: audio signatures, song
 * graph, playback feedback) without destroying user data.
 *
 * Uses the hand-crafted exported schema 4.json in app/schemas (v4 had
 * exportSchema disabled, so it is reproduced from the v4 entities) and the
 * 5.json / 6.json that KSP generates automatically on build.
 */
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate4To5_preservesSongsAndCreatesProfiles() {
        val dbName = "migration_test_4_5"

        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL(
                "INSERT INTO songs (id, title, artist, album, durationMs, uriString, " +
                        "folderName, filePath, dateAdded, genre, albumId, isFavorite, lyrics) " +
                        "VALUES ('42', 'Title', 'Artist', 'Album', 180000, 'content://x/42', " +
                        "'Music', '/storage/42.mp3', 1000, NULL, 7, 0, NULL)"
            )
        }

        helper.runMigrationsAndValidate(dbName, 5, true, dev.abstra.pro.data.db.AppDatabase.MIGRATION_4_5)
            .use { db ->
                // Pre-existing data survived the upgrade.
                db.query("SELECT id, title, fileSize, lastModified FROM songs WHERE id = '42'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("42", cursor.getString(0))
                    assertEquals("Title", cursor.getString(1))
                    assertEquals(0L, cursor.getLong(2)) // default fileSize
                    assertEquals(0L, cursor.getLong(3)) // default lastModified
                }

                // New profiles table is usable.
                db.execSQL(
                    "INSERT INTO song_profiles (songId, energy, acousticness, instrumentalLikelihood, " +
                            "rhythmActivity, tags, energyConfidence, acousticConfidence, " +
                            "instrumentalConfidence, profileSource, metadataFingerprint, " +
                            "analysisVersion, audioAnalyzedAt, updatedAt) " +
                            "VALUES ('42', 0.5, 0.3, 0.1, NULL, '', 0.5, 0.3, 0.1, 'METADATA', 'fp', 1, NULL, 1)"
                )
                db.query("SELECT COUNT(*) FROM song_profiles WHERE songId = '42'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
    }

    /**
     * v5 -> v6 adds the V2 recommendation tables and must leave every
     * existing user table (songs, overrides, playlists, telemetry,
     * transitions, profiles, settings, lyrics) untouched.
     */
    @Test
    fun migrate5To6_preservesUserDataAndCreatesRecommendationTables() {
        val dbName = "migration_test_5_6"

        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL(
                "INSERT INTO songs (id, title, artist, album, durationMs, uriString, " +
                        "folderName, filePath, dateAdded, genre, albumId, isFavorite, lyrics, " +
                        "fileSize, lastModified) " +
                        "VALUES ('7', 'Keep', 'Artist', 'Album', 240000, 'content://x/7', " +
                        "'Music', '/storage/7.mp3', 1000, 'Rock', 3, 1, NULL, 512, 999)"
            )
            db.execSQL(
                "INSERT INTO song_profiles (songId, energy, acousticness, instrumentalLikelihood, " +
                        "rhythmActivity, tags, energyConfidence, acousticConfidence, " +
                        "instrumentalConfidence, profileSource, metadataFingerprint, " +
                        "analysisVersion, audioAnalyzedAt, updatedAt) " +
                        "VALUES ('7', 0.6, 0.2, 0.1, 0.4, '', 0.8, 0.5, 0.2, 'METADATA', 'fp7', 2, NULL, 5)"
            )
        }

        helper.runMigrationsAndValidate(dbName, 6, true, dev.abstra.pro.data.db.AppDatabase.MIGRATION_5_6)
            .use { db ->
                // Pre-existing user data survived the upgrade.
                db.query("SELECT id, title, isFavorite, fileSize, lastModified FROM songs WHERE id = '7'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("7", cursor.getString(0))
                        assertEquals("Keep", cursor.getString(1))
                        assertEquals(1, cursor.getInt(2))
                        assertEquals(512L, cursor.getLong(3))
                        assertEquals(999L, cursor.getLong(4))
                    }
                db.query("SELECT energy, metadataFingerprint FROM song_profiles WHERE songId = '7'")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0.6f, cursor.getFloat(0), 1e-4f)
                        assertEquals("fp7", cursor.getString(1))
                    }

                // New V2 tables are usable and enforced.
                db.execSQL(
                    "INSERT INTO audio_signatures (songId, mfccMeanBlob, mfccStdBlob, chromaBlob, " +
                            "chromaConfidence, tempoBpm, tempoConfidence, rmsEnergy, dynamicRange, " +
                            "spectralCentroid, spectralFlux, onsetDensity, rhythmActivity, " +
                            "featureConfidence, extractorVersion, metadataFingerprint, " +
                            "analyzedAtMs, analysisStatus) " +
                            "VALUES ('7', x'00003c42', x'0000c83c', x'3b83c0ca3c83c0ca', " +
                            "0.9, 120.0, 0.9, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.9, 2, 'fp7', 1, 'SUCCESS')"
                )
                db.execSQL(
                    "INSERT INTO song_neighbors (songId, neighborSongId, edgeType, similarity, " +
                            "graphVersion, updatedAtMs) " +
                            "VALUES ('7', '8', 'AUDIO', 0.85, 1, 1)"
                )
                db.execSQL(
                    "INSERT INTO playback_feedback (songId, eventType, occurredAtMs, listenedMs, durationMs) " +
                            "VALUES ('7', 'COMPLETION', 1, 240000, 240000)"
                )

                db.query("SELECT COUNT(*) FROM audio_signatures WHERE songId = '7'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                db.query("SELECT similarity, edgeType FROM song_neighbors WHERE songId = '7'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0.85f, cursor.getFloat(0), 1e-4f)
                    assertEquals("AUDIO", cursor.getString(1))
                }
                db.query("SELECT eventType, id FROM playback_feedback WHERE songId = '7'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("COMPLETION", cursor.getString(0))
                    // Auto-increment primary key was assigned.
                    assertTrue(cursor.getLong(1) > 0)
                }
            }
    }
}
