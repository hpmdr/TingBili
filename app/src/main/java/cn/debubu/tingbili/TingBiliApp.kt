package cn.debubu.tingbili

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class TingBiliApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var prefs: PreferencesRepository

    override fun onCreate() {
        super.onCreate()
        enableStrictModeInDebug()
        // DataStore 预热：DI 构造期（SimpleCache / ExoPlayer / Coil ImageLoader）要同步取两个常量，
        // 这里先在 IO 线程把文件读完，构造期只读内存镜像，避免主线程 runBlocking 读盘（REVIEW_REPORT D-08）。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { prefs.prime() }
    }

    /**
     * debug 包打开 StrictMode（REVIEW_REPORT B-06）：主线程磁盘/网络、未关闭的 Cursor/Closeable 全部打日志。
     * 用 penaltyLog 而不是 penaltyDeath——第三方 SDK 也可能触发，让它变成**可见的噪音**而不是崩溃；
     * release 包完全跳过，零运行时开销。
     */
    private fun enableStrictModeInDebug() {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * Coil 全局图片加载器：只收紧磁盘上限，其余（解码器/网络/内存）全部走 Coil 默认。
     *
     * 图片盘上限 = 音频上限的 1/4（封顶 256MB，见 [PreferencesRepository.imageCacheMaxMb]）。
     * 目录沿用 Coil 默认（`cacheDir/coil3_disk_cache`），存量缓存跨版本保留；
     * 上限构造时固定，调整后需重启生效（与音频缓存同约束）。
     */
    override fun newImageLoader(context: Context): ImageLoader {
        // 镜像优先（App.onCreate 已预热），缺失才 80ms 有界兜底；配置仍在构造期固定，改动下次启动生效
        val audioMaxMb = prefs.cacheMaxMbNow()
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
