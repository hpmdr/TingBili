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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
            mediaItems = items; this.startIndex = startIndex; _currentIdx = startIndex; _pos = startPositionMs
        }
        override fun replaceMediaItem(index: Int, item: MediaItem) {
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
        override fun setPlaybackSpeed(speed: Float) { capturedSpeed = speed }
        override fun addListener(listener: Player.Listener) { storedListener = listener }
        override fun release() {}
        // shadow duration field duplication fix — delegate
    }

    class FakePlayUrlCacheDao : cn.debubu.tingbili.core.data.db.PlayUrlCacheDao {
        val map = mutableMapOf<String, cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>()
        override suspend fun getAll(): List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity> = map.values.toList()
        override suspend fun putAll(rows: List<cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity>) {
            rows.forEach { map[it.key] = it }
        }
        override suspend fun pruneExpired(cutoffMs: Long) {
            map.entries.removeIf { it.value.fetchedAtMs < cutoffMs }
        }
        override suspend fun pruneOverflow(keep: Int) {
            map.values.sortedByDescending { it.fetchedAtMs }.drop(keep).map { it.key }.forEach { map.remove(it) }
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
        private val map = mutableMapOf<Pair<String, Long>, HistoryEntity>()
        override suspend fun save(e: HistoryEntity) { saved.add(e); map[e.bvid to e.cid] = e }
        override suspend fun get(bvid: String, cid: Long): HistoryEntity? = map[bvid to cid]
        override suspend fun getAll(): List<HistoryEntity> = map.values.toList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(map.values.toList())
        override fun observeTotalListenMs(): Flow<Long> = flowOf(map.values.sumOf { it.listenDurationMs })
        override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flowOf(map.values.filter { it.bvid == bvid })
        override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flowOf(map.values.filter { it.bvid in bvids })
        override fun observeTodayCount(todayStart: Long): Flow<Int> = flowOf(map.values.count { it.updatedAt >= todayStart })
        override suspend fun delete(bvid: String, cid: Long) { map.remove(bvid to cid) }
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
        override fun artwork(cover: String, format: ImageFormat, width: Int, height: Int): android.net.Uri? = null
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
    }

    @Test
    fun `play sets queue and starts`() = runTest {
        playerManager.testScope = this
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
    fun `sleep timer pauses at zero and clears`() = runTest {
        playerManager.testScope = this
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
    fun `sleep timer setFinishChapter off mid-countdown stops at time`() = runTest {
        playerManager.testScope = this
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
        assertEquals("P1-new", fakePlayer.mediaItems[0].mediaMetadata.title)
        assertTrue(playerManager.state.value.isPlaying)
        playerManager.release()
    }

    @Test
    fun `refreshQueue keeps deleted playing episode in place`() = runTest {
        playerManager.testScope = this
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
    fun `play starts foreground playback service`() = runTest {
        playerManager.testScope = this
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
        val started = org.robolectric.Shadows.shadowOf(appContext as android.app.Application)
            .peekNextStartedService()
        assertTrue(started?.component?.className?.endsWith("TingBiliPlaybackService") == true)
        playerManager.release()
    }

    @Test
    fun `step seek respects config`() = runTest {
        playerManager.testScope = this
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
    fun `setSpeed updates player and prefs`() = runTest {
        playerManager.testScope = this
        playerManager.setSpeed(1.5f)
        assertEquals(1.5f, fakePlayer.capturedSpeed, 0.001f)
        assertEquals(1.5f, prefs.speed.first(), 0.001f)
    }

    @Test
    fun `setRepeatMode updates player and state`() = runTest {
        playerManager.testScope = this
        playerManager.setRepeatMode(PlaybackState.REPEAT_MODE_ONE)
        assertEquals(Player.REPEAT_MODE_ONE, fakePlayer.repeatMode)
        assertEquals(PlaybackState.REPEAT_MODE_ONE, playerManager.state.value.repeatMode)
    }

    @Test
    fun `history throttle saves after 10s`() = runTest {
        playerManager.testScope = this

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
    fun `play with empty result sets error and not loading`() = runTest {
        playerManager.testScope = this
        // Fake API returns empty playUrl if cid==-1 (simulate error)
        val fakeApi = FakeMediaResolvers().also { it.nextUrl = null }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_media_prefs_err_${System.nanoTime()}")
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), produceFile = { file })
        val errPrefs = PreferencesRepository(ds)
        val errManager = PlayerManager(fakePlayer, fakeHistory, errPrefs, fakeApi, fakeApi, fakeApi, FakePlayUrlCacheDao(), FakePlayQueueDao(), context)
        errManager.testScope = this
        errManager.play(listOf(Track("BVX", 999, "t", "a", "", 1000, null)), 0)
        runCurrent()
        assertEquals(false, errManager.state.value.isLoading)
        assertEquals(false, errManager.state.value.isPlaying)
        assertTrue(errManager.state.value.errorMessage != null)
        errManager.release()
    }

    @Test
    fun `url expiry 403 triggers refresh and replaces mediaItem`() = runTest {
        playerManager.testScope = this
        val fakeApi = FakeMediaResolvers().also {
            it.urlSequence = listOf("http://example.com/audio1.mp3", "http://example.com/audio2.mp3")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_media_prefs_refresh_${System.nanoTime()}")
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), produceFile = { file })
        val refreshPrefs = PreferencesRepository(ds)
        val m = PlayerManager(fakePlayer, fakeHistory, refreshPrefs, fakeApi, fakeApi, fakeApi, FakePlayUrlCacheDao(), FakePlayQueueDao(), context)
        m.testScope = this
        m.play(listOf(Track("BV1", 1, "t", "a", "", 10000, null)), 0)
        runCurrent()
        assertEquals("http://example.com/audio1.mp3", fakePlayer.mediaItems.first().localConfiguration?.uri.toString())
        // simulate 403 error from ExoPlayer
        val error = androidx.media3.common.PlaybackException("403", null, androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        fakePlayer.storedListener?.onPlayerError(error)
        // allow retry delay 1s
        advanceTimeBy(1100L)
        runCurrent()
        // should have replaced with second URL
        assertEquals("http://example.com/audio2.mp3", fakePlayer.mediaItems.first().localConfiguration?.uri.toString())
        assertEquals("BV1:1", fakePlayer.mediaItems.first().localConfiguration?.customCacheKey)
        m.release()
    }
}
