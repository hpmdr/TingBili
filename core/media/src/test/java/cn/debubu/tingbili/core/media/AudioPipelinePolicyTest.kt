package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** AudioPipelinePolicy 单测：倍速互斥 + 开关镜像（prefs 真 DataStore）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AudioPipelinePolicyTest {

    class FakeTransport : PlayerHandle {
        var speed = 1f
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override val isPlaying: Boolean get() = false
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override val currentMediaItemIndex: Int get() = 0
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {}
        override fun replaceMediaItem(index: Int, item: MediaItem) {}
        override fun addMediaItem(index: Int, item: MediaItem) {}
        override fun removeMediaItem(index: Int) {}
        override fun prepare() {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) { this.speed = speed }
        override fun addListener(listener: Player.Listener) {}
        override fun release() {}
    }

    class FakeListener(var speed: Float = 1f) : AudioPipelinePolicy.Listener {
        var resets = 0
        override fun currentSpeed(): Float = speed
        override fun onSpeedResetTo1x() { resets++; speed = 1f }
    }

    private lateinit var prefs: PreferencesRepository
    private lateinit var transport: FakeTransport
    private lateinit var listener: FakeListener
    private lateinit var policy: AudioPipelinePolicy

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_audio_prefs_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file },
        )
        prefs = PreferencesRepository(ds)
        transport = FakeTransport()
        listener = FakeListener()
        policy = AudioPipelinePolicy(prefs, transport)
    }

    @Test
    fun `offload off allows speed`() = runTest {
        policy.attach(this, listener)
        prefs.setAudioOffload(false)
        runCurrent()
        val r = policy.requestSpeed(1.5f)
        assertTrue(r is AudioPipelinePolicy.SpeedResult.Applied)
        assertEquals(1.5f, (r as AudioPipelinePolicy.SpeedResult.Applied).speed)
        assertEquals(1.5f, transport.speed, 0.001f)
        assertEquals(1.5f, prefs.speed.first(), 0.001f)
        policy.release()
    }

    @Test
    fun `offload on blocks non-1x`() = runTest {
        policy.attach(this, listener)
        prefs.setAudioOffload(true)
        runCurrent()
        assertEquals(true, policy.offloadEnabled)
        val r = policy.requestSpeed(1.5f)
        assertTrue(r is AudioPipelinePolicy.SpeedResult.BlockedByOffload)
        assertEquals(1f, transport.speed, 0.001f)
        policy.release()
    }

    @Test
    fun `offload on resets playing speed to 1x`() = runTest {
        listener.speed = 2f
        transport.speed = 2f
        policy.attach(this, listener)
        prefs.setAudioOffload(true)
        runCurrent()
        assertEquals(1f, transport.speed, 0.001f)
        policy.release()
        assertEquals(1f, prefs.speed.first(), 0.001f)
        assertEquals(1, listener.resets)
        policy.release()
    }

    @Test
    fun `speed is clamped`() = runTest {
        policy.attach(this, listener)
        prefs.setAudioOffload(false)
        runCurrent()
        assertEquals(3f, (policy.requestSpeed(5f) as AudioPipelinePolicy.SpeedResult.Applied).speed)
        assertEquals(0.5f, (policy.requestSpeed(0.1f) as AudioPipelinePolicy.SpeedResult.Applied).speed)
        policy.release()
    }
}
