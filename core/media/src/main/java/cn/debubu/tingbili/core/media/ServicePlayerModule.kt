package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.util.Log
import dagger.Module

import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Named

/**
 * Service 作用域的播放器供给：ExoPlayer 只在这里创建，
 * 只注入 TingBiliPlaybackService，只在 Service.onDestroy 释放。
 *
 * 音源站的 UA/Referer 由 data:bilibili 以 @Named 提供，
 * core 不再直引 WbiSigner（B 站细节）。
 */
@Module
@InstallIn(ServiceComponent::class)
object ServicePlayerModule {

    @Provides
    @ServiceScoped
    fun providePlaybackPlayer(
        @ApplicationContext context: Context,
        cache: SimpleCache,
        prefs: cn.debubu.tingbili.core.data.datastore.PreferencesRepository,
        audioUrls: AudioUrlResolver,
        @Named("biliUserAgent") userAgent: String,
        @Named("biliReferer") referer: String,
    ): Player {
        // 音频 CDN 要求带 Referer/UA，否则 403
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(mapOf("Referer" to referer))
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)

        // 惰性直链：MediaItem 里存的是 tingbili-audio://play?bvid=&cid=，
        // 真正要读音频时才换成本次解析出的 CDN 直链（一次播放只解析一次，不批量打接口）。
        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { spec ->
            val uri = spec.uri
            if (PlayUrlPipeline.isLazyUri(uri)) {
                val bvid = PlayUrlPipeline.bvidOf(uri)
                val cid = PlayUrlPipeline.cidOf(uri)
                if (bvid == null || cid == null) {
                    throw java.io.IOException("${AudioUrlResolver.RESOLVE_FAILED_MARK}：缺少 bvid/cid")
                }
                spec.withUri(audioUrls.resolveBlocking(bvid, cid).toUri())
            } else {
                spec
            }
        }

        // 缓存层：先查本地缓存，未命中再走网络；写入时同时落盘
        // 注意：不要 setCacheWriteDataSinkFactory(null)，那会关闭缓存写入导致缓存永不增长
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // 解码器经 PreferHardwareAudioDecoderSelector 重排：有硬解优先硬解，
    // 失败自动回落软解（ExoPlayer 按序尝试，兜底逻辑不变）。
    // DSP offload 创建时下发初始值；运行时由 TingBiliPlaybackService 监听开关实时重下发（下一首生效）。
    // 构造期常量：镜像优先（Application.onCreate 已预热），缺失才 80ms 有界兜底；开关实时生效，下一首用新值。
    val offloadOn = prefs.audioOffloadNow()
    // 可观测性：release 包验证 offload 意愿是否落到 ExoPlayer（logcat 搜 TingBiliAudio）
    Log.i("TingBiliAudio", "audioOffload pref=$offloadOn")
    val offload = if (offloadOn) {
        // ENABLED 尽力而为：无缝要求开（章节衔接不断），变速不支持（变速时自动回落不断播）。
        // 注：不在支持列表里的机型/编码（如多数联发科机型的 AAC offload）会自然回落，属预期。
        TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .setIsSpeedChangeSupportRequired(false)
            .build()
    } else {
        TrackSelectionParameters.AudioOffloadPreferences.DEFAULT
    }
    val trackParams = TrackSelectionParameters.DEFAULT.buildUpon()
        .setAudioOffloadPreferences(offload).build()
    val renderersFactory = DefaultRenderersFactory(context)
        .setMediaCodecSelector(PreferHardwareAudioDecoderSelector)
        // 解码器初始化失败自动向低优先级回落：硬解坏了不断播，切软解（选择器只重排，兜底靠它）。
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()
            .apply {
                // offload 意愿必须在 prepare 前下发：决定音轨走 DSP 还是 AP 解码
                setTrackSelectionParameters(trackParams)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }
}
