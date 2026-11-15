package dev.abstra.pro

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the Room v4 -> v5 upgrade path (song_profiles table + songs
 * fileSize/lastModified columns + app_settings normalization) without
 * destroying user data.
 *
 * Uses the hand-crafted exported schema 4.json in app/schemas (v4 had
 * exportSchema disabled, so it is reproduced from the v4 entities) and the
 * 5.json that KSP generates automatically on build.
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
}
