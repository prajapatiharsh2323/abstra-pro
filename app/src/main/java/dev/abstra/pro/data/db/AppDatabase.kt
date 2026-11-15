package dev.abstra.pro.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.abstra.pro.data.db.dao.LibraryDao
import dev.abstra.pro.data.db.dao.PlaylistDao
import dev.abstra.pro.data.db.dao.SettingsDao
import dev.abstra.pro.data.db.dao.SongProfileDao
import dev.abstra.pro.data.db.dao.TelemetryDao
import dev.abstra.pro.data.db.entities.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        SongOverrideEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlaybackTelemetryEntity::class,
        MarkovTransitionEntity::class,
        EffectSettingsEntity::class,
        CustomEqPresetEntity::class,
        LyricsCacheEntity::class,
        WidgetPrefsEntity::class,
        SettingsEntity::class,
        SongProfileEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun songProfileDao(): SongProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase = getDatabase(context)

        /** Exposed for instrumentation migration tests. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN selectedFont TEXT NOT NULL DEFAULT 'SYSTEM'")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN greetingStyle TEXT NOT NULL DEFAULT 'CLASSIC'")
            }
        }

        /** Exposed for instrumentation migration tests. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS song_profiles (" +
                            "songId TEXT NOT NULL PRIMARY KEY, " +
                            "energy REAL NOT NULL, " +
                            "acousticness REAL NOT NULL, " +
                            "instrumentalLikelihood REAL NOT NULL, " +
                            "rhythmActivity REAL, " +
                            "tags TEXT NOT NULL, " +
                            "energyConfidence REAL NOT NULL, " +
                            "acousticConfidence REAL NOT NULL, " +
                            "instrumentalConfidence REAL NOT NULL, " +
                            "profileSource TEXT NOT NULL, " +
                            "metadataFingerprint TEXT NOT NULL, " +
                            "analysisVersion INTEGER NOT NULL, " +
                            "audioAnalyzedAt INTEGER, " +
                            "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE songs ADD COLUMN fileSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN lastModified INTEGER NOT NULL DEFAULT 0")

                // v3->v4 added selectedFont/greetingStyle with SQL DEFAULTs that
                // Room's schema does not declare. Rebuild app_settings without
                // them so every v5 database has one canonical shape.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS app_settings_rebuild (" +
                            "id INTEGER NOT NULL PRIMARY KEY, " +
                            "userName TEXT, " +
                            "greetingEnabled INTEGER NOT NULL, " +
                            "hasAskedForName INTEGER NOT NULL, " +
                            "excludedFolderPaths TEXT NOT NULL, " +
                            "themeMode TEXT NOT NULL, " +
                            "accentColor TEXT NOT NULL, " +
                            "crossfadeSeconds INTEGER NOT NULL, " +
                            "gaplessEnabled INTEGER NOT NULL, " +
                            "defaultSleepTimerMin INTEGER NOT NULL, " +
                            "keepScreenOnInPlayer INTEGER NOT NULL, " +
                            "sortOption TEXT NOT NULL, " +
                            "showQueueOnSongClick INTEGER NOT NULL, " +
                            "playlistOrder TEXT NOT NULL, " +
                            "selectedFont TEXT NOT NULL, " +
                            "greetingStyle TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO app_settings_rebuild " +
                            "(id, userName, greetingEnabled, hasAskedForName, excludedFolderPaths, " +
                            "themeMode, accentColor, crossfadeSeconds, gaplessEnabled, " +
                            "defaultSleepTimerMin, keepScreenOnInPlayer, sortOption, " +
                            "showQueueOnSongClick, playlistOrder, selectedFont, greetingStyle) " +
                            "SELECT id, userName, greetingEnabled, hasAskedForName, excludedFolderPaths, " +
                            "themeMode, accentColor, crossfadeSeconds, gaplessEnabled, " +
                            "defaultSleepTimerMin, keepScreenOnInPlayer, sortOption, " +
                            "showQueueOnSongClick, playlistOrder, " +
                            "COALESCE(selectedFont, 'SYSTEM'), COALESCE(greetingStyle, 'CLASSIC') " +
                            "FROM app_settings"
                )
                db.execSQL("DROP TABLE app_settings")
                db.execSQL("ALTER TABLE app_settings_rebuild RENAME TO app_settings")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "abstra_pro.db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                // Ancient, unsupported schema versions are rebuilt instead of
                // crashing; every supported version (3, 4) upgrades in place.
                .fallbackToDestructiveMigrationFrom(1, 2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
