package cn.debubu.tingbili.cache

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import cn.debubu.tingbili.core.data.cache.CacheManager
import cn.debubu.tingbili.core.data.cache.CacheStats
import cn.debubu.tingbili.core.data.db.SubtitleDiskDao
import cn.debubu.tingbili.data.bilibili.SubtitleCache
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CacheManager] 的唯一实现（放 `:app`：三块缓存分属 media3 / Coil / Room，
 * 下沉到任何一个 core 模块都会新增跨域依赖，app 聚合一切是最薄的一层）。
 */
@Singleton
class AppCacheManager @Inject constructor(
    private val audioCache: SimpleCache,
    private val subtitleDiskDao: SubtitleDiskDao,
    private val subtitleCache: SubtitleCache,
    @ApplicationContext private val appContext: Context,
) : CacheManager {

    private fun audioBytes(): Long = try {
        audioCache.cacheSpace
    } catch (_: Exception) {
        0L
    }

    private fun imageBytes(): Long = try {
        SingletonImageLoader.get(appContext).diskCache?.size ?: 0L
    } catch (_: Exception) {
        0L
    }

    private suspend fun subtitleStats(): Pair<Long, Int> = try {
        val rows = subtitleDiskDao.rowSizes()
        var bytes = 0L
        rows.forEach { bytes += it.tracksJson.toByteArray().size + it.linesJson.toByteArray().size }
        bytes to rows.size
    } catch (_: Exception) {
        0L to 0
    }

    override suspend fun stats(): CacheStats {
        val (subBytes, subCount) = subtitleStats()
        return CacheStats(
            audioBytes = audioBytes(),
            imageBytes = imageBytes(),
            subtitleBytes = subBytes,
            subtitleCount = subCount,
        )
    }

    override suspend fun clearAudio(excludeKey: String?): Long {
        val before = audioBytes()
        try {
            audioCache.keys
                .filter { it != excludeKey }
                .forEach { audioCache.removeResource(it) }
        } catch (_: Exception) {
        }
        return (before - audioBytes()).coerceAtLeast(0L)
    }

    override suspend fun clearImages(): Long {
        val before = imageBytes()
        try {
            val loader = SingletonImageLoader.get(appContext)
            loader.diskCache?.clear()
            loader.memoryCache?.clear()
        } catch (_: Exception) {
        }
        return (before - imageBytes()).coerceAtLeast(0L)
    }

    override suspend fun clearSubtitles(): Int {
        val count = try {
            subtitleDiskDao.rowSizes().size
        } catch (_: Exception) {
            0
        }
        try {
            subtitleCache.clear()
        } catch (_: Exception) {
        }
        try {
            subtitleDiskDao.clear()
        } catch (_: Exception) {
        }
        return count
    }

    override suspend fun clearAll(excludeAudioKey: String?): CacheStats {
        val freedAudio = clearAudio(excludeAudioKey)
        val freedImage = clearImages()
        val beforeSub = subtitleStats()
        val freedCount = clearSubtitles()
        return CacheStats(
            audioBytes = freedAudio,
            imageBytes = freedImage,
            subtitleBytes = beforeSub.first,
            subtitleCount = freedCount,
        )
    }
}
