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
    /** 字幕字号缩放（全屏+迷你一起跟，默认 1.0，落盘钳制防极端值） */
    val subtitleScale: Flow<Float> = ds.data.map { (it[SUBTITLE_SCALE] ?: 1f).coerceIn(0.85f, 1.3f) }
    val sleepFinishChapter: Flow<Boolean> = ds.data.map { it[SLEEP_FINISH_CHAPTER] ?: false }
    /** 睡眠定时滚轮上次所选分钟（无记忆默认 60，居中露出上下项、暗示可滑；启动定时即记住） */
    val sleepLastMinutes: Flow<Int> = ds.data.map { it[SLEEP_LAST_MINUTES] ?: 60 }
    /** 睡眠定时绝对触发点（wall clock ms，0=无定时；杀进程/重启后靠它恢复，不再丢定时） */
    val sleepDeadlineMs: Flow<Long> = ds.data.map { it[SLEEP_DEADLINE_MS] ?: 0L }
    /** 睡眠定时总秒数（恢复后高亮用；尾阶段为 0） */
    val sleepTotalSec: Flow<Int> = ds.data.map { it[SLEEP_TOTAL_SEC] ?: 0 }
    /** 播完即停尾阶段（倒计时已结束、等章尾；单独持久化，杀进程后仍能补停） */
    val sleepTail: Flow<Boolean> = ds.data.map { it[SLEEP_TAIL] ?: false }

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

    // 进度（高频 10s 写）仍放 DataStore；队列 + 下标 + 合集名已迁 Room（PlayQueueDao，原子快照）。
    // 旧三键只剩 drainLastQueue 一次性搬运用，搬完即删。
    val lastPositionMs: Flow<Long> = ds.data.map { it[LAST_POSITION_MS] ?: 0L }

    /** 一次性搬运旧队列快照并删键：Room 为空且 DataStore 有旧数据时迁入，此后不再写入旧键 */
    suspend fun drainLastQueue(): Triple<List<Track>, Int, String?> {
        var tracks: List<Track> = emptyList()
        var index = -1
        var title: String? = null
        ds.edit { prefs ->
            val json = prefs[LAST_QUEUE_JSON]
            if (json != null) {
                tracks = try {
                    Json.decodeFromString(ListSerializer(Track.serializer()), json)
                } catch (_: Exception) {
                    emptyList()
                }
                index = (prefs[LAST_INDEX] ?: -1)
                title = prefs[LAST_SOURCE_TITLE]
                prefs.remove(LAST_QUEUE_JSON)
                prefs.remove(LAST_INDEX)
                prefs.remove(LAST_SOURCE_TITLE)
            }
        }
        return Triple(tracks, index, title)
    }

    suspend fun setStep(v: Int) {
        ds.edit { it[STEP_SEC] = v }
    }

    suspend fun setRepeatMode(v: Int) {
        ds.edit { it[REPEAT_MODE] = v }
    }

    suspend fun setSpeed(v: Float) {
        ds.edit { it[SPEED] = v }
    }

    suspend fun setSubtitleScale(v: Float) {
        ds.edit { it[SUBTITLE_SCALE] = v.coerceIn(0.85f, 1.3f) }
    }

    suspend fun setSleepFinishChapter(v: Boolean) {
        ds.edit { it[SLEEP_FINISH_CHAPTER] = v }
    }

    suspend fun setSleepLastMinutes(min: Int) {
        ds.edit { it[SLEEP_LAST_MINUTES] = min.coerceIn(30, 600) }
    }

    /** 睡眠定时落盘：绝对时间+总量+开关，一次 edit 减少 DataStore 抖动；尾标志清零 */
    suspend fun saveSleepTimerState(deadlineMs: Long, totalSec: Int, finishChapter: Boolean) {
        ds.edit {
            it[SLEEP_DEADLINE_MS] = deadlineMs
            it[SLEEP_TOTAL_SEC] = totalSec
            it[SLEEP_FINISH_CHAPTER] = finishChapter
            it[SLEEP_TAIL] = false
        }
    }

    /** 倒计时到点且播完本章开：转尾阶段，deadline/total 清掉，尾标志立起 */
    suspend fun saveSleepTailState() {
        ds.edit {
            it.remove(SLEEP_DEADLINE_MS)
            it.remove(SLEEP_TOTAL_SEC)
            it[SLEEP_TAIL] = true
        }
    }

    /** 清定时落盘：只清 deadline/total/tail，FINISH_CHAPTER 留作下次默认 */
    suspend fun clearSleepTimerState() {
        ds.edit {
            it.remove(SLEEP_DEADLINE_MS)
            it.remove(SLEEP_TOTAL_SEC)
            it.remove(SLEEP_TAIL)
        }
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

    suspend fun setLastPosition(positionMs: Long) {
        ds.edit { it[LAST_POSITION_MS] = positionMs.coerceAtLeast(0L) }
    }

    /**
     * 一次性搬运：读出旧 DataStore playurl 大 Map 并删键，供 PlayerManager 启动时迁入 Room。
     * 新写入一律走 Room，此函数在旧数据搬空后即为 no-op；下个版本可删。
     */
    suspend fun drainPlayUrlCache(): Map<String, CachedPlayUrl> {
        var out: Map<String, CachedPlayUrl> = emptyMap()
        ds.edit { prefs ->
            val json = prefs[PLAYURL_CACHE_JSON] ?: return@edit
            out = try {
                Json.decodeFromString(MapSerializer(String.serializer(), CachedPlayUrl.serializer()), json)
            } catch (_: Exception) {
                emptyMap()
            }
            prefs.remove(PLAYURL_CACHE_JSON)
        }
        return out
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
        val SUBTITLE_SCALE = floatPreferencesKey("subtitle_scale")
        val SLEEP_FINISH_CHAPTER = androidx.datastore.preferences.core.booleanPreferencesKey("sleep_finish_chapter")
        val SLEEP_LAST_MINUTES = androidx.datastore.preferences.core.intPreferencesKey("sleep_last_minutes")
        val SLEEP_DEADLINE_MS = androidx.datastore.preferences.core.longPreferencesKey("sleep_deadline_ms")
        val SLEEP_TOTAL_SEC = androidx.datastore.preferences.core.intPreferencesKey("sleep_total_sec")
        val SLEEP_TAIL = androidx.datastore.preferences.core.booleanPreferencesKey("sleep_tail")
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
    }
}
