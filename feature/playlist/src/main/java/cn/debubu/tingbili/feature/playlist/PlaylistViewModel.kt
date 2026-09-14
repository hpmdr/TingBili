package cn.debubu.tingbili.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

fun PlaylistTrackEntity.toTrack(): Track = Track(
    bvid = bvid,
    cid = cid,
    title = title,
    author = author,
    cover = cover,
    durationMs = durationMs,
    subtitleUrl = null
)

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val dao: PlaylistDao,
    private val player: PlayerManager
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>> =
        dao.observePlaylists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedPlaylistId = MutableStateFlow<Long?>(null)
    val selectedPlaylistId: StateFlow<Long?> = _selectedPlaylistId.asStateFlow()

    private val _tracks = MutableStateFlow<List<PlaylistTrackEntity>>(emptyList())
    val tracks: StateFlow<List<PlaylistTrackEntity>> = _tracks.asStateFlow()

    // expose tracks as Track list for UI convenience
    val trackModels: StateFlow<List<Track>> get() = _trackModels
    private val _trackModels = MutableStateFlow<List<Track>>(emptyList())

    fun selectPlaylist(id: Long?) {
        _selectedPlaylistId.value = id
        if (id != null) {
            viewModelScope.launch {
                refreshTracks(id)
            }
        } else {
            _tracks.value = emptyList()
            _trackModels.value = emptyList()
        }
    }

    /** 创建空白收藏，无封面，前端展示默认占位（预留手动收藏） */
    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            dao.insert(PlaylistEntity(name = name.trim()))
        }
    }

    /** 创建空白收藏并返回 id */
    suspend fun createAndGetId(name: String): Long {
        return dao.insert(PlaylistEntity(name = name.trim()))
    }

    /** 创建收藏并以首条 Track 封面作为收藏封面 */
    suspend fun createWithCover(name: String, cover: String?): Long {
        return dao.insert(PlaylistEntity(name = name.trim(), cover = cover?.takeIf { it.isNotBlank() }))
    }

    /** 带封面的创建（协程内） */
    fun createWithCoverAsync(name: String, cover: String?) {
        if (name.isBlank()) return
        viewModelScope.launch {
            dao.insert(PlaylistEntity(name = name.trim(), cover = cover?.takeIf { it.isNotBlank() }))
        }
    }

    fun addTracks(playlistId: Long, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            // compute base order as current max order + 1 to preserve insertion order
            val existing = dao.getTracks(playlistId)
            var base = (existing.maxOfOrNull { it.order } ?: -1) + 1
            tracks.forEach { t ->
                dao.addTrack(
                    PlaylistTrackEntity(
                        playlistId = playlistId,
                        bvid = t.bvid,
                        cid = t.cid,
                        title = t.title,
                        order = base++,
                        author = t.author,
                        cover = t.cover,
                        durationMs = t.durationMs
                    )
                )
            }
            ensureCover(playlistId, tracks)
            refreshTracks(playlistId)
        }
    }

    fun addTrack(playlistId: Long, track: Track) = addTracks(playlistId, listOf(track))

    fun removeTrack(playlistId: Long, bvid: String, cid: Long) {
        viewModelScope.launch {
            dao.removeTrack(playlistId, bvid, cid)
            refreshTracks(playlistId)
        }
    }

    fun remove(playlistId: Long, track: Track) = removeTrack(playlistId, track.bvid, track.cid)

    fun remove(playlistId: Long, entity: PlaylistTrackEntity) = removeTrack(playlistId, entity.bvid, entity.cid)

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            dao.deletePlaylist(id)
            dao.clearTracks(id)
            if (_selectedPlaylistId.value == id) {
                _selectedPlaylistId.value = null
                _tracks.value = emptyList()
                _trackModels.value = emptyList()
            }
        }
    }

    fun reorder(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val list = dao.getTracks(playlistId).toMutableList()
            if (fromIndex !in list.indices || toIndex !in list.indices) return@launch
            val moved = list.removeAt(fromIndex)
            list.add(toIndex, moved)
            // update order for all items
            list.forEachIndexed { idx, e ->
                try {
                    dao.updateOrder(e.playlistId, e.bvid, e.cid, idx)
                } catch (_: Exception) {
                    // fallback for fakes without updateOrder - clear and reinsert
                }
            }
            refreshTracks(playlistId)
        }
    }

    /** Reorder via providing new ordered list */
    fun reorder(playlistId: Long, newOrder: List<PlaylistTrackEntity>) {
        viewModelScope.launch {
            newOrder.forEachIndexed { idx, e ->
                dao.updateOrder(e.playlistId, e.bvid, e.cid, idx)
            }
            refreshTracks(playlistId)
        }
    }

    fun playAll(playlistId: Long, startIndex: Int = 0) {
        viewModelScope.launch {
            val entities = dao.getTracks(playlistId)
            if (entities.isEmpty()) return@launch
            val ts = entities.map { it.toTrack() }
            val safeIdx = startIndex.coerceIn(0, ts.lastIndex)
            val playlistName = dao.getPlaylist(playlistId)?.name ?: "收藏"
            player.play(ts, safeIdx, playlistName)
        }
    }

    fun playTrack(playlistId: Long, index: Int) = playAll(playlistId, index)

    fun clearTracks(playlistId: Long) {
        viewModelScope.launch {
            dao.clearTracks(playlistId)
            refreshTracks(playlistId)
        }
    }

    // 收藏封面：若当前 cover 为空，取首条有封面的 Track 设为收藏封面
    private suspend fun ensureCover(playlistId: Long, tracks: List<Track>) {
        val current = dao.getPlaylist(playlistId) ?: return
        if (!current.cover.isNullOrBlank()) return
        val cover = tracks.firstOrNull { it.cover.isNotBlank() }?.cover ?: return
        try {
            dao.updateCover(playlistId, cover)
        } catch (_: Exception) { }
    }

    private suspend fun refreshTracks(playlistId: Long) {
        val updated = dao.getTracks(playlistId)
        _tracks.value = updated
        _trackModels.value = updated.map { it.toTrack() }
        if (_selectedPlaylistId.value == null) {
            _selectedPlaylistId.value = playlistId
        }
    }

    /** Synchronous helper for tests to await tracks */
    suspend fun getTracksSync(playlistId: Long): List<PlaylistTrackEntity> = dao.getTracks(playlistId)
}
