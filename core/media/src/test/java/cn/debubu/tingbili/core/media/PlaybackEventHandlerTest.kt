package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Test

/** PlaybackEventHandler 单测：决策逻辑无 Android 依赖（Fake 触 Uri 故用 Robolectric）。 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEventHandlerTest {

    class FakeTransport : PlayerHandle {
        var listener: Player.Listener? = null
        var index = 0
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override val isPlaying: Boolean get() = false
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override val currentMediaItemIndex: Int get() = index
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {}
        override fun replaceMediaItem(index: Int, item: MediaItem) {}
        override fun addMediaItem(index: Int, item: MediaItem) {}
        override fun removeMediaItem(index: Int) {}
        override fun prepare() {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun addListener(l: Player.Listener) { listener = l }
        override fun release() {}
    }

    class FakeCache : cn.debubu.tingbili.core.data.db.PlayUrlCacheDao {
        val map = mutableMapOf<String, PlayUrlCacheEntity>()
        override suspend fun getAll(): List<PlayUrlCacheEntity> = map.values.toList()
        override suspend fun putAll(rows: List<PlayUrlCacheEntity>) { rows.forEach { map[it.key] = it } }
        override suspend fun pruneExpired(cutoffMs: Long) {}
        override suspend fun pruneOverflow(keep: Int) {}
    }

    class FakeResolvers : PlayUrlResolver, CatalogResolver, ArtworkResolver {
        override suspend fun resolve(bvid: String, cid: Long): String? = null
        override suspend fun tracksOf(bvid: String): List<Track> = emptyList()
        override fun artwork(cover: String, format: ImageFormat, width: Int, height: Int): android.net.Uri? = null
    }

    class FakeListener(var queue: List<Track> = emptyList()) : PlaybackEventHandler.Listener {
        val transitions = mutableListOf<Int>()
        val playingChanges = mutableListOf<Boolean>()
        val stateChanges = mutableListOf<Boolean>()
        val fatals = mutableListOf<String?>()
        var retryStarts = 0
        var recover: PlaybackEventHandler.RecoverResult = PlaybackEventHandler.RecoverResult.Recovered
        override fun queueSnapshot(): List<Track> = queue
        override fun onTransition(index: Int) { transitions += index }
        override fun onPlayingChanged(isPlaying: Boolean) { playingChanges += isPlaying }
        override fun onPlayerStateChanged(buffering: Boolean) { stateChanges += buffering }
        override fun onFatalError(message: String?) { fatals += message }
        override fun onRetryStarted() { retryStarts++ }
        override suspend fun onRetryRecover(): PlaybackEventHandler.RecoverResult = recover
    }

    private fun track(cid: Long = 1L) = Track("BV1", cid, "t", "a", "", 1000, null)

    private lateinit var transport: FakeTransport
    private lateinit var listener: FakeListener
    private lateinit var handler: PlaybackEventHandler

    @Before
    fun setUp() {
        transport = FakeTransport()
        listener = FakeListener(listOf(track(1), track(2)))
        handler = PlaybackEventHandler(transport, PlayUrlPipeline(FakeResolvers(), FakeResolvers(), FakeResolvers(), FakeCache()))
    }

    private fun error403() = PlaybackException(
        "403", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    @Test
    fun `transition in range notifies and resets retry`() = runTest {
        handler.attach(this, listener)
        transport.index = 1
        transport.listener?.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(listOf(1), listener.transitions)
        assertEquals(0, handler.retryCount)
    }

    @Test
    fun `transition out of range ignored`() = runTest {
        handler.attach(this, listener)
        transport.index = 9
        transport.listener?.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertTrue(listener.transitions.isEmpty())
        handler.release()
    }

    @Test
    fun `non-recoverable error is fatal immediately`() = runTest {
        handler.attach(this, listener)
        val fatal = PlaybackException("boom", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        transport.listener?.onPlayerError(fatal)
        runCurrent()
        assertEquals(listOf("boom"), listener.fatals)
        assertEquals(0, listener.retryStarts)
        assertEquals(0, handler.retryCount)
        handler.release()
    }

    @Test
    fun `recoverable error retries then recovers`() = runTest {
        handler.attach(this, listener)
        transport.listener?.onPlayerError(error403())
        runCurrent()
        assertEquals(1, listener.retryStarts)
        assertEquals(1, handler.retryCount)
        assertTrue(listener.fatals.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        // Recovered：调用方接管，不报 fatal
        assertTrue(listener.fatals.isEmpty())
        handler.release()
    }

    @Test
    fun `retry exhaustion is fatal`() = runTest {
        listener.recover = PlaybackEventHandler.RecoverResult.Failed("刷新失败")
        handler.attach(this, listener)
        transport.listener?.onPlayerError(error403())
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(listOf("刷新失败"), listener.fatals)
        handler.release()
    }

    @Test
    fun `playing and ready reset retry`() = runTest {
        handler.attach(this, listener)
        transport.listener?.onPlayerError(error403())
        assertEquals(1, handler.retryCount)
        transport.listener?.onIsPlayingChanged(true)
        assertEquals(0, handler.retryCount)
        assertEquals(listOf(true), listener.playingChanges)
        transport.listener?.onPlayerError(error403())
        transport.listener?.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(0, handler.retryCount)
        assertEquals(listOf(false), listener.stateChanges)
        handler.release()
    }
}
