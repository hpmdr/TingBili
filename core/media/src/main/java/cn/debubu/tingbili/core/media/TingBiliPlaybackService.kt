package cn.debubu.tingbili.core.media

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Audio-only foreground service. Single source of truth — no Surface, low memory.
 * Holds ExoPlayer + MediaSession for notification/lock-screen/BT/耳机线控.
 * 注入的 Player 为 @ServiceScoped，唯一释放点为 onDestroy。
 */
@AndroidEntryPoint
class TingBiliPlaybackService : MediaSessionService() {

    @Inject lateinit var player: Player
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
            // 可观测性：offload 是否真正接管 + 实际解码器名（logcat 搜 TingBiliAudio）
            (player as ExoPlayer).addAudioOffloadListener(object : ExoPlayer.AudioOffloadListener {
                override fun onSleepingForOffloadChanged(sleepingForOffload: Boolean) {
                    Log.i("TingBiliAudio", "sleepingForOffload=$sleepingForOffload")
                    AudioPipelineMonitor.onOffloadSleepingChanged(sleepingForOffload)
                }

                override fun onOffloadedPlayback(offloadedPlayback: Boolean) {
                    Log.i("TingBiliAudio", "offloadedPlayback=$offloadedPlayback")
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
            })
        }
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        player.release()
        super.onDestroy()
    }
}
