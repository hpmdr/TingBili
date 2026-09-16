package cn.debubu.tingbili.feature.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    private val historyDao: HistoryDao,
    private val repo: BiliRepository,
    private val player: PlayerManager
) : ViewModel() {

    val playlistId: Long = (savedStateHandle.get<Long>("playlistId") ?: savedStateHandle.get<Int>("playlistId")?.toLong() ?: savedStateHandle.get<String>("playlistId")?.toLongOrNull() ?: error("Missing playlistId in " + savedStateHandle.keys()))

    val playlist: StateFlow<PlaylistEntity?> =
        dao.observePlaylist(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val tracks: StateFlow<List<PlaylistTrackEntity>> =
        dao.observeTracks(playlistId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** "bvid:cid" -> 该集的播放记录，用于列表显示“上次听到 P{n} · 时间” */
    val progress: StateFlow<Map<String, HistoryEntity>> = historyDao.observeAll()
        .map { list -> list.associateBy { "${it.bvid}:${it.cid}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { backfillMissingTrackMeta() }
    }

    /**
     * videoTitle / pageIndex 是后加的列，老收藏里为空。打开详情时按 BV 拉一次 view 接口补齐，
     * 这样列表能显示 P 号、从收藏播放时播放页也能显示合集名。失败就静默跳过（下次打开再试）。
     */
    private suspend fun backfillMissingTrackMeta() {
        val missing = dao.getTracks(playlistId)
            .filter { it.pageIndex == null || it.videoTitle.isNullOrBlank() }
        if (missing.isEmpty()) return
        missing.map { it.bvid }.distinct().forEach { bvid ->
            val tracks = when (val result = repo.getView(bvid)) {
                is Result.Success -> result.data
                is Result.Error -> return@forEach
            }
            val byCid = tracks.associateBy { it.cid }
            missing.asSequence()
                .filter { it.bvid == bvid }
                .forEach { entity ->
                    val track = byCid[entity.cid] ?: return@forEach
                    if (entity.pageIndex == track.pageIndex && entity.videoTitle == track.videoTitle) return@forEach
                    dao.updateTrackMeta(
                        playlistId = entity.playlistId,
                        bvid = entity.bvid,
                        cid = entity.cid,
                        videoTitle = track.videoTitle,
                        pageIndex = track.pageIndex
                    )
                }
        }
    }

    fun consumeMessage() { _message.value = null }

    fun playAll(startIndex: Int = 0) {
        viewModelScope.launch {
            val entities = dao.getTracks(playlistId)
            if (entities.isEmpty()) {
                _message.value = "暂无内容"
                return@launch
            }
            val safe = startIndex.coerceIn(0, entities.lastIndex)
            player.play(entities.map { it.toTrack() }, safe, playlist.value?.name ?: "收藏")
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
