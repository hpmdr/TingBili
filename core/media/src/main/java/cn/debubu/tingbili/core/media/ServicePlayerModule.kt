package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import cn.debubu.tingbili.data.bilibili.WbiSigner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

/**
 * Service 作用域的播放器供给：ExoPlayer 只在这里创建，
 * 只注入 TingBiliPlaybackService，只在 Service.onDestroy 释放。
 */
@Module
@InstallIn(ServiceComponent::class)
object ServicePlayerModule {

    @Provides
    @ServiceScoped
    fun providePlaybackPlayer(
        @ApplicationContext context: Context,
        cache: SimpleCache
    ): Player {
        // B 站音频 CDN 要求带 Referer/UA，否则 403
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(WbiSigner.BROWSER_UA)
            .setDefaultRequestProperties(mapOf("Referer" to WbiSigner.REFERER))
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)

        // 缓存层：先查本地缓存，未命中再走网络；写入时同时落盘
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()
            .apply {
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
