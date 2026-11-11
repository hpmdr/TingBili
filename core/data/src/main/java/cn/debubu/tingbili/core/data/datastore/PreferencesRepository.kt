package cn.debubu.tingbili.core.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.debubu.tingbili.core.data.backup.SettingsBackup
import cn.debubu.tingbili.core.data.model.DarkMode
import cn.debubu.tingbili.core.data.model.DarkThemeStyle
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.RandomPlayMode
import cn.debubu.tingbili.core.data.model.ThemeMode
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 「构造期常量」的内存镜像：音频缓存上限、DSP offload 开关。
     *
     * DI 构造 ExoPlayer / SimpleCache / Coil ImageLoader 时必须同步拿到这两个值，而 DataStore 首读
     * 要打开文件 + 解析。原来在构造期 runBlocking 读盘，冷启动/首次起播会卡主线程几十毫秒
     * （REVIEW_REPORT D-08）。现在 Application.onCreate 就在 IO 线程 prime() 预热；构造期优先读镜像，
     * 镜像未就绪时只做 80ms 有界兜底，读失败落默认值。
     */
    @Volatile
    private var cacheMaxMbMirror: Int? = null

    @Volatile
    private var audioOffloadMirror: Boolean? = null

    /** 进程启动时调一次（IO 线程）：把构造期常量先读进内存镜像。 */
    suspend fun prime() {
        runCatching { ds.data.first() }.onSuccess { p ->
            cacheMaxMbMirror = (p[CACHE_MAX_MB] ?: DEFAULT_CACHE_MAX_MB)
                .coerceIn(MIN_CACHE_MAX_MB, MAX_CACHE_MAX_MB)
            audioOffloadMirror = p[AUDIO_OFFLOAD] ?: true
        }
    }

    /** 构造期同步取音频缓存上限（MB）：镜像优先，未就绪才做有界阻塞兜底。 */
    fun cacheMaxMbNow(): Int = cacheMaxMbMirror ?: runBlocking {
        withTimeoutOrNull(PRIME_BLOCK_TIMEOUT_MS) { cacheMaxMb.first() }
    }?.also { cacheMaxMbMirror = it } ?: DEFAULT_CACHE_MAX_MB

    /** 构造期同步取 DSP offload 开关：镜像优先，未就绪才做有界阻塞兜底。 */
    fun audioOffloadNow(): Boolean = audioOffloadMirror ?: runBlocking {
        withTimeoutOrNull(PRIME_BLOCK_TIMEOUT_MS) { audioOffload.first() }
    }?.also { audioOffloadMirror = it } ?: true

    // 读侧钳制（REVIEW_REPORT E-06）：旧版本写入、手改 DataStore 或异常路径都可能留下越界值，
    // 直接作用于「快进秒数 / 倍速 / 循环模式 / 音质 code」会造成诡异的播放行为，读时收敛到合法区间。
    val stepSec: Flow<Int> = ds.data.map { (it[STEP_SEC] ?: 15).coerceIn(MIN_STEP_SEC, MAX_STEP_SEC) }
    val repeatMode: Flow<Int> = ds.data.map { (it[REPEAT_MODE] ?: 0).coerceIn(0, 2) }
    /** 随机播放方式（洗牌 / 纯随机；只管方式，开关属于当前播放列表） */
    val randomPlayMode: Flow<RandomPlayMode> = ds.data.map {
        RandomPlayMode.fromCode(it[RANDOM_PLAY_MODE] ?: RandomPlayMode.SHUFFLE.code)
    }
    // 倍速读侧钳制：UI 只提供 1.0~2.0，但异常值（NaN/负数/10x）会直接喂给 ExoPlayer。coerceIn 对 NaN 无效，先判空区间
    val speed: Flow<Float> = ds.data.map { p ->
        val raw = p[SPEED] ?: 1f
        if (raw.isNaN() || raw.isInfinite()) 1f else raw.coerceIn(MIN_SPEED, MAX_SPEED)
    }
    /** 字幕字号缩放（全屏+迷你一起跟，默认 1.0，落盘钳制防极端值） */
    val subtitleScale: Flow<Float> = ds.data.map { (it[SUBTITLE_SCALE] ?: 1f).coerceIn(0.85f, 1.3f) }
    /** 音频优先硬解（设置页开关，默认开；开则硬解优先、失败自动回落软解） */
    val audioHwDecode: Flow<Boolean> = ds.data.map { it[AUDIO_HW_DECODE] ?: true }
    /** DSP 音频卸载（设置页开关，默认开；开后条件满足时整条管线交 DSP，AP 休眠） */
    val audioOffload: Flow<Boolean> = ds.data.map { it[AUDIO_OFFLOAD] ?: true }
    /** 音质偏好（playurl qn/audio id：30216/30232/30280/30250/30251，默认 132K；未知值回落默认） */
    val audioQuality: Flow<Int> = ds.data.map { p ->
        val raw = p[AUDIO_QUALITY] ?: DEFAULT_AUDIO_QUALITY
        // 合法档位 code 都落在 30216..30280（64K/132K/192K/杜比/Hi-Res），区间外视为脏值回默认。
        // 档位语义由 data:bilibili 的 AudioQuality 解释；core:data 在依赖图下游，不能反向引用它。
        if (raw in MIN_AUDIO_QUALITY_CODE..MAX_AUDIO_QUALITY_CODE) raw else DEFAULT_AUDIO_QUALITY
    }
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
    val darkThemeStyle: Flow<DarkThemeStyle> = ds.data.map { DarkThemeStyle.fromKey(it[DARK_THEME_STYLE]) }
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

    /**
     * 是否曾经有过收藏：只用于冷启动落地 tab（有收藏进收藏页），由收藏写路径置位。
     * 启动读一个布尔比首帧查 Room 便宜一个数量级（REVIEW_REPORT B-02）；
     * 语义是「曾经」，收藏清空后仍为 true（落地收藏页显示空列表，可接受）。
     */
    val hasFavoriteEver: Flow<Boolean> = ds.data.map { it[HAS_FAVORITE_EVER] ?: false }

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

    suspend fun setRandomPlayMode(mode: RandomPlayMode) {
        ds.edit { it[RANDOM_PLAY_MODE] = mode.code }
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

    suspend fun setDarkThemeStyle(style: DarkThemeStyle) {
        ds.edit { it[DARK_THEME_STYLE] = style.key }
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
        val clamped = v.coerceIn(MIN_CACHE_MAX_MB, MAX_CACHE_MAX_MB)
        cacheMaxMbMirror = clamped
        ds.edit { it[CACHE_MAX_MB] = clamped }
    }

    /** 启动期读一次「曾经有收藏」标记；null = 从未写过（覆盖安装后的首启，需要回填一次） */
    suspend fun hasFavoriteEverOrNull(): Boolean? = ds.data.first()[HAS_FAVORITE_EVER]

    suspend fun setHasFavoriteEver(v: Boolean) {
        ds.edit { it[HAS_FAVORITE_EVER] = v }
    }

    suspend fun markFavoriteEver() = setHasFavoriteEver(true)

    suspend fun setLastPosition(positionMs: Long) {
        ds.edit { it[LAST_POSITION_MS] = positionMs.coerceAtLeast(0L) }
    }

    /**
     * 一次性搬运：读出旧 DataStore playurl 大 Map 并删键，供 PlayerManager 启动时迁入 Room。
     * 新写入一律走 Room，此函数在旧数据搬空后即为 no-op；下个版本可删。
     */
    suspend fun setAudioOffload(v: Boolean) {
        audioOffloadMirror = v
        ds.edit { it[AUDIO_OFFLOAD] = v }
    }

    suspend fun setAudioHwDecode(v: Boolean) {
        ds.edit { it[AUDIO_HW_DECODE] = v }
    }

    /** 音质偏好 id（30216/30232/30280/30250/30251；写未知值时读侧回落请调用方钳制） */
    suspend fun setAudioQuality(v: Int) {
        ds.edit { it[AUDIO_QUALITY] = v }
    }

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

    // ── 更新检查 ─────────────────────────────────────────────
    /** 启动自动检查开关（默认开；关掉后只剩手动入口）。 */
    val autoCheckUpdate: Flow<Boolean> = ds.data.map { it[AUTO_CHECK_UPDATE] ?: true }

    suspend fun setAutoCheckUpdate(v: Boolean) {
        ds.edit { it[AUTO_CHECK_UPDATE] = v }
    }

    /** 上次检查（自动/手动）的墙钟毫秒，自动检查每天最多一次。 */
    val lastUpdateCheckMs: Flow<Long> = ds.data.map { it[LAST_UPDATE_CHECK_MS] ?: 0L }

    suspend fun setLastUpdateCheckMs(v: Long) {
        ds.edit { it[LAST_UPDATE_CHECK_MS] = v }
    }

    /**
     * 用户点过"不再提醒"的远端发布时间（秒）。只记时间不记 tag：
     * tag 手滑打错重发时时间会变，照样能再弹。
     */
    val skippedReleaseSec: Flow<Long> = ds.data.map { it[SKIPPED_RELEASE_SEC] ?: 0L }

    suspend fun setSkippedReleaseSec(v: Long) {
        ds.edit { it[SKIPPED_RELEASE_SEC] = v }
    }

    // ── 备份/恢复：设置白名单快照 ─────────────────────────

    /** 导出设置快照：只取用户偏好，不含睡眠定时/更新检查/播放进度等瞬时状态。 */
    suspend fun snapshotSettings(): SettingsBackup {
        val p = ds.data.first()
        return SettingsBackup(
            stepSec = p[STEP_SEC],
            repeatMode = p[REPEAT_MODE],
            randomPlayMode = p[RANDOM_PLAY_MODE],
            speed = p[SPEED],
            subtitleScale = p[SUBTITLE_SCALE],
            audioHwDecode = p[AUDIO_HW_DECODE],
            audioOffload = p[AUDIO_OFFLOAD],
            audioQuality = p[AUDIO_QUALITY],
            sleepFinishChapter = p[SLEEP_FINISH_CHAPTER],
            sleepLastMinutes = p[SLEEP_LAST_MINUTES],
            themeMode = p[THEME_MODE],
            customThemeColor = p[CUSTOM_THEME_COLOR],
            darkMode = p[DARK_MODE],
            darkThemeStyle = p[DARK_THEME_STYLE],
            imageFormat = p[IMAGE_FORMAT],
            homeKeywords = p[HOME_KEYWORDS]?.split(",")?.map(String::trim)?.filter(String::isNotBlank),
            cacheMaxMb = p[CACHE_MAX_MB],
            autoCheckUpdate = p[AUTO_CHECK_UPDATE],
            searchHistory = p[SEARCH_HISTORY_JSON]?.let { decodeStringList(it) },
        )
    }

    /** 恢复设置：只写非空字段，越界值按读侧规则钳制，避免备份文件把非法值带进来。 */
    suspend fun restoreSettings(s: SettingsBackup) {
        ds.edit { p ->
            s.stepSec?.let { p[STEP_SEC] = it.coerceIn(MIN_STEP_SEC, MAX_STEP_SEC) }
            s.repeatMode?.let { p[REPEAT_MODE] = it.coerceIn(0, 2) }
            s.randomPlayMode?.let { p[RANDOM_PLAY_MODE] = it }
            s.speed?.let { if (it.isFinite()) p[SPEED] = it.coerceIn(MIN_SPEED, MAX_SPEED) }
            s.subtitleScale?.let { p[SUBTITLE_SCALE] = it.coerceIn(0.85f, 1.3f) }
            s.audioHwDecode?.let { p[AUDIO_HW_DECODE] = it }
            s.audioOffload?.let {
                p[AUDIO_OFFLOAD] = it
                audioOffloadMirror = it
            }
            s.audioQuality?.let {
                p[AUDIO_QUALITY] =
                    if (it in MIN_AUDIO_QUALITY_CODE..MAX_AUDIO_QUALITY_CODE) it else DEFAULT_AUDIO_QUALITY
            }
            s.sleepFinishChapter?.let { p[SLEEP_FINISH_CHAPTER] = it }
            s.sleepLastMinutes?.let { p[SLEEP_LAST_MINUTES] = it.coerceIn(30, 600) }
            s.themeMode?.let { p[THEME_MODE] = it }
            s.customThemeColor?.let { p[CUSTOM_THEME_COLOR] = it }
            s.darkMode?.let { p[DARK_MODE] = it }
            s.darkThemeStyle?.let { p[DARK_THEME_STYLE] = it }
            s.imageFormat?.let { p[IMAGE_FORMAT] = it }
            s.homeKeywords?.let { keywords ->
                val cleaned = keywords.map(String::trim).filter(String::isNotBlank).distinct()
                if (cleaned.isEmpty()) p.remove(HOME_KEYWORDS) else p[HOME_KEYWORDS] = cleaned.joinToString(",")
            }
            s.cacheMaxMb?.let {
                val clamped = it.coerceIn(MIN_CACHE_MAX_MB, MAX_CACHE_MAX_MB)
                p[CACHE_MAX_MB] = clamped
                cacheMaxMbMirror = clamped
            }
            s.autoCheckUpdate?.let { p[AUTO_CHECK_UPDATE] = it }
            s.searchHistory?.let { history ->
                val cleaned = history.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_SEARCH_HISTORY)
                if (cleaned.isEmpty()) p.remove(SEARCH_HISTORY_JSON)
                else p[SEARCH_HISTORY_JSON] = Json.encodeToString(ListSerializer(String.serializer()), cleaned)
            }
        }
    }

    private fun decodeStringList(raw: String): List<String> = try {
        Json.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        val STEP_SEC = intPreferencesKey("step_sec")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
        val RANDOM_PLAY_MODE = intPreferencesKey("random_play_mode")
        val SPEED = floatPreferencesKey("speed")
        val SUBTITLE_SCALE = floatPreferencesKey("subtitle_scale")
        val AUDIO_HW_DECODE = androidx.datastore.preferences.core.booleanPreferencesKey("audio_hw_decode")
        val AUDIO_OFFLOAD = androidx.datastore.preferences.core.booleanPreferencesKey("audio_offload")
        val AUDIO_QUALITY = androidx.datastore.preferences.core.intPreferencesKey("audio_quality")
        /** 默认音质偏好 132K（免登录可得最佳档；对齐 data:bilibili AudioQuality.DEFAULT_CODE，core:data 不反向依赖） */
        const val DEFAULT_AUDIO_QUALITY = 30232
        /** 步长滑杆范围（SettingsScreen 的 5f..60f） */
        const val MIN_STEP_SEC = 5
        const val MAX_STEP_SEC = 60
        const val MIN_AUDIO_QUALITY_CODE = 30216
        const val MAX_AUDIO_QUALITY_CODE = 30280
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        val SLEEP_FINISH_CHAPTER = androidx.datastore.preferences.core.booleanPreferencesKey("sleep_finish_chapter")
        val SLEEP_LAST_MINUTES = androidx.datastore.preferences.core.intPreferencesKey("sleep_last_minutes")
        val SLEEP_DEADLINE_MS = androidx.datastore.preferences.core.longPreferencesKey("sleep_deadline_ms")
        val SLEEP_TOTAL_SEC = androidx.datastore.preferences.core.intPreferencesKey("sleep_total_sec")
        val SLEEP_TAIL = androidx.datastore.preferences.core.booleanPreferencesKey("sleep_tail")
        val DYNAMIC_COLOR = androidx.datastore.preferences.core.booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CUSTOM_THEME_COLOR = intPreferencesKey("custom_theme_color")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val DARK_THEME_STYLE = stringPreferencesKey("dark_theme_style")
        val DEFAULT_THEME_COLOR: Int = 0xFFFF6699.toInt()
        val IMAGE_FORMAT = stringPreferencesKey("image_format")
        val HOME_KEYWORDS = stringPreferencesKey("home_keywords")
        val SEARCH_HISTORY_JSON = stringPreferencesKey("search_history_json")
        const val MAX_SEARCH_HISTORY = 15
        val CACHE_MAX_MB = intPreferencesKey("cache_max_mb")
        val HAS_FAVORITE_EVER = androidx.datastore.preferences.core.booleanPreferencesKey("has_favorite_ever")
        const val DEFAULT_CACHE_MAX_MB = 2048
        /** 构造期读镜像缺失时的兜底上限：只在 DataStore 预热未完成的极端情况下才会用到 */
        const val PRIME_BLOCK_TIMEOUT_MS = 80L
        val DEFAULT_HOME_KEYWORDS = listOf("有声小说", "音乐合辑", "恐怖有声")
        const val MIN_CACHE_MAX_MB = 1024
        const val MAX_CACHE_MAX_MB = 4096
        /**
         * 图片盘缓存派生上限 = 音频上限的 1/4（无封顶，纯比例）。
         * 字幕走 Room 裁 50 集（几 MB），不参与配额。
         */
        fun imageCacheMaxMb(audioMaxMb: Int): Int = audioMaxMb / 4
        val LAST_QUEUE_JSON = stringPreferencesKey("last_queue_json")
        val LAST_INDEX = intPreferencesKey("last_index")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val LAST_SOURCE_TITLE = stringPreferencesKey("last_source_title")
        val PLAYURL_CACHE_JSON = stringPreferencesKey("playurl_cache_json")
        val AUTO_CHECK_UPDATE = androidx.datastore.preferences.core.booleanPreferencesKey("auto_check_update")
        val LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
        val SKIPPED_RELEASE_SEC = longPreferencesKey("skipped_release_sec")
    }
}
