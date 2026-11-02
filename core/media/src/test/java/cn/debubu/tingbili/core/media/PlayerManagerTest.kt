package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.os.Looper
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerManagerTest {

    class FakePlayerHandle : PlayerHandle {
        var mediaItems: List<MediaItem> = emptyList()
        var startIndex: Int = -1
        var seekToPos: Long? = null
        var seekDelta: Long = 0L
        var capturedSpeed: Float = 1f
        var storedListener: Player.Listener? = null
        private var _duration: Long = 100_000L
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override var shuffleModeEnabled: Boolean = false
        private var _currentIdx: Int = 0
        override val currentMediaItemIndex: Int get() = _currentIdx
        private var _pos: Long = 0L
        override val currentPosition: Long get() = _pos
        override val duration: Long get() = _duration
        override val isPlaying: Boolean get() = _isPlaying
        private var _isPlaying: Boolean = false
        fun setCurrentPosition(pos: Long) { _pos = pos }
        fun moveTo(index: Int) { _currentIdx = index.coerceIn(0, mediaItems.size - 1) }
        fun setDuration(v: Long) { _duration = v }
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (failSetMediaItems) throw IllegalStateException("setMediaItems failed")
            mediaItems = items; this.startIndex = startIndex; _currentIdx = startIndex; _pos = startPositionMs
        }
        var failSetMediaItems = false
        var replaceCalls = 0
        override fun replaceMediaItem(index: Int, item: MediaItem) {
            replaceCalls++
            mediaItems = mediaItems.toMutableList().also { if (index in it.indices) it[index] = item }
        }

        override val mediaItemCount: Int get() = mediaItems.size
        override fun addMediaItem(index: Int, item: MediaItem) {
            mediaItems = mediaItems.toMutableList().also { it.add(index.coerceIn(0, it.size), item) }
        }
        override fun removeMediaItem(index: Int) {
            mediaItems = mediaItems.toMutableList().also { if (index in it.indices) it.removeAt(index) }
        }
        override fun prepare() {}
        override fun play() { _isPlaying = true }
        override fun pause() { _isPlaying = false }
        override fun seekTo(positionMs: Long) {
            seekDelta = positionMs - _pos
            seekToPos = positionMs
            _pos = positionMs
        }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItems.isEmpty()) return
            _currentIdx = mediaItemIndex.coerceIn(0, mediaItems.lastIndex)
            seekToPos = positionMs
            _pos = positionMs
            storedListener?.onMediaItemTransition(
                mediaItems[_currentIdx],
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
            )
        }
        override fun seekToNextMediaItem() {
            if (_currentIdx >= mediaItems.lastIndex) return
            seekTo(_currentIdx + 1, 0L)
        }
        override fun seekToPreviousMediaItem() {
            if (_currentIdx <= 0) return
            seekTo(_currentIdx - 1, 0L)
        }
        override fun setPlaybackSpeed(speed: Float) { capturedSpeed = speed }
        override fun addListener(listener: Player.Listener) { storedListener = listener }
        override fun release() {}
        // shadow duration field duplication fix — delegate
    }

    class FakePlayUrlCacheDao : cn.debubu.tingbili.core.data.db.PlayUrlCacheDao {
        val map = mutableMapOf<String, cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>()
        override suspend fun getAll(): List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity> = map.values.toList()
        override suspend fun get(key: String): cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity? = map[key]
        override suspend fun putAll(rows: List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>) {
            rows.forEach { map[it.key] = it }
        }
        override suspend fun pruneExpired(cutoffMs: Long) {
            map.entries.removeIf { it.value.fetchedAtMs < cutoffMs }
        }
        override suspend fun pruneOverflow(keep: Int) {
            map.values.sortedByDescending { it.fetchedAtMs }.drop(keep).map { it.key }.forEach { map.remove(it) }
        }
        override suspend fun delete(key: String) {
            map.remove(key)
        }
    }


    class FakePlayQueueDao : cn.debubu.tingbili.core.data.db.PlayQueueDao {
        var snapshot: cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity? = null
        override suspend fun get(): cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity? = snapshot
        override suspend fun save(s: cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity) {
            snapshot = s
        }
        override suspend fun clear() {
            snapshot = null
        }
    }

    class FakeHistoryDao : HistoryDao {
        val saved = mutableListOf<HistoryEntity>()
        private val map = mutableMapOf<String, HistoryEntity>()
        private fun key(e: HistoryEntity) = "${e.sourceType}:${e.sourceId}"
        override suspend fun save(e: HistoryEntity) { saved.add(e); map[key(e)] = e }
        override suspend fun getSource(sourceType: Int, sourceId: String): HistoryEntity? =
            map["$sourceType:$sourceId"]
        override suspend fun getLatestForTrack(bvid: String, cid: Long): HistoryEntity? =
            map.values.filter { it.bvid == bvid && it.cid == cid }.maxByOrNull { it.updatedAt }
        override suspend fun getAll(): List<HistoryEntity> = map.values.toList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(map.values.toList())
        override fun observeTotalListenMs(): Flow<Long> = flowOf(map.values.sumOf { it.listenDurationMs })
        override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flowOf(map.values.filter { it.bvid == bvid })
        override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flowOf(map.values.filter { it.bvid in bvids })
        override fun observeTodayCount(todayStart: Long): Flow<Int> = flowOf(map.values.count { it.updatedAt >= todayStart })
        override suspend fun deleteSource(sourceType: Int, sourceId: String) {
            map.remove("$sourceType:$sourceId")
        }
        override suspend fun clearAll() { map.clear(); saved.clear() }
    }

    class FakeMediaResolvers : PlayUrlResolver, CatalogResolver, ArtworkResolver {
        var nextUrl: String? = "http://example.com/audio.mp3"
        var urlSequence: List<String?>? = null
        var resolveCalls = 0
        override suspend fun resolve(bvid: String, cid: Long): String? {
            resolveCalls++
            val seq = urlSequence
            return if (seq != null) seq.getOrElse(resolveCalls - 1) { seq.last() } else nextUrl
        }
        override suspend fun tracksOf(bvid: String): List<Track> = emptyList()
        override fun artwork(cover: String, format: ImageFormat): android.net.Uri? = null
    }

    private lateinit var fakePlayer: FakePlayerHandle
    private lateinit var fakeHistory: FakeHistoryDao
    private lateinit var fakePlayQueue: FakePlayQueueDao
    private lateinit var prefs: PreferencesRepository
    private lateinit var fakeResolvers: FakeMediaResolvers
    private lateinit var playerManager: PlayerManager

    @Before
    fun setUp() {
        fakePlayer = FakePlayerHandle()
        fakeHistory = FakeHistoryDao()
        fakePlayQueue = FakePlayQueueDao()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Ensure clean prefs file per test suite
        val file = context.preferencesDataStoreFile("test_media_prefs_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
        fakeResolvers = FakeMediaResolvers()
        playerManager = PlayerManager(fakePlayer, fakeHistory, prefs, fakeResolvers, fakeResolvers, fakeResolvers, FakePlayUrlCacheDao(), fakePlayQueue, context)
        AudioPipelineMonitor.onOffloadSleepingChanged(false)
    }

    @Test
    fun `play sets queue and starts`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        fakePlayer.setCurrentPosition(0L)
        val tracks = listOf(Track("BV1", 1, "t", "a", "", 1000, null))
        playerManager.play(tracks, 0)
        assertEquals("BV1", playerManager.state.value.currentTrack?.bvid)
        assertEquals(1, fakePlayer.mediaItems.size)
        assertEquals(0, fakePlayer.startIndex)
        assertTrue(playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `play order reverse keeps current track and position`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(
            Track("BV1", 1, "p1", "a", "", 10_000, null),
            Track("BV1", 2, "p2", "a", "", 10_000, null),
            Track("BV1", 3, "p3", "a", "", 10_000, null),
            Track("BV1", 4, "p4", "a", "", 10_000, null),
        )
        playerManager.play(tracks, 1, "收藏夹", sourceFolderId = 7L)
        playerManager.seekTo(1234L)

        playerManager.setPlayOrder(cn.debubu.tingbili.core.media.PlayOrder.REVERSE)

        // 队列整体反转，在播的 p2 从下标 1 挪到 2；声音和进度不变
        assertEquals(listOf(4L, 3L, 2L, 1L), playerManager.state.value.queue.map { it.cid })
        assertEquals(2, playerManager.state.value.currentIndex)
        assertEquals(2L, playerManager.state.value.currentTrack?.cid)
        assertEquals(1234L, playerManager.state.value.positionMs)
        assertEquals(2, fakePlayer.startIndex)

        playerManager.setPlayOrder(cn.debubu.tingbili.core.media.PlayOrder.FORWARD)
        assertEquals(listOf(1L, 2L, 3L, 4L), playerManager.state.value.queue.map { it.cid })
        assertEquals(1, playerManager.state.value.currentIndex)
        assertEquals(2L, playerManager.state.value.currentTrack?.cid)
        playerManager.release()
    }

    @Test
    fun `shuffle switch follows random mode`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(
            Track("BV1", 1, "p1", "a", "", 10_000, null),
            Track("BV1", 2, "p2", "a", "", 10_000, null),
        )
        playerManager.play(tracks, 0)
        prefs.setRandomPlayMode(cn.debubu.tingbili.core.data.model.RandomPlayMode.PURE_RANDOM)

        playerManager.setShuffleEnabled(true)
        assertEquals(true, playerManager.state.value.shuffleEnabled)
        assertEquals(false, fakePlayer.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, fakePlayer.repeatMode)

        prefs.setRandomPlayMode(cn.debubu.tingbili.core.data.model.RandomPlayMode.SHUFFLE)
        playerManager.setShuffleEnabled(false)
        assertEquals(false, playerManager.state.value.shuffleEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, fakePlayer.repeatMode)

        playerManager.setShuffleEnabled(true)
        assertEquals(true, fakePlayer.shuffleModeEnabled)
        playerManager.release()
    }

    @Test
    fun `pure random next jumps to another item`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(
            Track("BV1", 1, "p1", "a", "", 10_000, null),
            Track("BV1", 2, "p2", "a", "", 10_000, null),
        )
        playerManager.play(tracks, 0)
        prefs.setRandomPlayMode(cn.debubu.tingbili.core.data.model.RandomPlayMode.PURE_RANDOM)
        playerManager.setShuffleEnabled(true)

        playerManager.next()
        // 两首列表里排除当前项后只剩 p2，必跳它
        assertEquals(1, playerManager.state.value.currentIndex)
        assertEquals(2L, playerManager.state.value.currentTrack?.cid)
        playerManager.release()
    }

    @Test
    fun `play resumes source specific history when available`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        fakeHistory.save(
            HistoryEntity(
                bvid = "BV1",
                cid = 1,
                positionMs = 6_000,
                sourceType = HistoryEntity.SOURCE_FOLDER,
                sourceId = "7",
                sourceTitle = "胖宝宝",
            )
        )
        val tracks = listOf(Track("BV1", 1, "t", "a", "", 10_000, null))
        playerManager.play(tracks, 0, "胖宝宝", sourceFolderId = 7L)
        assertEquals(6_000L, fakePlayer.currentPosition)
        playerManager.release()
    }

    @Test
    fun `play falls back to latest track history across sources`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        fakeHistory.save(
            HistoryEntity(
                bvid = "BV1",
                cid = 1,
                positionMs = 4_000,
                sourceType = HistoryEntity.SOURCE_BV,
                sourceId = "BV1",
                sourceTitle = "T",
            )
        )
        val tracks = listOf(Track("BV1", 1, "t", "a", "", 10_000, null))
        // 从收藏夹 7 起播，但夹记录不存在 → 回退该分 P 最近一条
        playerManager.play(tracks, 0, "胖宝宝", sourceFolderId = 7L)
        assertEquals(4_000L, fakePlayer.currentPosition)
        playerManager.release()
    }

    @Test
    fun `syncFolderQueue appends new P without touching the playing item`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val p1 = Track("BV1", 1, "p1", "a", "", 1000, null)
        val p2 = Track("BV1", 2, "p2", "a", "", 1000, null)
        playerManager.play(listOf(p1), 0, "收藏夹", sourceFolderId = 7L)
        assertEquals(7L, playerManager.state.value.sourceFolderId)

        assertTrue(playerManager.syncFolderQueue(7L, listOf(p1, p2), "收藏夹"))
        assertEquals(listOf(1L, 2L), playerManager.state.value.queue.map { it.cid })
        assertEquals(0, playerManager.state.value.currentIndex)
        assertEquals(2, fakePlayer.mediaItems.size)
        assertEquals(0, fakePlayer.startIndex)
        playerManager.release()
    }

    @Test
    fun `syncFolderQueue ignores folders other than the queue source`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val p1 = Track("BV1", 1, "p1", "a", "", 1000, null)
        val p2 = Track("BV1", 2, "p2", "a", "", 1000, null)
        playerManager.play(listOf(p1), 0, "收藏夹", sourceFolderId = 7L)

        assertFalse(playerManager.syncFolderQueue(8L, listOf(p1, p2), "别的夹"))
        assertEquals(1, playerManager.state.value.queue.size)
        assertEquals(7L, playerManager.state.value.sourceFolderId)
        playerManager.release()
    }

    @Test
    fun `syncFolderQueue reorder rebuilds transport and keeps playing item`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val p1 = Track("BV1", 1, "p1", "a", "", 1000, null)
        val p2 = Track("BV1", 2, "p2", "a", "", 1000, null)
        playerManager.play(listOf(p1, p2), 0, "收藏夹", sourceFolderId = 7L)

        assertTrue(playerManager.syncFolderQueue(7L, listOf(p2, p1), "收藏夹"))
        // 在播项仍是 p1，只是跟随夹内新顺序挪到第 2 位
        assertEquals(listOf(2L, 1L), playerManager.state.value.queue.map { it.cid })
        assertEquals(1, playerManager.state.value.currentIndex)
        assertEquals(1L, playerManager.state.value.currentTrack?.cid)
        assertEquals(1, fakePlayer.startIndex)
        assertTrue(fakePlayer.isPlaying)
        playerManager.release()
    }

    @Test
    fun `syncFolderQueue stops and clears queue when folder is emptied`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val p1 = Track("BV1", 1, "p1", "a", "", 1000, null)
        val p2 = Track("BV1", 2, "p2", "a", "", 1000, null)
        playerManager.play(listOf(p1, p2), 0, "收藏夹", sourceFolderId = 7L)

        assertTrue(playerManager.syncFolderQueue(7L, emptyList(), "收藏夹"))
        // 夹被清空：按 Media3「无后继 → ended」停播并清空队列
        assertTrue(playerManager.state.value.queue.isEmpty())
        assertEquals(null, playerManager.state.value.currentTrack)
        assertEquals(-1, playerManager.state.value.currentIndex)
        assertEquals(null, playerManager.state.value.sourceFolderId)
        assertEquals(false, playerManager.state.value.isPlaying)
        assertEquals(0, fakePlayer.mediaItems.size)
        assertEquals(false, fakePlayer.isPlaying)
        runCurrent()
        assertEquals(null, fakePlayQueue.snapshot)
        playerManager.release()
    }

    @Test
    fun `syncFolderQueue keeps reverse order when folder changes`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val p1 = Track("BV1", 1, "p1", "a", "", 1000, null)
        val p2 = Track("BV1", 2, "p2", "a", "", 1000, null)
        val p3 = Track("BV1", 3, "p3", "a", "", 1000, null)
        playerManager.play(listOf(p1, p2), 0, "收藏夹", sourceFolderId = 7L)
        playerManager.setPlayOrder(cn.debubu.tingbili.core.media.PlayOrder.REVERSE)

        assertTrue(playerManager.syncFolderQueue(7L, listOf(p1, p2, p3), "收藏夹"))
        // 收藏夹从 [p1,p2] 变成 [p1,p2,p3]；倒序播放时应是 [p3,p2,p1]
        assertEquals(listOf(3L, 2L, 1L), playerManager.state.value.queue.map { it.cid })
        assertEquals(1L, playerManager.state.value.currentTrack?.cid)
        assertEquals(2, playerManager.state.value.currentIndex)
        playerManager.release()
    }

    @Test
    fun `syncFolderQueue keeps playing item when removed from folder`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val p1 = Track("BV1", 1, "p1", "a", "", 1000, null)
        val p2 = Track("BV1", 2, "p2", "a", "", 1000, null)
        playerManager.play(listOf(p1, p2), 1, "收藏夹", sourceFolderId = 7L)

        assertTrue(playerManager.syncFolderQueue(7L, listOf(p1), "收藏夹"))
        assertEquals(listOf(1L, 2L), playerManager.state.value.queue.map { it.cid })
        assertEquals(2L, playerManager.state.value.currentTrack?.cid)
        assertEquals(1, playerManager.state.value.currentIndex)
        playerManager.release()
    }

    @Test
    fun `sleep timer pauses at zero and clears`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        playerManager.startSleepTimer(60)
        assertEquals(60, playerManager.state.value.sleepRemainSec)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(false, playerManager.state.value.isPlaying)
        assertEquals(null, playerManager.state.value.sleepRemainSec)
        assertEquals(false, playerManager.state.value.sleepEndOfChapter)
        playerManager.release()
    }

    @Test
    fun `sleep timer cancel clears state`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        playerManager.startSleepTimer(60)
        playerManager.cancelSleepTimer()
        assertEquals(null, playerManager.state.value.sleepRemainSec)
        advanceTimeBy(120_000)
        runCurrent()
        // 取消后不再触发暂停
        assertEquals(true, playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `sleep timer with finishChapter enters chapter tail then stops`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(
            Track("BV1", 1, "t1", "a", "", 3_600_000, null),
            Track("BV2", 2, "t2", "a", "", 3_600_000, null),
        )
        playerManager.play(tracks, 0)
        playerManager.startSleepTimer(60, finishChapter = true)
        assertEquals(60, playerManager.state.value.sleepRemainSec)
        assertEquals(true, playerManager.state.value.sleepFinishChapter)
        advanceTimeBy(61_000)
        runCurrent()
        // 到点后不暂停，转入播完即停阶段
        assertEquals(true, playerManager.state.value.isPlaying)
        assertEquals(true, playerManager.state.value.sleepEndOfChapter)
        assertEquals(null, playerManager.state.value.sleepRemainSec)
        playerManager.next()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(false, playerManager.state.value.isPlaying)
        assertEquals(false, playerManager.state.value.sleepEndOfChapter)
        playerManager.release()
    }

    @Test
    fun `sleep timer persists deadline and clears on fire`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        playerManager.startSleepTimer(60)
        runCurrent()
        assertEquals(60, prefs.sleepTotalSec.first())
        assertTrue(prefs.sleepDeadlineMs.first() > 0L)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(null, playerManager.state.value.sleepRemainSec)
        assertEquals(0L, prefs.sleepDeadlineMs.first())
        playerManager.release()
    }

    @Test
    fun `restore resumes future deadline`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        prefs.saveSleepTimerState(testScheduler.currentTime + 50_000, 60, false)
        playerManager.restoreSleepTimer()
        runCurrent()
        assertEquals(50, playerManager.state.value.sleepRemainSec)
        assertEquals(60, playerManager.state.value.sleepTotalSec)
        playerManager.release()
    }

    @Test
    fun `restore with expired deadline pauses immediately`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        assertTrue(playerManager.state.value.isPlaying)
        advanceTimeBy(10_000)
        runCurrent()
        prefs.saveSleepTimerState(testScheduler.currentTime - 1_000, 60, false)
        playerManager.restoreSleepTimer()
        runCurrent()
        assertEquals(false, playerManager.state.value.isPlaying)
        assertEquals(null, playerManager.state.value.sleepRemainSec)
        assertEquals(0L, prefs.sleepDeadlineMs.first())
        playerManager.release()
    }

    @Test
    fun `restore tail resumes chapter-end stop`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(
            Track("BV1", 1, "t1", "a", "", 3_600_000, null),
            Track("BV1", 2, "t2", "a", "", 3_600_000, null),
        )
        playerManager.play(tracks, 0)
        prefs.setSleepFinishChapter(true)
        prefs.saveSleepTailState()
        playerManager.restoreSleepTimer()
        runCurrent()
        assertEquals(true, playerManager.state.value.sleepEndOfChapter)
        assertEquals(null, playerManager.state.value.sleepRemainSec)
        playerManager.next()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(false, playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `sleep timer setFinishChapter off mid-countdown stops at time`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        playerManager.startSleepTimer(60, finishChapter = true)
        playerManager.setSleepFinishChapter(false)
        assertEquals(false, playerManager.state.value.sleepFinishChapter)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(false, playerManager.state.value.isPlaying)
        assertEquals(null, playerManager.state.value.sleepRemainSec)
        playerManager.release()
    }

    @Test
    fun `refreshQueue appends new episodes without interrupting`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val old = listOf(
            Track("BV1", 1, "P1", "a", "", 1000, null),
            Track("BV1", 2, "P2", "a", "", 1000, null),
        )
        playerManager.play(old, 0)
        runCurrent()
        val res = playerManager.refreshQueue(old + Track("BV1", 3, "P3", "a", "", 1000, null), "书")
        assertEquals(1, res.added)
        assertEquals(0, res.removed)
        assertEquals(3, fakePlayer.mediaItems.size)
        assertEquals("BV1:3", fakePlayer.mediaItems[2].mediaId)
        assertEquals(0, playerManager.state.value.currentIndex)
        assertTrue(playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `refreshQueue removes stale renames kept and holds position`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val old = listOf(
            Track("BV1", 1, "P1", "a", "", 1000, null),
            Track("BV1", 2, "P2", "a", "", 1000, null),
            Track("BV1", 3, "P3", "a", "", 1000, null),
        )
        playerManager.play(old, 1)
        runCurrent()
        val new = listOf(
            Track("BV1", 1, "P1-new", "a", "", 1000, null),
            Track("BV1", 2, "P2", "a", "", 1000, null),
            Track("BV1", 4, "P4", "a", "", 1000, null),
        )
        val res = playerManager.refreshQueue(new, "书")
        assertEquals(1, res.added)
        assertEquals(1, res.removed) // P3 没了（非在播，直接删）
        assertEquals(1, res.updated) // P1 改名
        assertEquals(listOf("BV1:1", "BV1:2", "BV1:4"), fakePlayer.mediaItems.map { it.mediaId })
        assertEquals(1, playerManager.state.value.currentIndex) // 还在 P2 上
        assertEquals("01 · P1-new", fakePlayer.mediaItems[0].mediaMetadata.title)
        assertTrue(playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `refreshQueue keeps deleted playing episode in place`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val old = listOf(
            Track("BV1", 1, "P1", "a", "", 1000, null),
            Track("BV1", 2, "P2", "a", "", 1000, null),
        )
        playerManager.play(old, 0)
        runCurrent()
        // 在播的 P1 被删：保留原位（拍板语义），不计 removed
        val res = playerManager.refreshQueue(listOf(old[1]), "书")
        assertEquals(0, res.removed)
        assertEquals(listOf("BV1:1", "BV1:2"), fakePlayer.mediaItems.map { it.mediaId })
        assertEquals(0, playerManager.state.value.currentIndex)
        assertEquals("BV1", playerManager.state.value.currentTrack?.bvid)
        assertTrue(playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `play does not start foreground service manually`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        // 前台服务由 Media3 在真正开始播放时拉起并 startForeground；手动提前起服务会让
        // 「起了服务但 5s 内没 startForeground」成为系统杀进程的理由（REVIEW_REPORT G-12）。
        // 这里锁住新契约：play() 不再手动 startForegroundService。
        val started = org.robolectric.Shadows.shadowOf(appContext as android.app.Application)
            .peekNextStartedService()
        assertEquals(null, started)
        playerManager.release()
    }

    @Test
    fun `step seek respects config`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        // set step to 15s via prefs
        prefs.setStep(15)
        // prepare player at position 5000
        fakePlayer.setCurrentPosition(5000L)
        fakePlayer.setDuration(100000L)
        // need queue for state
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        fakePlayer.setCurrentPosition(5000L)
        playerManager.seekStep(+1)
        // 5000 + 15000 = 20000
        assertEquals(20000L, fakePlayer.seekToPos)
        playerManager.release()
    }

    @Test
    fun `sleeping callback alone marks offload active`() {
        // 不少机型只回调 sleepingForOffload、不回调 onOffloadedPlayback；
        // 且 sleeping 是瞬时的（缓冲满才为真），判定必须是粘性的，否则会误报"已回落 CPU"
        AudioPipelineMonitor.resetTrackState()
        AudioPipelineMonitor.onOffloadSleepingChanged(true)
        assertTrue(AudioPipelineMonitor.offloadEverActive.value)
        AudioPipelineMonitor.onOffloadSleepingChanged(false)
        assertTrue(AudioPipelineMonitor.offloadEverActive.value)
        // 出现 CPU 解码器才撤销
        AudioPipelineMonitor.onDecoderInitialized("c2.android.aac.decoder")
        assertEquals(false, AudioPipelineMonitor.offloadEverActive.value)
        AudioPipelineMonitor.resetTrackState()
    }

    @Test
    fun `stall realigns when offload is in use`() = runTest {
        playerManager.testScope = this
        var now = 0L
        playerManager.clock = { now }
        AudioPipelineMonitor.onOffloadedPlaybackChanged(true)
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 600_000, null)), 0)
        fakePlayer.play()
        // play() 内部 setMediaItems 会把位置重置为起点，这里再摆到 5s 作为基线
        fakePlayer.setCurrentPosition(5_000L)
        // 先跑一个 tick 建立位置基线
        now = 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        fakePlayer.seekToPos = null
        // 冻结：墙钟跳 60s，位置原地不动（tick 只跑一次，模拟冻结期间循环停摆）
        now += 60_000L
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(5_000L, fakePlayer.seekToPos)
        playerManager.release()
        AudioPipelineMonitor.resetTrackState()
    }

    @Test
    fun `stall does not realign without offload`() = runTest {
        playerManager.testScope = this
        var now = 0L
        playerManager.clock = { now }
        AudioPipelineMonitor.resetTrackState()
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 600_000, null)), 0)
        fakePlayer.play()
        fakePlayer.setCurrentPosition(5_000L)
        now = 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        fakePlayer.seekToPos = null
        now += 60_000L
        advanceTimeBy(500L)
        runCurrent()
        // 普通模式下冻结会直接停音，不需要 seek（否则每次卡顿都白跳）
        assertEquals(null, fakePlayer.seekToPos)
        playerManager.release()
    }

    @Test
    fun `pause re-anchors position when player reports not playing`() = runTest {
        // 缓冲/offload 出声时 isPlaying 可能为假，但暂停落点必须按播放器位置落盘/显字
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 600_000, null)), 0)
        fakePlayer.setCurrentPosition(30_000L)
        fakePlayer.pause()
        playerManager.pause()
        assertEquals(30_000L, playerManager.state.value.positionMs)
        playerManager.release()
    }

    @Test
    fun `ui visible re-anchors paused position without seeking`() = runTest {
        playerManager.testScope = this
        var now = 0L
        playerManager.clock = { now }
        var deep = 0L
        playerManager.deepSleepClock = { deep }
        AudioPipelineMonitor.resetTrackState()
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 600_000, null)), 0)
        fakePlayer.pause()
        now = 2_000L
        playerManager.onUiVisible() // 首次只建深睡基线
        // 睡了一晚，期间停在 42s；解锁后 UI 必须按播放器位置显字
        deep += 4 * 60 * 60 * 1000L
        fakePlayer.setCurrentPosition(42_000L)
        playerManager.onUiVisible()
        assertEquals(42_000L, playerManager.state.value.positionMs)
        // 暂停态不 seek：起播本来就从播放器位置续，seek 只会制造倒带
        assertEquals(null, fakePlayer.seekToPos)
        playerManager.release()
    }

    @Test
    fun `ui visible realigns after long deep sleep while playing`() = runTest {
        playerManager.testScope = this
        var now = 0L
        playerManager.clock = { now }
        var deep = 0L
        playerManager.deepSleepClock = { deep }
        AudioPipelineMonitor.onOffloadedPlaybackChanged(true)
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 600_000, null)), 0)
        fakePlayer.play()
        fakePlayer.setCurrentPosition(5_000L)
        now = 2_000L
        playerManager.onUiVisible() // 基线
        deep += 3 * 60 * 60 * 1000L
        AudioPipelineMonitor.onAudioUnderrun() // 断流证据：位置源可能落后于实际出声
        fakePlayer.seekToPos = null
        playerManager.onUiVisible()
        assertEquals(5_000L, fakePlayer.seekToPos)
        playerManager.release()
        AudioPipelineMonitor.resetTrackState()
    }

    @Test
    fun `ui visible reconciles chapter index changed while frozen`() = runTest {
        playerManager.testScope = this
        var now = 0L
        playerManager.clock = { now }
        playerManager.deepSleepClock = { 0L }
        val tracks = listOf(
            Track("BV1", 1, "t1", "a", "", 600_000, null),
            Track("BV1", 2, "t2", "a", "", 600_000, null),
        )
        playerManager.play(tracks, 0)
        // 夜间自动切到第 2 集，UI 的 transition 事件被冻结漏掉
        fakePlayer.moveTo(1)
        fakePlayer.setCurrentPosition(3_000L)
        now = 2_000L
        playerManager.onUiVisible()
        assertEquals(1, playerManager.state.value.currentIndex)
        assertEquals(2L, playerManager.state.value.currentTrack?.cid)
        assertEquals(3_000L, playerManager.state.value.positionMs)
        playerManager.release()
    }

    @Test
    fun `setSpeed updates player and prefs`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        // 默认开 offload 会钳住倍速: 先关, 再放行主 looper 让镜像同步
        prefs.setAudioOffload(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(playerManager.setSpeed(1.5f))
        assertEquals(1.5f, fakePlayer.capturedSpeed, 0.001f)
        assertEquals(1.5f, prefs.speed.first(), 0.001f)
    }

    @Test
    fun `setSpeed blocked when offload engaged`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        prefs.setAudioOffload(true)
        AudioPipelineMonitor.onOffloadSleepingChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, playerManager.setSpeed(1.5f))
        assertEquals(1f, fakePlayer.capturedSpeed, 0.001f)
        assertEquals(1f, prefs.speed.first(), 0.001f)
        assertEquals(null, playerManager.state.value.errorMessage)
        AudioPipelineMonitor.onOffloadSleepingChanged(false)
        AudioPipelineMonitor.resetTrackState()
        playerManager.release()
    }


    @Test
    fun `setRepeatMode updates player and state`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.setRepeatMode(PlaybackState.REPEAT_MODE_ONE)
        assertEquals(Player.REPEAT_MODE_ONE, fakePlayer.repeatMode)
        assertEquals(PlaybackState.REPEAT_MODE_ONE, playerManager.state.value.repeatMode)
    }

    @Test
    fun `history throttle saves after 10s`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }

        val tracks = listOf(Track("BV1", 1, "t", "a", "", 10000, null))
        fakePlayer.setCurrentPosition(12345L)
        playerManager.play(tracks, 0)
        // fakePlayer.isPlaying true after play
        fakePlayer.setCurrentPosition(12345L)
        // 节流 10s：推进 9s 不写，满 10s 写一次（全 App 1Hz 根因治理）
        advanceTimeBy(9_000L)
        assertTrue(fakeHistory.saved.isEmpty())
        advanceTimeBy(1_100L)
        assertTrue(fakeHistory.saved.isNotEmpty())
        assertEquals("BV1", fakeHistory.saved.first().bvid)
        assertEquals(12345L, fakeHistory.saved.first().positionMs)
        playerManager.release()
    }

    @Test
    fun `pause flushes history immediately`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }

        val tracks = listOf(Track("BV1", 1, "t", "a", "", 10000, null))
        fakePlayer.setCurrentPosition(5000L)
        playerManager.play(tracks, 0)
        runCurrent()
        fakeHistory.saved.clear()
        fakePlayer.setCurrentPosition(5000L)
        playerManager.pause()
        runCurrent()
        // 暂停即时刷：不等 10s 节流，直接落盘
        assertTrue(fakeHistory.saved.isNotEmpty())
        assertEquals(5000L, fakeHistory.saved.first().positionMs)
        playerManager.release()
    }

    @Test
    fun `track transition flushes history immediately`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(
            Track("BV1", 1, "t1", "a", "", 10000, null),
            Track("BV1", 2, "t2", "a", "", 10000, null),
        )
        fakePlayer.setCurrentPosition(1000L)
        playerManager.play(tracks, 0)
        runCurrent()
        fakeHistory.saved.clear()
        // 切到第 2 集：不等 10s 节流，transition 当场落盘
        fakePlayer.setCurrentPosition(2000L)
        fakePlayer.moveTo(1)
        fakePlayer.storedListener?.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        runCurrent()
        val saved = fakeHistory.saved
        assertTrue(saved.isNotEmpty())
        assertEquals(2L, saved.last().cid)
        assertEquals(2000L, saved.last().positionMs)
        // 同一 BV 换分 P 只更新同一条历史，不再新增行
        assertEquals(1, fakeHistory.getAll().size)
        playerManager.release()
    }

    @Test
    fun `restore restores queue and position from prefs`() = runTest {
        val tracks = listOf(
            Track("BV1", 1, "t1", "a", "cover1", 10000, null),
            Track("BV2", 2, "t2", "a", "cover2", 20000, null)
        )
        fakePlayQueue.snapshot = cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity(
            tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(tracks),
            currentIndex = 1,
        )
        prefs.setLastPosition(5500L)
        playerManager.restoreLastPlayback()
        assertEquals(2, playerManager.state.value.queue.size)
        assertEquals(1, playerManager.state.value.currentIndex)
        assertEquals("BV2", playerManager.state.value.currentTrack?.bvid)
        assertEquals(5500L, playerManager.state.value.positionMs)
        assertEquals("cover2", playerManager.state.value.currentTrack?.cover)
        assertEquals(false, playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `restore with empty queue keeps empty state`() = runTest {
        // no set, prefs empty
        playerManager.restoreLastPlayback()
        assertTrue(playerManager.state.value.queue.isEmpty())
        assertEquals(-1, playerManager.state.value.currentIndex)
        assertEquals(null, playerManager.state.value.currentTrack)
        playerManager.release()
    }

    @Test
    fun `play persists queue for restore`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(Track("BV1", 1, "t", "a", "", 1000, null))
        playerManager.play(tracks, 0)
        runCurrent()
        // Room snapshot should contain queue
        val snap = fakePlayQueue.snapshot
        val decoded = cn.debubu.tingbili.core.data.db.PlayQueueJson.decode(snap!!.tracksJson)
        assertEquals(1, decoded.size)
        assertEquals("BV1", decoded.first().bvid)
        assertEquals(0, snap.currentIndex)
        playerManager.release()
    }

    @Test
    fun `seek persists position`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(Track("BV1", 1, "t", "a", "", 10000, null))
        playerManager.play(tracks, 0)
        runCurrent()
        playerManager.seekTo(4200L)
        runCurrent()
        assertEquals(4200L, prefs.lastPositionMs.first())
        playerManager.release()
    }

    @Test
    fun `play sets loading and custom cache key`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        val tracks = listOf(Track("BV99", 123, "t", "a", "", 10000, null))
        playerManager.play(tracks, 0)
        runCurrent()
        // loading should be false after success
        assertEquals(false, playerManager.state.value.isLoading)
        assertEquals(true, playerManager.state.value.isPlaying)
        // customCacheKey should be bvid:cid for cache hit across URL expiry
        assertEquals("BV99:123", fakePlayer.mediaItems.first().localConfiguration?.customCacheKey)
        assertEquals("BV99:123", fakePlayer.mediaItems.first().mediaId)
        playerManager.release()
    }

    @Test
    fun `play starts without resolving urls upfront`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        // 67 P 的 BV 也不该在起播前批量解析直链（否则瞬间打 67 次 playurl 触发风控）
        val tracks = (1..5).map { Track("BV1", it.toLong(), "t$it", "a", "", 1000, null) }
        playerManager.play(tracks, 0)
        runCurrent()
        assertEquals(5, fakePlayer.mediaItems.size)
        assertEquals(0, fakeResolvers.resolveCalls)
        assertEquals(true, playerManager.state.value.isPlaying)
        assertEquals(null, playerManager.state.value.errorMessage)
        assertTrue(PlayUrlPipeline.isLazyUri(fakePlayer.mediaItems.first().localConfiguration!!.uri))
        playerManager.release()
    }

    @Test
    fun `play failure while old track playing restores old track and resumes`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BVOLD", 1, "old", "a", "", 100_000, null)), 0)
        runCurrent()
        fakePlayer.play()
        fakePlayer.setCurrentPosition(30_000L)
        runCurrent()

        // 新曲目装载失败：不能留下「新标题 + 旧声音」
        fakePlayer.failSetMediaItems = true
        playerManager.play(listOf(Track("BVNEW", 2, "new", "a", "", 10_000, null)), 0)
        runCurrent()

        val state = playerManager.state.value
        assertEquals("BVOLD", state.currentTrack?.bvid)
        assertEquals(100_000L, state.durationMs)
        assertEquals(false, state.isLoading)
        assertTrue(state.errorMessage != null)
        // 旧声音恢复，transport 队列未被新曲目污染
        assertTrue(fakePlayer.isPlaying)
        assertEquals("BVOLD:1", fakePlayer.mediaItems.first().mediaId)
        playerManager.release()
    }

    @Test
    fun `url expiry 403 triggers retry with lazy item`() = runTest {
        playerManager.testScope = this
        playerManager.clock = { testScheduler.currentTime }
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 10000, null)), 0)
        runCurrent()
        val before = fakePlayer.mediaItems.first()
        assertEquals(0, fakePlayer.replaceCalls)
        // simulate 403 error from ExoPlayer
        val error = androidx.media3.common.PlaybackException("403", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        fakePlayer.storedListener?.onPlayerError(error)
        // allow retry delay 1s
        advanceTimeBy(1100L)
        runCurrent()
        // 换回同一个惰性 item 再 prepare：DataSource 打开时会重新解析直链
        assertEquals(1, fakePlayer.replaceCalls)
        assertEquals("BV1:1", fakePlayer.mediaItems.first().localConfiguration?.customCacheKey)
        assertTrue(PlayUrlPipeline.isLazyUri(fakePlayer.mediaItems.first().localConfiguration!!.uri))
        assertEquals(before.mediaId, fakePlayer.mediaItems.first().mediaId)
        playerManager.release()
    }
}
