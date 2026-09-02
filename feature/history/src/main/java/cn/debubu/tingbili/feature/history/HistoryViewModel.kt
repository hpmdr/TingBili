package cn.debubu.tingbili.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val dao: HistoryDao,
    private val player: PlayerManager,
    private val repo: BiliRepository
) : ViewModel() {

    val history: StateFlow<List<HistoryEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
