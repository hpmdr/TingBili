package cn.debubu.tingbili.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val dao: HistoryDao,
    private val player: PlayerManager,
    private val repo: BiliRepository
) : ViewModel() {

    val history: StateFlow<List<HistoryEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** bvid -> 该视频的 Track 列表（标题/UP主/封面/每P时长），用于列表展示，按 bvid 懒加载缓存 */
    private val _videoInfo = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val videoInfo: StateFlow<Map<String, List<Track>>> = _videoInfo.asStateFlow()

    private val inflight = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            history.collect { list ->
                list.map { it.bvid }.distinct().forEach { bvid -> loadVideoInfo(bvid) }
            }
        }
    }

    private suspend fun loadVideoInfo(bvid: String) {
        if (_videoInfo.value.containsKey(bvid) || !inflight.add(bvid)) return
        when (val result = repo.getView(bvid)) {
            is Result.Success -> _videoInfo.update { it + (bvid to result.data) }
            is Result.Error -> inflight.remove(bvid) // 失败时允许下次重试
        }
    }

    fun resume(h: HistoryEntity) {
        viewModelScope.launch {
            val result = repo.getView(h.bvid)
            val tracks = when (result) {
                is Result.Success -> result.data
                is Result.Error -> return@launch
            }
            if (tracks.isEmpty()) return@launch
            val idx = tracks.indexOfFirst { it.cid == h.cid }.coerceAtLeast(0)
            player.play(tracks, idx)
            player.seekTo(h.positionMs)
        }
    }

    /** Back-compat overload for tests that pass bvid/cid directly */
    fun resume(bvid: String, cid: Long, positionMs: Long) {
        resume(HistoryEntity(bvid = bvid, cid = cid, positionMs = positionMs))
    }

    fun clearAll() {
        viewModelScope.launch { dao.clearAll() }
    }

    fun delete(bvid: String, cid: Long) {
        viewModelScope.launch { dao.delete(bvid, cid) }
    }
}

/** Extension to bridge brief's getOrNull expectation for custom Result */
private fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> data
    is Result.Error -> null
}
