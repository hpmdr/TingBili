package cn.debubu.tingbili.core.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SleepTimer 纯逻辑单测：内存 Store + 虚拟时间，无 Android 依赖。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    class FakeStore : SleepTimer.Store {
        var tail = false
        var finishChapter = false
        var deadlineMs = 0L
        var totalSec = 0
        var cleared = 0
        override suspend fun loadTail(): Boolean = tail
        override suspend fun loadFinishChapter(fallback: Boolean): Boolean = finishChapter
        override suspend fun loadDeadlineMs(): Long = deadlineMs
        override suspend fun loadTotalSec(): Int = totalSec
        override suspend fun saveCountdown(deadlineMs: Long, totalSec: Int, finishChapter: Boolean) {
            this.deadlineMs = deadlineMs
            this.totalSec = totalSec
            this.finishChapter = finishChapter
        }
        override suspend fun saveTail() { tail = true }
        override suspend fun setFinishChapter(finishChapter: Boolean) { this.finishChapter = finishChapter }
        override suspend fun clear() {
            cleared++
            tail = false
            deadlineMs = 0L
        }
    }

    class FakeListener(var key: Pair<String, Long>? = "BV1" to 1L) : SleepTimer.Listener {
        val states = mutableListOf<SleepTimer.SleepUiState>()
        var fireCount = 0
        var over = false
        override fun chapter(): SleepTimer.ChapterSnapshot = SleepTimer.ChapterSnapshot(key, over)
        override fun onStateChanged(state: SleepTimer.SleepUiState) { states += state }
        override suspend fun onFire() { fireCount++ }
    }

    @Test
    fun `countdown fires at zero and clears`() = runTest {
        val store = FakeStore()
        val listener = FakeListener()
        var now = 0L
        val timer = SleepTimer(clock = { now }, store = store)
        timer.attach(this, listener)
        timer.start(60)
        runCurrent()
        assertEquals(60, timer.state.remainSec)
        assertEquals(60, store.totalSec)
        now = 61_000L
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(1, listener.fireCount)
        assertEquals(null, timer.state.remainSec)
    }

    @Test
    fun `cancel clears state and never fires`() = runTest {
        val store = FakeStore()
        val listener = FakeListener()
        var now = 0L
        val timer = SleepTimer(clock = { now }, store = store)
        timer.attach(this, listener)
        timer.start(60)
        timer.cancel()
        assertEquals(null, timer.state.remainSec)
        now = 120_000L
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, listener.fireCount)
    }

    @Test
    fun `finishChapter enters tail and fires on chapter change`() = runTest {
        val store = FakeStore()
        val listener = FakeListener()
        var now = 0L
        val timer = SleepTimer(clock = { now }, store = store)
        timer.attach(this, listener)
        timer.start(60, finishChapter = true)
        now = 61_000L
        advanceTimeBy(61_000)
        runCurrent()
        // 到点不触发，转入播完即停
        assertEquals(0, listener.fireCount)
        assertEquals(true, timer.state.endOfChapter)
        assertTrue(store.tail)
        // 切歌 → 停
        listener.key = "BV1" to 2L
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, listener.fireCount)
        assertEquals(false, timer.state.endOfChapter)
    }

    @Test
    fun `setFinishChapter off mid-countdown fires at time`() = runTest {
        val store = FakeStore()
        val listener = FakeListener()
        var now = 0L
        val timer = SleepTimer(clock = { now }, store = store)
        timer.attach(this, listener)
        timer.start(60, finishChapter = true)
        timer.setFinishChapter(false)
        assertEquals(false, timer.state.finishChapter)
        now = 61_000L
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(1, listener.fireCount)
    }

    @Test
    fun `setFinishChapter off in tail cancels`() = runTest {
        val store = FakeStore()
        val listener = FakeListener()
        var now = 0L
        val timer = SleepTimer(clock = { now }, store = store)
        timer.attach(this, listener)
        timer.start(60, finishChapter = true)
        now = 61_000L
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(true, timer.state.endOfChapter)
        timer.setFinishChapter(false)
        assertEquals(false, timer.state.endOfChapter)
        assertEquals(null, timer.state.remainSec)
    }

    @Test
    fun `restore resumes future deadline`() = runTest {
        val store = FakeStore().also {
            it.deadlineMs = 50_000L
            it.totalSec = 60
        }
        val listener = FakeListener()
        val timer = SleepTimer(clock = { 0L }, store = store)
        timer.attach(this, listener)
        timer.restore()
        runCurrent()
        assertEquals(50, timer.state.remainSec)
        assertEquals(60, timer.state.totalSec)
        timer.release()
    }

    @Test
    fun `restore with expired deadline fires immediately`() = runTest {
        // 过期 deadline 用过去的绝对时间（正值）；负值按无落盘忽略，与旧逻辑一致
        var now = 10_000L
        val store = FakeStore().also { it.deadlineMs = 9_000L }
        val listener = FakeListener()
        val timer = SleepTimer(clock = { now }, store = store)
        timer.attach(this, listener)
        timer.restore()
        runCurrent()
        assertEquals(1, listener.fireCount)
        timer.release()
    }

    @Test
    fun `restore tail with null key clears and ignores`() = runTest {
        val store = FakeStore().also { it.tail = true }
        val listener = FakeListener(key = null)
        val timer = SleepTimer(clock = { 0L }, store = store)
        timer.attach(this, listener)
        timer.restore()
        runCurrent()
        assertEquals(0, listener.fireCount)
        assertEquals(false, timer.state.endOfChapter)
        assertEquals(1, store.cleared)
    }

    @Test
    fun `restore tail resumes chapter-end stop`() = runTest {
        val store = FakeStore().also {
            it.tail = true
            it.finishChapter = true
        }
        val listener = FakeListener()
        val timer = SleepTimer(clock = { 0L }, store = store)
        timer.attach(this, listener)
        timer.restore()
        runCurrent()
        assertEquals(true, timer.state.endOfChapter)
        listener.key = "BV1" to 2L
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, listener.fireCount)
        timer.release()
    }
}
