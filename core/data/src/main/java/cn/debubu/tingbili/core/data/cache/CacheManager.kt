package cn.debubu.tingbili.core.data.cache

/**
 * 三块缓存的统计口径（设置页“缓存管理”用）。
 *
 * - 音频：Media3 SimpleCache（`cache/audio`）
 * - 图片：Coil 磁盘缓存（封面/头像；内存缓存是 RAM，不计入）
 * - 字幕：Room 表 `SubtitleDiskEntity` 的 JSON（内存 LRU 不计入）
 *
 * 直链缓存（DataStore，百 KB 级）和首页快照（db 内几十 KB）太小，不计入总数。
 */
data class CacheStats(
    val audioBytes: Long = 0L,
    val imageBytes: Long = 0L,
    val subtitleBytes: Long = 0L,
    val subtitleCount: Int = 0,
) {
    val totalBytes: Long get() = audioBytes + imageBytes + subtitleBytes
}

/**
 * 缓存管理契约（实现在 `:app`，VM 只认接口，避免 feature 反向依赖 app）。
 */
interface CacheManager {
    suspend fun stats(): CacheStats

    /** 清音频；[excludeKey] 命中的 key 跳过（一般是正在播放的 `bvid:cid`）。返回释放的字节数。 */
    suspend fun clearAudio(excludeKey: String? = null): Long

    /** 清图片磁盘+内存缓存。返回释放的字节数（磁盘部分）。 */
    suspend fun clearImages(): Long

    /** 清字幕磁盘+内存缓存。返回删掉的集数。 */
    suspend fun clearSubtitles(): Int

    /** 全清（音频同样受 [excludeAudioKey] 保护）。返回释放量的统计。 */
    suspend fun clearAll(excludeAudioKey: String? = null): CacheStats
}
