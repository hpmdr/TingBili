package cn.debubu.tingbili

import android.app.Application
import android.content.Context
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class TingBiliApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var prefs: PreferencesRepository

    /**
     * Coil 全局图片加载器：只收紧磁盘上限，其余（解码器/网络/内存）全部走 Coil 默认。
     *
     * 图片盘上限 = 音频上限的 1/4（封顶 256MB，见 [PreferencesRepository.imageCacheMaxMb]）。
     * 目录沿用 Coil 默认（`cacheDir/coil3_disk_cache`），存量缓存跨版本保留；
     * 上限构造时固定，调整后需重启生效（与音频缓存同约束）。
     */
    override fun newImageLoader(context: Context): ImageLoader {
        val audioMaxMb = try {
            runBlocking { prefs.cacheMaxMb.first() }
        } catch (_: Exception) {
            PreferencesRepository.DEFAULT_CACHE_MAX_MB
        }
        val imageMaxBytes = PreferencesRepository.imageCacheMaxMb(audioMaxMb).toLong() * 1024 * 1024
        return ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil3_disk_cache"))
                    .maxSizeBytes(imageMaxBytes)
                    .build()
            }
            .build()
    }
}
