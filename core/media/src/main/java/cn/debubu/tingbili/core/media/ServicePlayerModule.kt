package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.util.Log
import dagger.Module
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        // 缓存层：先查本地缓存，未命中再走网络；写入时同时落盘
        // 注意：不要 setCacheWriteDataSinkFactory(null)，那会关闭缓存写入导致缓存永不增长
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // 解码器经 PreferHardwareAudioDecoderSelector 重排：有硬解优先硬解，
    // 失败自动回落软解（ExoPlayer 按序尝试，兜底逻辑不变）。
    // DSP offload 只能在创建时下发（MediaController 无对应会话命令，运行时下发到不了 Service）。
    // DataStore 首读走磁盘（约数十毫秒），随 Service 创建一次性同步读，注释开关=重启 App 生效。
    val offloadOn = runBlocking { prefs.audioOffload.first() }
    // 可观测性：release 包验证 offload 意愿是否落到 ExoPlayer（logcat 搜 TingBiliAudio）
    Log.i("TingBiliAudio", "audioOffload pref=$offloadOn")
    val offload = if (offloadOn) {
        // ENABLED 尽力而为：无缝要求开（章节衔接不断），变速不支持（变速时自动回落不断播）。
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
    val renderersFactory = DefaultRenderersFactory(context).setMediaCodecSelector(PreferHardwareAudioDecoderSelector)
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
