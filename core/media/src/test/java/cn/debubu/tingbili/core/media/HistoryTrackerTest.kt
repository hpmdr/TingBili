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
        val stalls = mutableListOf<Pair<Long, Long>>()
        val samples = mutableListOf<Long>()
        override fun currentTrack(): Track? = track
        override fun statePositionMs(): Long = statePos
        override fun onTick(positionMs: Long, bufferedMs: Long) { lastTickPos = positionMs }
        override fun onPositionSampled(positionMs: Long) { samples += positionMs }
        override suspend fun onPersistPosition(pos: Long) { persisted += pos }
        override fun onPlaybackStalled(gapMs: Long, positionMs: Long) { stalls += gapMs to positionMs }
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
    fun `stall fires when wall clock runs but position lags`() = runTest {
        // 模拟进程被冻结：先正常跑两个 tick，再让墙钟跳过 60s、位置原地不动，
        // 只补一个 tick（真实冻结期间循环是停的，不会补跑中间 tick）。
        val transport = FakeTransport().also { it.pos = 5_000L }
        val listener = FakeListener(track(), 0L)
        var now = 0L
        val tracker = HistoryTracker(transport, FakeHistory())
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        now += 60_000L
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(listener.stalls.isNotEmpty())
        assertEquals(5_000L, listener.stalls.first().second)
        tracker.release()
    }

    @Test
    fun `no stall when position advances with wall clock`() = runTest {
        // 同一时间内播放位置正常前进（位置源已对齐），不应触发重同步
        val transport = FakeTransport().also { it.pos = 5_000L }
        val listener = FakeListener(track(), 0L)
        var now = 0L
        val tracker = HistoryTracker(transport, FakeHistory())
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        now += 60_000L
        transport.pos += 60_000L
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(listener.stalls.isEmpty())
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

    @Test
    fun `underrun hint lowers stall threshold`() = runTest {
        // 进程只是被限流：墙钟间隔 4s 达不到默认 10s，但 offload 缓冲被抽空记了一次 underrun，
        // 有这条硬证据就放宽阈值，位置确实落后时仍要重同步。
        val transport = FakeTransport().also { it.pos = 5_000L }
        val listener = FakeListener(track(), 0L)
        var now = 0L
        var underruns = 0L
        val tracker = HistoryTracker(transport, FakeHistory(), underrunCount = { underruns })
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        now += 4_000L
        transport.pos += 2_000L
        underruns = 1L
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(listener.stalls.isNotEmpty())
        tracker.release()
    }

    @Test
    fun `no stall below default threshold without underrun`() = runTest {
        // 同样的 4s/2s 间隔，但没有 underrun 佐证时保持默认阈值（10s/3s），不误伤
        val transport = FakeTransport().also { it.pos = 5_000L }
        val listener = FakeListener(track(), 0L)
        var now = 0L
        val tracker = HistoryTracker(transport, FakeHistory())
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        now += 4_000L
        transport.pos += 2_000L
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(listener.stalls.isEmpty())
        tracker.release()
    }

    @Test
    fun `paused ticks sample position without ticking or saving`() = runTest {
        // 暂停/缓冲态 isPlaying 为假，但位置可能仍在推进（offload 出声/缓冲）：
        // 低频采样只回调 onPositionSampled 做对表，不走 onTick、不落盘。
        val transport = FakeTransport().also { it.playing = false; it.pos = 7_000L }
        val dao = FakeHistory()
        val listener = FakeListener(track(), 0L)
        var now = 0L
        val tracker = HistoryTracker(transport, dao)
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 4_000L
        advanceTimeBy(4_000L)
        runCurrent()
        assertTrue(listener.samples.isNotEmpty())
        assertEquals(7_000L, listener.samples.last())
        assertEquals(null, listener.lastTickPos) // 不走 onTick（不会把 isPlaying 翻成 true）
        assertTrue(dao.saved.isEmpty())
        tracker.release()
    }

    @Test
    fun `underrun alone does not realign when position keeps up`() = runTest {
        // underrun 可能只是缓冲正常见底：位置跟着墙钟走就说明没脱节，不该 seek
        val transport = FakeTransport().also { it.pos = 5_000L }
        val listener = FakeListener(track(), 0L)
        var now = 0L
        var underruns = 0L
        val tracker = HistoryTracker(transport, FakeHistory(), underrunCount = { underruns })
        tracker.clockMs = { now }
        tracker.attach(this, listener)
        tracker.start(now)
        now = 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        now += 4_000L
        transport.pos += 4_000L
        underruns = 1L
        advanceTimeBy(500L)
        runCurrent()
        assertTrue(listener.stalls.isEmpty())
        tracker.release()
    }
}
