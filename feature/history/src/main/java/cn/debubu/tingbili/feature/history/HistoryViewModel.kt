package cn.debubu.tingbili.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.repository.HistoryRepository
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.model.toTrack
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val player: PlayerManager,
    private val repo: BiliRepository,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    /**
     * 一个播单（BV / 收藏夹）一行，按最后播放时间倒序。
     * 用 WhileSubscribed 而不是 Eagerly：播放中每 10s 会写历史，Eagerly 会让历史页不可见时也全表重查
     * （REVIEW_REPORT R8/B-04）；离开页面 5s 后停，回来立刻重新订阅。
     */
    val history: StateFlow<List<HistoryEntity>> =
        historyRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前仍存在的收藏夹 id：null=尚未加载（先按可用展示），决定历史行「收藏夹」跳转是否可用 */
    val folderIds: StateFlow<Set<Long>?> = favoritesRepository.observeFolders()
        .map { folders -> folders.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** bvid -> 该视频的 Track 列表（标题/UP主/封面/每P时长），用于列表展示，按 bvid 懒加载缓存 */
    private val _videoInfo = MutableStateFlow<Map<String, List<Track>>>(emptyMap())
    val videoInfo: StateFlow<Map<String, List<Track>>> = _videoInfo.asStateFlow()

    private val inflight = mutableSetOf<String>()

    private val _startingResume = MutableStateFlow(false)
    private val _pendingKey = MutableStateFlow<String?>(null)

    /**
     * 续播准备中。resume() 要先 getView 再由 PlayerManager 取音源，两跳网络等待更长，
     * 必须给出行内反馈并禁止重复点击，否则用户会以为没点上。
     */
    val startingResume: StateFlow<Boolean> =
        combine(_startingResume, player.state) { starting, s -> starting || s.isLoading }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 正在等起播的那条记录，键为 "sourceType:sourceId" */
    val pendingKey: StateFlow<String?> = _pendingKey.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() {
        _message.value = null
    }

    /** 详情并发上限：进页瞬间 60 个 BV 不会同时打出去，超限的排队 */
    private val videoInfoSemaphore = Semaphore(4)

    /**
     * 可见才拉：由列表行进入组合时触发（LaunchedEffect per bvid）。
     * 之前是 init 里全量 distinct 拉，进页瞬间 N 个 view 请求直接撞 412。
     */
    fun prefetch(bvid: String) {
        if (bvid.isBlank() || _videoInfo.value.containsKey(bvid) || !inflight.add(bvid)) return
        viewModelScope.launch {
            videoInfoSemaphore.withPermit {
                when (val result = repo.getView(bvid)) {
                    is Result.Success -> _videoInfo.update { it + (bvid to result.data) }
                    is Result.Error -> inflight.remove(bvid) // 失败时允许下次重试
                }
            }
        }
    }

    /**
     * 续播：
     * - 收藏夹播单：夹还在且这一集还在 → 恢复夹当前队列；
     * - 否则回退 BV 分 P；
     * - BV 播单：直接恢复 BV 分 P。
     */
    fun resume(h: HistoryEntity) {
        val key = "${h.sourceType}:${h.sourceId}"
        if (_startingResume.value) return
        viewModelScope.launch {
            _pendingKey.value = key
            _startingResume.value = true
            try {
                if (h.sourceType == HistoryEntity.SOURCE_FOLDER) {
                    if (resumeFolder(h)) return@launch
                }
                resumeBv(h)
            } finally {
                _startingResume.value = false
                _pendingKey.value = null
            }
        }
    }

    /** @return true=已在收藏夹队列起播；false=需要回退 BV */
    private suspend fun resumeFolder(h: HistoryEntity): Boolean {
        val folderId = h.sourceId.toLongOrNull()
        if (folderId == null) {
            _message.value = "原收藏夹已不可用，已按 BV 续播"
            return false
        }
        val folder = favoritesRepository.getFolder(folderId)
        if (folder == null) {
            _message.value = "原收藏夹已删除，已按 BV 续播"
            return false
        }
        val tracks = favoritesRepository.getTracks(folderId).map { it.toTrack() }
        val idx = tracks.indexOfFirst { it.bvid == h.bvid && it.cid == h.cid }
        if (idx < 0) {
            _message.value = "该集已移出收藏夹，已按 BV 续播"
            return false
        }
        // 同集去重：playOrResume 内同集忽略 startPositionMs，保持现进度续播/只跳转
        player.playOrResume(
            tracks,
            idx,
            folder.name,
            startPositionMs = h.positionMs.coerceAtLeast(0L),
            sourceFolderId = folderId,
        )
        return true
    }

    private suspend fun resumeBv(h: HistoryEntity) {
        val tracks = when (val result = repo.getView(h.bvid)) {
            is Result.Success -> result.data
            is Result.Error -> {
                _message.value = result.msg.ifBlank { "续播失败" }
                return
            }
        }
        if (tracks.isEmpty()) {
            _message.value = "暂无分 P"
            return
        }
        val idx = tracks.indexOfFirst { it.cid == h.cid }
        if (idx < 0) {
            _message.value = "该分 P 已失效"
            return
        }
        // 同集去重：同集忽略历史落点，保持现进度续播/只跳转
        player.playOrResume(
            tracks,
            idx,
            "播放历史",
            startPositionMs = h.positionMs.coerceAtLeast(0L),
        )
    }

    /** Back-compat overload for tests that pass bvid/cid directly */
    fun resume(bvid: String, cid: Long, positionMs: Long) {
        resume(HistoryEntity(bvid = bvid, cid = cid, positionMs = positionMs))
    }

    fun clearAll() {
        viewModelScope.launch { historyRepository.clearAll() }
    }

    /** 删一条播单历史；不会删除收藏夹本身 */
    fun delete(h: HistoryEntity) {
        viewModelScope.launch { historyRepository.deleteSource(h.sourceType, h.sourceId) }
    }
}

/** Extension to bridge brief's getOrNull expectation for custom Result */
private fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> data
    is Result.Error -> null
}
