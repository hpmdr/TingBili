package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.SubtitleDiskDao
import cn.debubu.tingbili.core.data.db.SubtitleDiskEntity
import cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 磁盘快照命中（含新鲜度）：调用方（播放页管线）先秒显、按需再拉新。
 * 解码失败按无缓存处理（不把坏数据带上界面）。
 */
data class CachedSubtitle(
    val tracks: List<SubtitleTrack>,
    val index: Int,
    val lines: List<LyricLine>,
    val label: String,
    val isAi: Boolean,
    val fetchedAt: Long,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        fetchedAt > 0L && nowMs - fetchedAt < SubtitleCache.SUBTITLE_DISK_TTL_MS
}

/**
 * 字幕应用级缓存（单例）。
 *
 * 背景：字幕状态原来住在播放页 ViewModel 里，播放页每次打开都是新 VM，
 * 同一首歌反复进出也要重新拉两跳网络（轨列表+正文），还连带卡住整个 uiState。
 * 常用页不能这么写：贵的状态上移到应用作用域，页面只管读。
 *
 * - key：轨列表用 `bvid:cid`，正文用轨 url。
 * - 只记成功：失败/空正文不缓存，下次重拉（视频后补字幕也能捡回来）。
 * - 有界 LRU，默认 20 条，防内存膨胀。
 * - 线程安全：播放页/后台共用一把锁。
 */
@Singleton
class SubtitleCache @Inject constructor(
    private val repo: BiliRepository,
    private val disk: SubtitleDiskDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val lock = Any()
    private val tracksCache = LinkedHashMap<String, List<SubtitleTrack>>(16, 0.75f, true)
    private val linesCache = LinkedHashMap<String, List<LyricLine>>(16, 0.75f, true)

    /** 字幕轨列表（已排序，取首个即最佳轨）。失败返回空列表且不缓存。 */
    suspend fun tracks(bvid: String, cid: Long): List<SubtitleTrack> =
        when (val r = tracksResult(bvid, cid)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }

    /** 同上，但透出失败（调用方给重试）。空列表也是 Success（真没字幕）。 */
    suspend fun tracksResult(bvid: String, cid: Long): Result<List<SubtitleTrack>> {
        if (bvid.isBlank()) return Result.Success(emptyList())
        val key = "$bvid:$cid"
        synchronized(lock) { tracksCache[key]?.let { return Result.Success(it) } }
        val result = repo.listSubtitleTracks(bvid, cid)
        if (result is Result.Success) {
            synchronized(lock) {
                tracksCache[key] = result.data
                while (tracksCache.size > MAX_TRACK_ENTRIES) {
                    tracksCache.keys.firstOrNull()?.let { tracksCache.remove(it) } ?: break
                }
            }
        }
        return result
    }

    /** 字幕正文。空正文按无字幕处理且不缓存（与 getSubtitle 语义一致）。 */
    suspend fun lines(url: String): List<LyricLine> =
        when (val r = linesResult(url)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }

    /** 同上，但透出失败（CDN 抽风时调用方给重试）。空正文是 Success(empty)。 */
    suspend fun linesResult(url: String): Result<List<LyricLine>> {
        if (url.isBlank()) return Result.Success(emptyList())
        synchronized(lock) { linesCache[url]?.let { return Result.Success(it) } }
        val result = repo.loadSubtitleLinesResult(url)
        if (result is Result.Success && result.data.isNotEmpty()) {
            synchronized(lock) {
                linesCache[url] = result.data
                while (linesCache.size > MAX_LINE_ENTRIES) {
                    linesCache.keys.firstOrNull()?.let { linesCache.remove(it) } ?: break
                }
            }
        }
        return result
    }

    fun clear() = synchronized(lock) {
        tracksCache.clear()
        linesCache.clear()
    }

    /**
     * 读磁盘快照：内存不管（内存只加速同会话切轨，拼快照以磁盘为准，简单可预期）。
     * 只给“成功非空”的做快照，空正文/失败从不落盘（视频后补字幕靠 TTL 过期重拉捡回）。
     */
    suspend fun cached(bvid: String, cid: Long): CachedSubtitle? {
        if (bvid.isBlank()) return null
        val e = try {
            disk.get("$bvid:$cid")
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val tracks = json.decodeFromString(ListSerializer(SubtitleTrack.serializer()), e.tracksJson)
            val lines = json.decodeFromString(ListSerializer(LyricLine.serializer()), e.linesJson)
            if (tracks.isEmpty() || lines.isEmpty()) null
            else CachedSubtitle(tracks, e.index, lines, e.label, e.isAi, e.fetchedAt)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 作废整个 BV 的快照（播放页“刷新当前内容”用）：内存按 `bvid:` 前缀清，
     * 连带正文一起清（正文 key 是 url，从被清的轨里反查）；磁盘按前缀删。
     * 下次管线重走即走网络，字幕后补/改版能捡回来。
     */
    suspend fun invalidateBv(bvid: String) {
        if (bvid.isBlank()) return
        val prefix = "$bvid:"
        synchronized(lock) {
            val doomedTracks = tracksCache.keys.filter { it.startsWith(prefix) }
            val doomedUrls = doomedTracks.flatMap { key -> tracksCache[key].orEmpty().map { it.url } }.toSet()
            doomedTracks.forEach { tracksCache.remove(it) }
            doomedUrls.forEach { linesCache.remove(it) }
        }
        try {
            disk.deleteByPrefix(prefix)
        } catch (_: Exception) {
        }
    }

    /** 写快照（网络成功非空后由管线调）+ 顺手裁到 50 集 */
    suspend fun store(
        bvid: String,
        cid: Long,
        tracks: List<SubtitleTrack>,
        index: Int,
        lines: List<LyricLine>,
        label: String,
        isAi: Boolean,
    ) {
        if (bvid.isBlank() || tracks.isEmpty() || lines.isEmpty()) return
        try {
            disk.save(
                SubtitleDiskEntity(
                    cacheKey = "$bvid:$cid",
                    tracksJson = json.encodeToString(ListSerializer(SubtitleTrack.serializer()), tracks),
                    index = index,
                    linesJson = json.encodeToString(ListSerializer(LyricLine.serializer()), lines),
                    label = label,
                    isAi = isAi,
                    fetchedAt = System.currentTimeMillis(),
                )
            )
            disk.prune(MAX_DISK_ENTRIES)
        } catch (_: Exception) {
            // 快照是纯加速：写失败不影响正文显示
        }
    }

    companion object {
        /** 轨列表很小，多留；正文是整本歌词，少留（同时只听一本） */
        const val MAX_TRACK_ENTRIES = 20
        const val MAX_LINE_ENTRIES = 5
        /** 磁盘快照 TTL：字幕几乎不变，7 天才肯再花网络（用户拍板） */
        const val SUBTITLE_DISK_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        /** 磁盘只留 50 集（单集几小时字幕也就几十 KB，总量几 MB） */
        const val MAX_DISK_ENTRIES = 50
    }
}
