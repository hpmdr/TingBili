package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HistoryTracker 单 tick 单测：虚拟时间，无 Robolectric。 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryTrackerTest {

    class FakeTransport : PlayerHandle {
        var pos = 0L
        var playing = true
        override val currentPosition: Long get() = pos
        override val duration: Long get() = 100_000L
        override val isPlaying: Boolean get() = playing
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
        override fun setPlaybackSpeed(speed: Float) {}
        override fun addListener(listener: Player.Listener) {}
        override fun release() {}
    }

    class FakeHistory : HistoryDao {
        val saved = mutableListOf<HistoryEntity>()
        private val map = mutableMapOf<String, HistoryEntity>()
        override suspend fun save(e: HistoryEntity) { saved += e; map[e.bvid] = e }
        override suspend fun get(bvid: String): HistoryEntity? = map[bvid]
        override suspend fun getAll(): List<HistoryEntity> = map.values.toList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(map.values.toList())
        override fun observeTotalListenMs(): Flow<Long> = flowOf(0L)
        override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTodayCount(todayStart: Long): Flow<Int> = flowOf(0)
        override suspend fun delete(bvid: String) { map.remove(bvid) }
        override suspend fun clearAll() { map.clear(); saved.clear() }
    }

    class FakeListener(var track: Track? = null, var statePos: Long = 0L) : HistoryTracker.Listener {
        var lastTickPos: Long? = null
        val persisted = mutableListOf<Long>()
        override fun currentTrack(): Track? = track
        override fun statePositionMs(): Long = statePos
        override fun onTick(positionMs: Long, bufferedMs: Long) { lastTickPos = positionMs }
        override suspend fun onPersistPosition(pos: Long) { persisted += pos }
    }

    private fun track() = Track("BV1", 1, "t", "a", "", 10000, null)

    @Test
    fun `tick updates memory and saves after 10s`() = runTest {
        val transport = FakeTransport().also { it.pos = 12345L }
        val dao = FakeHistory()
        val listener = FakeListener(track(), 0L)
        var now = 0L
        val tracker = HistoryTracker(transport, dao)
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 9_000L
        advanceTimeBy(9_000L)
        runCurrent()
        assertEquals(12345L, listener.lastTickPos)
        assertTrue(dao.saved.isEmpty())
        now = 10_100L
        advanceTimeBy(1_100L)
        runCurrent()
        assertTrue(dao.saved.isNotEmpty())
        assertEquals(12345L, dao.saved.first().positionMs)
        assertEquals(listOf(12345L), listener.persisted)
        tracker.release()
    }

    @Test
    fun `paused ticks skip saving, resume saves overdue immediately`() = runTest {
        val transport = FakeTransport()
        val dao = FakeHistory()
        val listener = FakeListener(track(), 0L)
        var now = 0L
        val tracker = HistoryTracker(transport, dao)
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 5_000L
        advanceTimeBy(5_000L)
        transport.playing = false
        now = 25_000L
        advanceTimeBy(20_000L)
        runCurrent()
        assertTrue(dao.saved.isEmpty())
        transport.playing = true
        now = 25_600L
        advanceTimeBy(600L)
        runCurrent()
        // 恢复后已过期立刻补一次落盘（旧逻辑等下一个 10s 槽；此处更保险）
        assertTrue(dao.saved.isNotEmpty())
        tracker.release()
    }

    @Test
    fun `flush writes immediately with state position`() = runTest {
        val transport = FakeTransport()
        val dao = FakeHistory()
        val listener = FakeListener(track(), 5000L)
        val tracker = HistoryTracker(transport, dao)
        tracker.attach(this, listener)
        tracker.flush()
        runCurrent()
        assertTrue(dao.saved.isNotEmpty())
        assertEquals(5000L, dao.saved.first().positionMs)
        tracker.release()
    }

    @Test
    fun `seek jump ignored in accumulated listen`() = runTest {
        val transport = FakeTransport()
        val dao = FakeHistory()
        val listener = FakeListener(track(), 0L)
        var now = 0L
        val tracker = HistoryTracker(transport, dao)
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        transport.pos = 0L
        now = 500L
        advanceTimeBy(500L)
        // seek 跳变：delta 60000 超出正常推进窗口，不计入累计
        transport.pos = 60_000L
        now = 10_500L
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(dao.saved.isNotEmpty())
        assertEquals(0L, dao.saved.first().listenDurationMs)
        tracker.release()
    }
}
