package cn.debubu.tingbili.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.debubu.tingbili.core.data.model.DarkMode
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** playurl 直链持久缓存条目：B 站直链约 2h 过期，TTL 到即视为失效 */
@Serializable
data class CachedPlayUrl(
    val url: String,
    val fetchedAtMs: Long = 0L,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        url.isNotBlank() && fetchedAtMs > 0L && nowMs - fetchedAtMs < PLAYURL_CACHE_TTL_MS

    companion object {
        /** 与 PlayerManager.PROACTIVE_REFRESH_MS 对齐：提前半小时失效，留足余量 */
        const val PLAYURL_CACHE_TTL_MS = 90 * 60 * 1000L
    }
}

class PreferencesRepository(private val ds: DataStore<Preferences>) {

    val stepSec: Flow<Int> = ds.data.map { it[STEP_SEC] ?: 15 }
    val repeatMode: Flow<Int> = ds.data.map { it[REPEAT_MODE] ?: 0 }
    val speed: Flow<Float> = ds.data.map { it[SPEED] ?: 1f }
    val sleepFinishChapter: Flow<Boolean> = ds.data.map { it[SLEEP_FINISH_CHAPTER] ?: false }
    /** 睡眠定时滚轮上次所选分钟（默认 30，启动定时即记住） */
    val sleepLastMinutes: Flow<Int> = ds.data.map { it[SLEEP_LAST_MINUTES] ?: 30 }

    val themeMode: Flow<ThemeMode> = ds.data.map { prefs ->
        prefs[THEME_MODE]?.let(ThemeMode::fromKey)
            ?: if (prefs[DYNAMIC_COLOR] == true) ThemeMode.SYSTEM else ThemeMode.DEFAULT
    }
    val darkMode: Flow<DarkMode> = ds.data.map { DarkMode.fromKey(it[DARK_MODE]) }
    val customThemeColor: Flow<Int> = ds.data.map { it[CUSTOM_THEME_COLOR] ?: DEFAULT_THEME_COLOR }
    val dynamicColor: Flow<Boolean> = themeMode.map { it == ThemeMode.SYSTEM }
    val imageFormat: Flow<ImageFormat> = ds.data.map { ImageFormat.fromKey(it[IMAGE_FORMAT]) }
    /** 首页发现流策展关键词，设置页可增减；空时回落默认 */
    // 注意：ds.data 任何 key 写入都会发射（播放时进度每 500ms 写一次），
    // 必须去重，否则下游 combine 会反复重建推荐 Pager（首页无限刷新）。
    val homeKeywords: Flow<List<String>> = ds.data.map { prefs ->
        prefs[HOME_KEYWORDS]?.split(",")?.map { it.trim() }
            ?.filter { it.isNotBlank() }?.ifEmpty { null } ?: DEFAULT_HOME_KEYWORDS
    }.distinctUntilChanged()
    /** 搜索历史（本地，最近在前、去重、上限），搜索页空态展示 */
    val searchHistory: Flow<List<String>> = ds.data.map { prefs ->
        prefs[SEARCH_HISTORY_JSON]?.let { json ->
            try {
                Json.decodeFromString(ListSerializer(String.serializer()), json)
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }
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

    suspend fun setSleepFinishChapter(v: Boolean) {
        ds.edit { it[SLEEP_FINISH_CHAPTER] = v }
    }

    suspend fun setSleepLastMinutes(min: Int) {
        ds.edit { it[SLEEP_LAST_MINUTES] = min.coerceIn(30, 600) }
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

    suspend fun setDarkMode(mode: DarkMode) {
        ds.edit { it[DARK_MODE] = mode.key }
    }

    suspend fun setImageFormat(v: ImageFormat) {
        ds.edit { it[IMAGE_FORMAT] = v.name }
    }

    suspend fun setHomeKeywords(keywords: List<String>) {
        val cleaned = keywords.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        ds.edit {
            if (cleaned.isEmpty()) it.remove(HOME_KEYWORDS) else it[HOME_KEYWORDS] = cleaned.joinToString(",")
        }
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

    /** playurl 缓存：key="$bvid:$cid"，冷启动/切集先读这里，命中即秒开 */
    val playUrlCache: Flow<Map<String, CachedPlayUrl>> = ds.data.map { prefs ->
        prefs[PLAYURL_CACHE_JSON]?.let { json ->
            try {
                Json.decodeFromString(MapSerializer(String.serializer(), CachedPlayUrl.serializer()), json)
            } catch (_: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    }

    /** 合并写入并淘汰：去过期、超上限去最旧，单次 edit 完成 */
    suspend fun putPlayUrls(entries: Map<String, CachedPlayUrl>, nowMs: Long = System.currentTimeMillis()) {
        if (entries.isEmpty()) return
        ds.edit { prefs ->
            val cur = (prefs[PLAYURL_CACHE_JSON]?.let { json ->
                try {
                    Json.decodeFromString(MapSerializer(String.serializer(), CachedPlayUrl.serializer()), json)
                } catch (_: Exception) {
                    emptyMap()
                }
            } ?: emptyMap()).toMutableMap()
            cur.putAll(entries)
            val cutoff = nowMs - CachedPlayUrl.PLAYURL_CACHE_TTL_MS
            val pruned = cur.filterValues { it.fetchedAtMs >= cutoff }
                .entries.sortedBy { it.value.fetchedAtMs }.takeLast(MAX_PLAYURL_CACHE)
                .associate { it.key to it.value }
            prefs[PLAYURL_CACHE_JSON] = Json.encodeToString(
                MapSerializer(String.serializer(), CachedPlayUrl.serializer()), pruned
            )
        }
    }

    suspend fun clearLastPlayback() {
        ds.edit {
            it.remove(LAST_QUEUE_JSON)
            it.remove(LAST_INDEX)
            it.remove(LAST_POSITION_MS)
            it.remove(LAST_SOURCE_TITLE)
        }
    }

    suspend fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        ds.edit {
            val cur = it[SEARCH_HISTORY_JSON]?.let { json ->
                try {
                    Json.decodeFromString(ListSerializer(String.serializer()), json)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()
            val next = (listOf(q) + cur.filter { h -> h != q }).take(MAX_SEARCH_HISTORY)
            it[SEARCH_HISTORY_JSON] = Json.encodeToString(ListSerializer(String.serializer()), next)
        }
    }

    suspend fun removeSearchHistory(query: String) {
        ds.edit {
            val cur = it[SEARCH_HISTORY_JSON]?.let { json ->
                try {
                    Json.decodeFromString(ListSerializer(String.serializer()), json)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()
            val next = cur.filter { h -> h != query }
            if (next.isEmpty()) it.remove(SEARCH_HISTORY_JSON)
            else it[SEARCH_HISTORY_JSON] = Json.encodeToString(ListSerializer(String.serializer()), next)
        }
    }

    suspend fun clearSearchHistory() {
        ds.edit { it.remove(SEARCH_HISTORY_JSON) }
    }

    companion object {
        val STEP_SEC = intPreferencesKey("step_sec")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val SPEED = floatPreferencesKey("speed")
        val SLEEP_FINISH_CHAPTER = androidx.datastore.preferences.core.booleanPreferencesKey("sleep_finish_chapter")
        val SLEEP_LAST_MINUTES = androidx.datastore.preferences.core.intPreferencesKey("sleep_last_minutes")
        val DYNAMIC_COLOR = androidx.datastore.preferences.core.booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CUSTOM_THEME_COLOR = intPreferencesKey("custom_theme_color")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val DEFAULT_THEME_COLOR: Int = 0xFFFF6699.toInt()
        val IMAGE_FORMAT = stringPreferencesKey("image_format")
        val HOME_KEYWORDS = stringPreferencesKey("home_keywords")
        val SEARCH_HISTORY_JSON = stringPreferencesKey("search_history_json")
        const val MAX_SEARCH_HISTORY = 15
        val CACHE_MAX_MB = intPreferencesKey("cache_max_mb")
        const val DEFAULT_CACHE_MAX_MB = 500
        val DEFAULT_HOME_KEYWORDS = listOf("有声小说", "有声书", "悬疑小说", "多人有声")
        const val MIN_CACHE_MAX_MB = 100
        const val MAX_CACHE_MAX_MB = 2048
        val LAST_QUEUE_JSON = stringPreferencesKey("last_queue_json")
        val LAST_INDEX = intPreferencesKey("last_index")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val LAST_SOURCE_TITLE = stringPreferencesKey("last_source_title")
        val PLAYURL_CACHE_JSON = stringPreferencesKey("playurl_cache_json")
        const val MAX_PLAYURL_CACHE = 300
    }
}
