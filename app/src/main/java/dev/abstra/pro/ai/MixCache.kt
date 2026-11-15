package dev.abstra.pro.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** A cached generated mix: preset id plus the ordered song ids and reasons. */
data class CachedPlaylist(
    val presetId: String,
    val songIds: List<String>,
    val reasons: Map<String, List<String>> = emptyMap(),
    val generatedAtMs: Long = 0L,
    val refreshSeed: Long = 0L,
    val totalDurationMs: Long = 0L
)

/** Persisted daily mix cache. Generation is reproducible via date + seed. */
data class MixCacheData(
    val dateKey: String,
    val libraryFingerprint: String,
    /** Base seed shared by all presets of one daily generation. */
    val baseSeed: Long = 0L,
    val playlists: List<CachedPlaylist> = emptyList()
)

/**
 * Caches generated daily mixes so they do not change every time the user
 * opens the screen. Stored as compact JSON in SharedPreferences; profiles
 * and mixes are derived data, so they are not part of the SAF backup.
 */
class MixCache(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<MixCacheData>() {}.type

    fun load(): MixCacheData? = runCatching {
        prefs.getString(KEY_CACHE, null)?.let { gson.fromJson<MixCacheData>(it, type) }
    }.getOrNull()

    fun save(data: MixCacheData) {
        runCatching {
            prefs.edit().putString(KEY_CACHE, gson.toJson(data)).apply()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_CACHE).apply()
    }

    companion object {
        private const val PREFS_NAME = "abstra_prefs"
        private const val KEY_CACHE = "ai_mix_cache_v1"
    }
}
