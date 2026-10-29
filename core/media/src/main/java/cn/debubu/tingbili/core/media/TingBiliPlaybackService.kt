package cn.debubu.tingbili.core.media

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Audio-only foreground service. Single source of truth — no Surface, low memory.
 * Holds ExoPlayer + MediaSession for notification/lock-screen/BT/耳机线控.
 * 注入的 Player 为 @ServiceScoped，唯一释放点为 onDestroy。
 */
@AndroidEntryPoint
class TingBiliPlaybackService : MediaSessionService() {

    @Inject lateinit var player: Player
    @Inject lateinit var prefs: cn.debubu.tingbili.core.data.datastore.PreferencesRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // If Hilt provided a plain Player (ExoPlayer), configure audio attributes.
        // Guard: only configure if player is ExoPlayer (our provider gives ExoPlayer).
        if (player is ExoPlayer) {
            (player as ExoPlayer).setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // handleAudioFocus
            )
            (player as ExoPlayer).setHandleAudioBecomingNoisy(true)
            (player as ExoPlayer).setWakeMode(C.WAKE_MODE_NETWORK)
            applyOffloadLive()
            // 可观测性：offload 是否真正接管 + 实际解码器名（logcat 搜 TingBiliAudio）
            (player as ExoPlayer).addAudioOffloadListener(object : ExoPlayer.AudioOffloadListener {
                override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
                    Log.i("TingBiliAudio", "sleepingForOffload=$sleepingForOffload")
                    AudioPipelineMonitor.onOffloadSleepingChanged(sleepingForOffload)
                }

                override fun onOffloadedPlayback(offloadedPlayback: Boolean) {
                    Log.i("TingBiliAudio", "offloadedPlayback=$offloadedPlayback")
                    AudioPipelineMonitor.onOffloadedPlaybackChanged(offloadedPlayback)
                }
            })
            (player as ExoPlayer).addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializationDurationMs: Long,
                    initializationError: Long,
                ) {
                    Log.i("TingBiliAudio", "audioDecoder=$decoderName")
                    AudioPipelineMonitor.onDecoderInitialized(decoderName)
                }

                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    AudioPipelineMonitor.onAudioFormatChanged(format)
                }

                override fun onAudioUnderrun(
                    eventTime: AnalyticsListener.EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long,
                ) {
                    // offload 断流的硬证据：进程被冻结/限流时 HAL 那边缓冲被抽空才会记
                    Log.i(
                        "TingBiliAudio",
                        "audioUnderrun buffer=${bufferSize}B ${bufferSizeMs}ms sinceLastFeed=${elapsedSinceLastFeedMs}ms"
                    )
                    AudioPipelineMonitor.onAudioUnderrun()
                }
            })
        }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    /**
     * offload 开关运行时下发: 创建时只定初始值(见 ServicePlayerModule), 这里监听开关变化实时重下发, 下一首生效.
     * 直接操作 Service 内的 ExoPlayer, 不走 MediaController(无对应会话命令).
     */
    private fun applyOffloadLive() {
        serviceScope.launch {
            prefs.audioOffload.collect { on ->
                val exo = player as? ExoPlayer ?: return@collect
                val offload = if (on) {
                    // 同 ServicePlayerModule：无缝要求开，变速不要求
                    TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                        .setIsGaplessSupportRequired(true)
                        .setIsSpeedChangeSupportRequired(false)
                        .build()
                } else {
                    TrackSelectionParameters.AudioOffloadPreferences.DEFAULT
                }
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(offload)
                    .build()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
