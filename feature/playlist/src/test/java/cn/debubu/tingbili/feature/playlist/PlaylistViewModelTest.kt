package cn.debubu.tingbili.feature.playlist

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.media.PlayerHandle
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.PlayUrlDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaylistViewModelTest {

    @get:Rule
    val instantTask = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    class FakePlaylistDao : PlaylistDao {
        private var idSeq = 1L
        private val playlists = mutableListOf<PlaylistEntity>()
        private val tracks = mutableListOf<PlaylistTrackEntity>()
        private val playlistsFlow = MutableStateFlow<List<PlaylistEntity>>(emptyList())

        override suspend fun insert(p: PlaylistEntity): Long {
            val id = idSeq++
            val e = p.copy(id = id)
            playlists.add(e)
            playlistsFlow.value = playlists.toList()
            return id
        }

        override suspend fun addTrack(t: PlaylistTrackEntity) {
            // INSERT OR IGNORE按 (playlistId,bvid,cid)
            if (tracks.none { it.playlistId == t.playlistId && it.bvid == t.bvid && it.cid == t.cid }) {
                tracks.add(t)
            }
        }

        override suspend fun getTracks(id: Long): List<PlaylistTrackEntity> =
            tracks.filter { it.playlistId == id }.sortedBy { it.order }

        override fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistsFlow

        override suspend fun getPlaylists(): List<PlaylistEntity> = playlists.toList()

        override suspend fun removeTrack(playlistId: Long, bvid: String, cid: Long) {
            tracks.removeIf { it.playlistId == playlistId && it.bvid == bvid && it.cid == cid }
        }

        override suspend fun deletePlaylist(id: Long) {
            playlists.removeIf { it.id == id }
            playlistsFlow.value = playlists.toList()
        }

        override suspend fun clearTracks(playlistId: Long) {
            tracks.removeIf { it.playlistId == playlistId }
        }

        override suspend fun updateOrder(playlistId: Long, bvid: String, cid: Long, newOrder: Int) {
            val idx = tracks.indexOfFirst { it.playlistId == playlistId && it.bvid == bvid && it.cid == cid }
            if (idx >= 0) {
                val old = tracks[idx]
                tracks[idx] = old.copy(order = newOrder)
            }
        }
    }

    class FakePlayerHandle : PlayerHandle {
        var mediaItems: List<MediaItem> = emptyList()
        var playCalled = false
        var lastTracksTitles: List<String> = emptyList()
        override var repeatMode: Int = Player.REPEAT_MODE_OFF
        override val currentMediaItemIndex: Int get() = 0
        override val currentPosition: Long get() = 0L
        override val duration: Long get() = 0L
        override val isPlaying: Boolean get() = false
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            mediaItems = items
            lastTracksTitles = items.mapNotNull { it.mediaMetadata.title?.toString() }
        }
        override fun prepare() {}
        override fun play() { playCalled = true }
        override fun pause() {}
        override fun seekTo(positionMs: Long) {}
        override fun setPlaybackSpeed(speed: Float) {}
        override fun addListener(listener: Player.Listener) {}
        override fun release() {}
    }

    class FakeHistoryDao : HistoryDao {
        override suspend fun save(e: HistoryEntity) {}
        override suspend fun get(bvid: String, cid: Long): HistoryEntity? = null
        override suspend fun getAll(): List<HistoryEntity> = emptyList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override suspend fun delete(bvid: String, cid: Long) {}
        override suspend fun clearAll() {}
    }

    class FakeBiliApi : BiliApi {
        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto(code = 0)
        override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int): PlayUrlDto =
            PlayUrlDto(code = 0, data = cn.debubu.tingbili.data.bilibili.dto.PlayUrlData(dash = cn.debubu.tingbili.data.bilibili.dto.DashData(audio = listOf(cn.debubu.tingbili.data.bilibili.dto.DashAudio(baseUrl = "https://audio.example.com/a.mp3")))))
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
    }

    private lateinit var dao: FakePlaylistDao
    private lateinit var playerHandle: FakePlayerHandle
    private lateinit var player: PlayerManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = FakePlaylistDao()
        playerHandle = FakePlayerHandle()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferencesRepository(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { ctx.preferencesDataStoreFile("test_playlist_prefs_${System.nanoTime()}") })
        val repo = BiliRepository(FakeBiliApi())
        player = PlayerManager(playerHandle, FakeHistoryDao(), prefs, repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `create playlist and add tracks dedup by bvid+cid`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("我的歌单")
        advanceUntilIdle()
        // vm.playlists uses Eagerly, value should reflect, but use dao as ground truth
        assertEquals(1, dao.getPlaylists().size)
        val pid = dao.getPlaylists().first().id

        val track = Track("BV1", 1, "a", "", "", 0, null)
        vm.addTracks(pid, listOf(track, track.copy()))
        advanceUntilIdle()
        // dedup via IGNORE, same bvid+cid only one row
        assertEquals(1, vm.tracks.first().size)
        // also verify dao directly
        assertEquals(1, dao.getTracks(pid).size)

        // different cid should be considered distinct
        vm.addTracks(pid, listOf(Track("BV1", 2, "b", "", "", 0, null)))
        advanceUntilIdle()
        assertEquals(2, vm.tracks.first().size)
    }

    @Test
    fun `reorder updates order field`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("reorder")
        advanceUntilIdle()
        val pid = dao.getPlaylists().first().id
        val t1 = Track("BV1", 1, "a", "", "", 0, null)
        val t2 = Track("BV1", 2, "b", "", "", 0, null)
        val t3 = Track("BV2", 1, "c", "", "", 0, null)
        vm.addTracks(pid, listOf(t1, t2, t3))
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), vm.tracks.first().map { it.title })

        vm.reorder(pid, 0, 2)
        advanceUntilIdle()
        val reordered = vm.tracks.first().map { it.title }
        assertEquals(listOf("b", "c", "a"), reordered)
        // order field should be 0,1,2 accordingly
        val entities = dao.getTracks(pid)
        assertEquals(0, entities[0].order)
        assertEquals(1, entities[1].order)
        assertEquals(2, entities[2].order)
    }

    @Test
    fun `remove track via swipe delete`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("del")
        advanceUntilIdle()
        val pid = dao.getPlaylists().first().id
        val t = Track("BV1", 1, "a", "", "", 0, null)
        vm.addTracks(pid, listOf(t))
        advanceUntilIdle()
        assertEquals(1, vm.tracks.first().size)
        vm.remove(pid, t)
        advanceUntilIdle()
        assertEquals(0, vm.tracks.first().size)
        assertEquals(0, dao.getTracks(pid).size)
    }

    @Test
    fun `playAll delegates to PlayerManager with queue`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("play")
        advanceUntilIdle()
        val pid = dao.getPlaylists().first().id
        val tracks = listOf(Track("BV1", 1, "a", "", "", 1000, null), Track("BV1", 2, "b", "", "", 1000, null))
        vm.addTracks(pid, tracks)
        advanceUntilIdle()
        vm.playAll(pid)
        advanceUntilIdle()
        assertTrue(playerHandle.playCalled)
        assertEquals(2, playerHandle.mediaItems.size)
        assertEquals("BV1:1", playerHandle.mediaItems[0].mediaId)
        assertEquals("BV1:2", playerHandle.mediaItems[1].mediaId)
    }

    @Test
    fun `delete playlist clears tracks and selection`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("toDelete")
        advanceUntilIdle()
        val pid = dao.getPlaylists().first().id
        vm.addTracks(pid, listOf(Track("BV1", 1, "a", "", "", 0, null)))
        advanceUntilIdle()
        vm.deletePlaylist(pid)
        advanceUntilIdle()
        assertEquals(0, dao.getPlaylists().size)
    }

    @Test
    fun `addTracks dedup across multiple calls`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("multi")
        advanceUntilIdle()
        val pid = dao.getPlaylists().first().id
        val t = Track("BV1", 1, "a", "", "", 0, null)
        vm.addTracks(pid, listOf(t))
        advanceUntilIdle()
        vm.addTracks(pid, listOf(t))
        advanceUntilIdle()
        assertEquals(1, vm.tracks.first().size)
    }
}

private fun android.content.Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
