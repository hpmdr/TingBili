package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 音频缓存：全局单例，基于 SimpleCache。
 * - 位置：cacheDir/audio（随系统清理，可被用户清缓存）
 * - 大小：设置页可调（默认 500MB），LRU，超出自动逐出
 * - 数据库：StandaloneDatabaseProvider 记录已缓存片段
 * B 站音频 URL 会过期，MediaItem.customCacheKey 使用 bvid:cid，已在 PlayerManager 中设置，故命中率不受 URL 变化影响。
 * 注意：Media3 的淘汰器上限在构造时固定、不支持运行时调整，
 * 因此上限改动需重启应用生效（下次启动按新上限建缓存，超量部分由 LRU 逐出）。
 */
@Module
@InstallIn(SingletonComponent::class)
object AudioCacheModule {

    @Provides
    @Singleton
    fun provideAudioCache(
        @ApplicationContext context: Context,
        prefs: PreferencesRepository,
    ): SimpleCache {
        val maxMb = try {
            runBlocking { prefs.cacheMaxMb.first() }
        } catch (_: Exception) {
            PreferencesRepository.DEFAULT_CACHE_MAX_MB
        }
        val cacheDir = File(context.cacheDir, "audio").apply { mkdirs() }
        val evictor = LeastRecentlyUsedCacheEvictor(maxMb.toLong() * 1024 * 1024)
        val dbProvider = StandaloneDatabaseProvider(context)
        return SimpleCache(cacheDir, evictor, dbProvider)
    }
}
