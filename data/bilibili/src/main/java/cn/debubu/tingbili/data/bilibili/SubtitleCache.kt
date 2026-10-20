package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import javax.inject.Inject
import javax.inject.Singleton

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
    private val repo: BiliRepository
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

    companion object {
        /** 轨列表很小，多留；正文是整本歌词，少留（同时只听一本） */
        const val MAX_TRACK_ENTRIES = 20
        const val MAX_LINE_ENTRIES = 5
    }
}
