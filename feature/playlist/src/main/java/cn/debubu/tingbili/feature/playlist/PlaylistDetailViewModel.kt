package cn.debubu.tingbili.feature.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 收藏详情 ViewModel（路由参数 playlistId）。
 * 监听 playlist + tracks 的实时流，操作（播放/排序/删除/重命名）均在此完成。
 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dao: PlaylistDao,
    private val player: PlayerManager
) : ViewModel() {

    val playlistId: Long = (savedStateHandle.get<Long>("playlistId") ?: savedStateHandle.get<Int>("playlistId")?.toLong() ?: savedStateHandle.get<String>("playlistId")?.toLongOrNull() ?: error("Missing playlistId in " + savedStateHandle.keys()))

    val playlist: StateFlow<PlaylistEntity?> =
        dao.observePlaylist(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tracks: StateFlow<List<PlaylistTrackEntity>> =
        dao.observeTracks(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun playAll(startIndex: Int = 0) {
        viewModelScope.launch {
            val entities = dao.getTracks(playlistId)
            if (entities.isEmpty()) {
                _message.value = "暂无内容"
                return@launch
            }
            val safe = startIndex.coerceIn(0, entities.lastIndex)
            player.play(entities.map { it.toTrack() }, safe)
        }
    }

    fun playAt(index: Int) = playAll(index)

    fun remove(bvid: String, cid: Long) {
        viewModelScope.launch { dao.removeTrack(playlistId, bvid, cid) }
    }

    fun remove(entity: PlaylistTrackEntity) = remove(entity.bvid, entity.cid)

    fun reorder(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val list = dao.getTracks(playlistId).toMutableList()
            if (fromIndex !in list.indices || toIndex !in list.indices) return@launch
            val moved = list.removeAt(fromIndex)
            list.add(toIndex, moved)
            list.forEachIndexed { idx, e ->
                try { dao.updateOrder(e.playlistId, e.bvid, e.cid, idx) } catch (_: Exception) { }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clearTracks(playlistId) }
    }

    fun rename(newName: String) {
        val name = newName.trim()
        if (name.isBlank()) {
            _message.value = "名称不能为空"
            return
        }
        viewModelScope.launch {
            try { dao.updateName(playlistId, name) } catch (_: Exception) { }
        }
    }

    fun deletePlaylist(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            dao.deletePlaylist(playlistId)
            dao.clearTracks(playlistId)
            onDone()
        }
    }
}
