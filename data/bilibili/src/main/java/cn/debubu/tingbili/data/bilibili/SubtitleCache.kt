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
    private val maxEntries: Int = MAX_ENTRIES
    private val lock = Any()
    private val tracksCache = LinkedHashMap<String, List<SubtitleTrack>>(16, 0.75f, true)
    private val linesCache = LinkedHashMap<String, List<LyricLine>>(16, 0.75f, true)

    /** 字幕轨列表（已排序，取首个即最佳轨）。失败返回空列表且不缓存。 */
    suspend fun tracks(bvid: String, cid: Long): List<SubtitleTrack> {
        if (bvid.isBlank()) return emptyList()
        val key = "$bvid:$cid"
        synchronized(lock) { tracksCache[key]?.let { return it } }
        val result = (repo.listSubtitleTracks(bvid, cid) as? Result.Success)?.data ?: emptyList()
        synchronized(lock) {
            tracksCache[key] = result
            evictLocked()
        }
        return result
    }

    /** 字幕正文。空正文按无字幕处理且不缓存（与 getSubtitle 语义一致）。 */
    suspend fun lines(url: String): List<LyricLine> {
        if (url.isBlank()) return emptyList()
        synchronized(lock) { linesCache[url]?.let { return it } }
        val result = repo.loadSubtitleLines(url)
        synchronized(lock) {
            if (result.isNotEmpty()) {
                linesCache[url] = result
                evictLocked()
            }
        }
        return result
    }

    fun clear() = synchronized(lock) {
        tracksCache.clear()
        linesCache.clear()
    }

    companion object {
        const val MAX_ENTRIES = 20
    }

    private fun evictLocked() {
        while (tracksCache.size + linesCache.size > maxEntries) {
            val oldestTracks = tracksCache.keys.firstOrNull()
            if (oldestTracks != null) tracksCache.remove(oldestTracks)
            else linesCache.keys.firstOrNull()?.let { linesCache.remove(it) } ?: break
        }
    }
}
