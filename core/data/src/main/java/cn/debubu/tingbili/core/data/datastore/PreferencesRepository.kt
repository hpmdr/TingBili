package cn.debubu.tingbili.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class PreferencesRepository(private val ds: DataStore<Preferences>) {

    val stepSec: Flow<Int> = ds.data.map { it[STEP_SEC] ?: 15 }
    val repeatMode: Flow<Int> = ds.data.map { it[REPEAT_MODE] ?: 0 }
    val speed: Flow<Float> = ds.data.map { it[SPEED] ?: 1f }
    val timerPresets: Flow<Set<Int>> = ds.data.map { prefs ->
        prefs[TIMER_PRESETS]?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(15, 30, 60, 90)
    }

    val themeMode: Flow<ThemeMode> = ds.data.map { prefs ->
        prefs[THEME_MODE]?.let(ThemeMode::fromKey)
            ?: if (prefs[DYNAMIC_COLOR] == true) ThemeMode.SYSTEM else ThemeMode.DEFAULT
    }
    val customThemeColor: Flow<Int> = ds.data.map { it[CUSTOM_THEME_COLOR] ?: DEFAULT_THEME_COLOR }
    val dynamicColor: Flow<Boolean> = themeMode.map { it == ThemeMode.SYSTEM }
    val imageFormat: Flow<ImageFormat> = ds.data.map { ImageFormat.fromKey(it[IMAGE_FORMAT]) }
    val cacheMaxMb: Flow<Int> = ds.data.map { (it[CACHE_MAX_MB] ?: DEFAULT_CACHE_MAX_MB).coerceIn(MIN_CACHE_MAX_MB, MAX_CACHE_MAX_MB) }

    // 上次播放恢复：队列（JSON）+ 下标 + 进度
    val lastQueue: Flow<List<Track>> = ds.data.map { prefs ->
        prefs[LAST_QUEUE_JSON]?.let { json ->
            try {
                Json.decodeFromString(ListSerializer(Track.serializer()), json)
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }
    val lastIndex: Flow<Int> = ds.data.map { it[LAST_INDEX] ?: -1 }
    val lastPositionMs: Flow<Long> = ds.data.map { it[LAST_POSITION_MS] ?: 0L }
    val lastSourceTitle: Flow<String?> = ds.data.map { it[LAST_SOURCE_TITLE] }

    suspend fun setStep(v: Int) {
        ds.edit { it[STEP_SEC] = v }
    }

    suspend fun setRepeatMode(v: Int) {
        ds.edit { it[REPEAT_MODE] = v }
    }

    suspend fun setSpeed(v: Float) {
        ds.edit { it[SPEED] = v }
    }

    suspend fun setTimerPresets(presets: Set<Int>) {
        ds.edit { it[TIMER_PRESETS] = presets.joinToString(",") }
    }

    suspend fun setDynamicColor(v: Boolean) {
        setThemeMode(if (v) ThemeMode.SYSTEM else ThemeMode.DEFAULT)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        ds.edit { it[THEME_MODE] = mode.key }
    }

    suspend fun setCustomThemeColor(color: Int) {
        ds.edit {
            it[CUSTOM_THEME_COLOR] = color
            it[THEME_MODE] = ThemeMode.CUSTOM.key
        }
    }

    suspend fun setImageFormat(v: ImageFormat) {
        ds.edit { it[IMAGE_FORMAT] = v.name }
    }

    suspend fun setCacheMaxMb(v: Int) {
        ds.edit { it[CACHE_MAX_MB] = v.coerceIn(MIN_CACHE_MAX_MB, MAX_CACHE_MAX_MB) }
    }

    /** 持久化上次播放的队列与进度，队列超 100 首截断以控大小 */
    suspend fun setLastPlayback(
        queue: List<Track>,
        index: Int,
        positionMs: Long,
        sourceTitle: String? = null,
    ) {
        val capped = if (queue.size > 100) queue.take(100) else queue
        val json = Json.encodeToString(ListSerializer(Track.serializer()), capped)
        ds.edit {
            it[LAST_QUEUE_JSON] = json
            it[LAST_INDEX] = index.coerceIn(-1, capped.lastIndex.coerceAtLeast(-1))
            it[LAST_POSITION_MS] = positionMs.coerceAtLeast(0L)
            if (sourceTitle.isNullOrBlank()) {
                it.remove(LAST_SOURCE_TITLE)
            } else {
                it[LAST_SOURCE_TITLE] = sourceTitle
            }
        }
    }

    suspend fun setLastPosition(positionMs: Long) {
        ds.edit { it[LAST_POSITION_MS] = positionMs.coerceAtLeast(0L) }
    }

    suspend fun clearLastPlayback() {
        ds.edit {
            it.remove(LAST_QUEUE_JSON)
            it.remove(LAST_INDEX)
            it.remove(LAST_POSITION_MS)
            it.remove(LAST_SOURCE_TITLE)
        }
    }

    companion object {
        val STEP_SEC = intPreferencesKey("step_sec")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val SPEED = floatPreferencesKey("speed")
        val TIMER_PRESETS = stringPreferencesKey("timer_presets")
        val DYNAMIC_COLOR = androidx.datastore.preferences.core.booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CUSTOM_THEME_COLOR = intPreferencesKey("custom_theme_color")
        val DEFAULT_THEME_COLOR: Int = 0xFFFF6699.toInt()
        val IMAGE_FORMAT = stringPreferencesKey("image_format")
        val CACHE_MAX_MB = intPreferencesKey("cache_max_mb")
        const val DEFAULT_CACHE_MAX_MB = 500
        const val MIN_CACHE_MAX_MB = 100
        const val MAX_CACHE_MAX_MB = 2048
        val LAST_QUEUE_JSON = stringPreferencesKey("last_queue_json")
        val LAST_INDEX = intPreferencesKey("last_index")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val LAST_SOURCE_TITLE = stringPreferencesKey("last_source_title")
    }
}
