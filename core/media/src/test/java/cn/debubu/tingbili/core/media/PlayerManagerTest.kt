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
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlData
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
        fun setDuration(v: Long) { _duration = v }
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            mediaItems = items; this.startIndex = startIndex; _currentIdx = startIndex; _pos = startPositionMs
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
        override fun addListener(listener: Player.Listener) {}
        override fun release() {}
        // shadow duration field duplication fix — delegate
    }

    class FakeHistoryDao : HistoryDao {
        val saved = mutableListOf<HistoryEntity>()
        private val map = mutableMapOf<Pair<String, Long>, HistoryEntity>()
        override suspend fun save(e: HistoryEntity) { saved.add(e); map[e.bvid to e.cid] = e }
        override suspend fun get(bvid: String, cid: Long): HistoryEntity? = map[bvid to cid]
        override suspend fun getAll(): List<HistoryEntity> = map.values.toList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(map.values.toList())
        override suspend fun delete(bvid: String, cid: Long) { map.remove(bvid to cid) }
        override suspend fun clearAll() { map.clear(); saved.clear() }
    }

    class FakeBiliApi : BiliApi {
        override suspend fun search(keyword: String, searchType: String): SearchDto = SearchDto(code = 0)
        override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto {
            return PlayUrlDto(
                code = 0,
                message = "",
                data = PlayUrlData(
                    dash = cn.debubu.tingbili.data.bilibili.dto.DashData(
                        audio = listOf(cn.debubu.tingbili.data.bilibili.dto.DashAudio(baseUrl = "http://example.com/audio.mp3"))
                    )
                )
            )
        }
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
    }

    private lateinit var fakePlayer: FakePlayerHandle
    private lateinit var fakeHistory: FakeHistoryDao
    private lateinit var prefs: PreferencesRepository
    private lateinit var biliRepo: BiliRepository
    private lateinit var playerManager: PlayerManager
    private lateinit var timerManager: TimerManager

    @Before
    fun setUp() {
        fakePlayer = FakePlayerHandle()
        fakeHistory = FakeHistoryDao()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Ensure clean prefs file per test suite
        val file = context.preferencesDataStoreFile("test_media_prefs_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
        biliRepo = BiliRepository(FakeBiliApi())
        playerManager = PlayerManager(fakePlayer, fakeHistory, prefs, biliRepo)
        timerManager = TimerManager(fakePlayer)
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
    fun `history throttle saves after 1s`() = runTest {
        playerManager.testScope = this

        val tracks = listOf(Track("BV1", 1, "t", "a", "", 10000, null))
        fakePlayer.setCurrentPosition(12345L)
        playerManager.play(tracks, 0)
        // fakePlayer.isPlaying true after play
        fakePlayer.setCurrentPosition(12345L)
        // advance virtual time by 1100ms -> history should save once
        advanceTimeBy(1100L)
        assertTrue(fakeHistory.saved.isNotEmpty())
        assertEquals("BV1", fakeHistory.saved.first().bvid)
        assertEquals(12345L, fakeHistory.saved.first().positionMs)
        playerManager.release()
    }

    @Test
    fun `timer pauses after delay`() = runTest {
        timerManager.testScope = this
        fakePlayer.play() // start playing
        assertTrue(fakePlayer.isPlaying)
        timerManager.set(2000L)
        advanceTimeBy(2100L)
        assertTrue(!fakePlayer.isPlaying)
        timerManager.cancel()
    }

    @Test
    fun `timer cancel stops pause`() = runTest {
        timerManager.testScope = this
        fakePlayer.play()
        timerManager.set(2000L)
        timerManager.cancel()
        advanceTimeBy(3000L)
        assertTrue(fakePlayer.isPlaying)
        timerManager.cancel()
    }
}
