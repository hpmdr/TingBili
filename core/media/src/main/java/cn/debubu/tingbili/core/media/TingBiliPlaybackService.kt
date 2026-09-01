package cn.debubu.tingbili.core.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Audio-only foreground service. Single source of truth — no Surface, low memory.
 * Holds ExoPlayer + MediaSession for notification/lock-screen/BT/耳机线控.
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
