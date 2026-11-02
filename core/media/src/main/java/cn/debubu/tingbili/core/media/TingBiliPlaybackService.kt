package cn.debubu.tingbili.core.media

import android.app.PendingIntent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
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
    companion object {
        /** 通知点击 → 直达播放页（MainActivity 读取后消费，见 AppRootNavHost） */
        const val EXTRA_OPEN_PLAYER = "cn.debubu.tingbili.extra.OPEN_PLAYER"
    }

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
            // 切歌即清 offload 粘性标记：新的一轨要等新的 offload/decoder 回调重建证据，
            // 否则上一轨的 true 会漏到下一轨（CPU 解码 + 解码器复用时无 init 回调纠正）。
            // decoderName 故意保留（复用场景下名字不变，清掉就补不回来）。
            (player as ExoPlayer).addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    AudioPipelineMonitor.onNewTrack()
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
        // 通知渠道显式定义（REVIEW_REPORT A-03）：渠道名/说明/重要级可控，用户能在通知设置里单独识别；
        // 旧渠道（Media3 默认 default_channel_id，名字是 App 名）清掉，避免设置里出现两条。
        ensureNotificationChannel()
        val notificationProvider = androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_notification_channel)
            .build()
        // 单色品牌图标（1.11 的 Builder 没有该参数，只能建完再设）：默认是 Media3 播放三角，
        // 状态栏/锁屏里认不出是谁在播，且彩色自适应图标会被系统抹成一团。
        notificationProvider.setSmallIcon(R.drawable.ic_notification_playback)
        setMediaNotificationProvider(notificationProvider)
        // 点通知/锁屏卡片的「应用」区域要能回到 App：不设 sessionActivity 时默认 contentIntent 是 null（G-13）。
        // 用 getLaunchIntentForPackage 而不是直接引用 MainActivity，避免 core:media 反向依赖 app 模块。
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            // P1：点通知应直达播放页，而不是回到上次的 tab
            putExtra(EXTRA_OPEN_PLAYER, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val sessionActivity = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        mediaSession = MediaSession.Builder(this, player)
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .setCallback(TRUSTED_CONTROLLERS_ONLY)
            .build()
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

    /**
     * 服务需要 exported=true（系统 UI/蓝牙/车机要绑定），但不能对任意 App 开放会话能力：
     * 只接受本应用进程与系统信任的媒体控制器（SystemUI/蓝牙/车机），其余拒绝。
     */
    private val TRUSTED_CONTROLLERS_ONLY = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (isTrustedController(controller)) return super.onConnect(session, controller)
            return MediaSession.ConnectionResult.reject()
        }
    }

    private fun isTrustedController(controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == packageName || controller.isTrusted

    /** 常驻但不响铃（IMPORTANCE_LOW）：媒体控制要可见，但不能在播放时弹提示音 */
    private fun ensureNotificationChannel() {
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_notification_channel),
            android.app.NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}

private const val CHANNEL_ID = "tingbili_playback"
private const val LEGACY_CHANNEL_ID = "default_channel_id"
