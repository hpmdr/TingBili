package cn.debubu.tingbili.feature.playlist

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistSummary
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
import cn.debubu.tingbili.data.bilibili.dto.RelatedDto
import cn.debubu.tingbili.data.bilibili.dto.SearchDto
import cn.debubu.tingbili.data.bilibili.dto.SubtitleDto
import cn.debubu.tingbili.data.bilibili.dto.ViewDto
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
        override fun observePlaylistSummaries(): Flow<List<PlaylistSummary>> = flowOf(
            playlists.map { playlist ->
                val playlistTracks = tracks.filter { it.playlistId == playlist.id }
                PlaylistSummary(
                    playlist = playlist,
                    trackCount = playlistTracks.size,
                    totalDurationMs = playlistTracks.sumOf { it.durationMs }
                )
            }
        )

        override suspend fun getPlaylists(): List<PlaylistEntity> = playlists.toList()

        override suspend fun countTracks(): Int = tracks.size

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

        override suspend fun updateTrackMeta(playlistId: Long, bvid: String, cid: Long, videoTitle: String?, pageIndex: Int?) {
            val idx = tracks.indexOfFirst { it.playlistId == playlistId && it.bvid == bvid && it.cid == cid }
            if (idx >= 0) tracks[idx] = tracks[idx].copy(videoTitle = videoTitle, pageIndex = pageIndex)
        }

        override fun observePlaylist(id: Long): Flow<PlaylistEntity?> = flowOf(playlists.firstOrNull { it.id == id })
        override fun observeTracks(id: Long): Flow<List<PlaylistTrackEntity>> = flowOf(tracks.filter { it.playlistId == id }.sortedBy { it.order })
        override suspend fun updateName(playlistId: Long, name: String) {
            val idx = playlists.indexOfFirst { it.id == playlistId }
            if (idx >= 0) {
                playlists[idx] = playlists[idx].copy(name = name)
                playlistsFlow.value = playlists.toList()
            }
        }
        override suspend fun getPlaylist(id: Long): PlaylistEntity? = playlists.firstOrNull { it.id == id }
        override suspend fun getPlaylistByBvid(bvid: String): PlaylistEntity? =
            playlists.firstOrNull { it.sourceBvid == bvid }
        override fun observePlaylistByBvid(bvid: String): Flow<PlaylistEntity?> =
            flowOf(playlists.firstOrNull { it.sourceBvid == bvid })

        override suspend fun updateCover(playlistId: Long, cover: String) {
            val idx = playlists.indexOfFirst { it.id == playlistId }
            if (idx >= 0) playlists[idx] = playlists[idx].copy(cover = cover)
            playlistsFlow.value = playlists.toList()
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
        override fun replaceMediaItem(index: Int, item: MediaItem) { if (index in mediaItems.indices) mediaItems = mediaItems.toMutableList().also { it[index] = item } }

        override val mediaItemCount: Int get() = mediaItems.size
        override fun addMediaItem(index: Int, item: MediaItem) {
            mediaItems = mediaItems.toMutableList().also { it.add(index.coerceIn(0, it.size), item) }
        }
        override fun removeMediaItem(index: Int) {
            mediaItems = mediaItems.toMutableList().also { if (index in it.indices) it.removeAt(index) }
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
        override suspend fun get(bvid: String): HistoryEntity? = null
        override suspend fun getAll(): List<HistoryEntity> = emptyList()
        override fun observeAll(): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTotalListenMs(): Flow<Long> = flowOf(0L)
        override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = flowOf(emptyList())
        override fun observeTodayCount(todayStart: Long): Flow<Int> = flowOf(0)
        override suspend fun delete(bvid: String) {}
        override suspend fun clearAll() {}
    }

    class FakeBiliApi : BiliApi {
        override suspend fun search(keyword: String, searchType: String, page: Int): SearchDto = SearchDto(code = 0)
        override suspend fun view(bvid: String): ViewDto = ViewDto(code = 0)
        override suspend fun playUrl(bvid: String, cid: Long, fnval: Int, qn: Int?): PlayUrlDto =
            PlayUrlDto(code = 0, data = cn.debubu.tingbili.data.bilibili.dto.PlayUrlData(dash = cn.debubu.tingbili.data.bilibili.dto.DashData(audio = listOf(cn.debubu.tingbili.data.bilibili.dto.DashAudio(baseUrl = "https://audio.example.com/a.mp3")))))
        override suspend fun related(bvid: String): RelatedDto = RelatedDto()
        override suspend fun subtitle(bvid: String, cid: Long): SubtitleDto = SubtitleDto(code = 0)
        override suspend fun favFolders(pn: Int, ps: Int, upMid: Long): cn.debubu.tingbili.data.bilibili.dto.FavFolderDto = cn.debubu.tingbili.data.bilibili.dto.FavFolderDto()
        override suspend fun favMedias(mediaId: Long, pn: Int, ps: Int, keyword: String, order: String, type: Int, tid: Int, platform: String): cn.debubu.tingbili.data.bilibili.dto.FavDetailDto = cn.debubu.tingbili.data.bilibili.dto.FavDetailDto()
        override suspend fun toview(pn: Int, ps: Int): cn.debubu.tingbili.data.bilibili.dto.ToviewDto = cn.debubu.tingbili.data.bilibili.dto.ToviewDto()
        override suspend fun historyCursor(type: String, ps: Int, max: Long, viewAt: Long): cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto = cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto()
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
        // DataStore 必须跑在测试调度器上：否则读写发生在别的线程、不受虚拟时间控制，
        // runCurrent() 等不到它，而节流任务在它上面阻塞会和测试线程互等。
        val prefs = PreferencesRepository(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + dispatcher)
            ) { ctx.preferencesDataStoreFile("test_playlist_prefs_${System.nanoTime()}") }
        )
        val repo = BiliRepository(FakeBiliApi())
        val resolvers = cn.debubu.tingbili.data.bilibili.BiliMediaResolvers(repo, prefs)
        player = PlayerManager(playerHandle, FakeHistoryDao(), prefs, resolvers, resolvers, resolvers, FakePlayUrlCacheDao(), FakePlayQueueDao(), ctx)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `create playlist and add tracks dedup by bvid+cid`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("我的歌单")
        runCurrent()
        // vm.playlists uses Eagerly, value should reflect, but use dao as ground truth
        assertEquals(1, dao.getPlaylists().size)
        val pid = dao.getPlaylists().first().id

        val track = Track("BV1", 1, "a", "", "", 0, null)
        vm.addTracks(pid, listOf(track, track.copy()))
        runCurrent()
        // dedup via IGNORE, same bvid+cid only one row
        assertEquals(1, vm.tracks.first().size)
        // also verify dao directly
        assertEquals(1, dao.getTracks(pid).size)

        // different cid should be considered distinct
        vm.addTracks(pid, listOf(Track("BV1", 2, "b", "", "", 0, null)))
        runCurrent()
        assertEquals(2, vm.tracks.first().size)
    }

    @Test
    fun `reorder updates order field`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("reorder")
        runCurrent()
        val pid = dao.getPlaylists().first().id
        val t1 = Track("BV1", 1, "a", "", "", 0, null)
        val t2 = Track("BV1", 2, "b", "", "", 0, null)
        val t3 = Track("BV2", 1, "c", "", "", 0, null)
        vm.addTracks(pid, listOf(t1, t2, t3))
        runCurrent()
        assertEquals(listOf("a", "b", "c"), vm.tracks.first().map { it.title })

        vm.reorder(pid, 0, 2)
        runCurrent()
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
        runCurrent()
        val pid = dao.getPlaylists().first().id
        val t = Track("BV1", 1, "a", "", "", 0, null)
        vm.addTracks(pid, listOf(t))
        runCurrent()
        assertEquals(1, vm.tracks.first().size)
        vm.remove(pid, t)
        runCurrent()
        assertEquals(0, vm.tracks.first().size)
        assertEquals(0, dao.getTracks(pid).size)
    }

    @Test
    fun `playAll delegates to PlayerManager with queue`() = runTest(dispatcher) {
        try {
            val vm = PlaylistViewModel(dao, player)
            vm.create("play")
            runCurrent()
            val pid = dao.getPlaylists().first().id
            val tracks = listOf(Track("BV1", 1, "a", "", "", 1000, null), Track("BV1", 2, "b", "", "", 1000, null))
            vm.addTracks(pid, tracks)
            runCurrent()
            vm.playAll(pid)
            runCurrent()
            assertTrue(playerHandle.playCalled)
            assertEquals(2, playerHandle.mediaItems.size)
            assertEquals("BV1:1", playerHandle.mediaItems[0].mediaId)
            assertEquals("BV1:2", playerHandle.mediaItems[1].mediaId)
        } finally {
            // playAll -> play() 启动的 while(true){delay()} 节流任务必须先停掉：
            // runTest 收尾自己会 advanceUntilIdle，留着它们就永不返回。
            player.release()
        }
    }

    @Test
    fun `delete playlist clears tracks and selection`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("toDelete")
        runCurrent()
        val pid = dao.getPlaylists().first().id
        vm.addTracks(pid, listOf(Track("BV1", 1, "a", "", "", 0, null)))
        runCurrent()
        vm.deletePlaylist(pid)
        runCurrent()
        assertEquals(0, dao.getPlaylists().size)
    }

    @Test
    fun `addTracks dedup across multiple calls`() = runTest(dispatcher) {
        val vm = PlaylistViewModel(dao, player)
        vm.create("multi")
        runCurrent()
        val pid = dao.getPlaylists().first().id
        val t = Track("BV1", 1, "a", "", "", 0, null)
        vm.addTracks(pid, listOf(t))
        runCurrent()
        vm.addTracks(pid, listOf(t))
        runCurrent()
        assertEquals(1, vm.tracks.first().size)
    }
}

private fun android.content.Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
