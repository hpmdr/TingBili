package cn.debubu.tingbili.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.repository.HistoryRepository
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.latestPerTrack
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.toFavoriteSnapshot
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Success(val video: ViewData, val tracks: List<Track>) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

/**
 * 相关推荐区状态：与详情主内容独立加载，失败不影响主内容展示。
 */
sealed interface RelatedUiState {
    /** 从未加载过：相关推荐是懒加载，用户首次切到该 Tab 时才请求 */
    data object Idle : RelatedUiState
    data object Loading : RelatedUiState
    data class Success(val tracks: List<Track>) : RelatedUiState
    data class Error(val message: String) : RelatedUiState
}

/**
 * 列表里被重点标出的那一项。一个列表同时只会有一个。
 * @param index 分P 下标
 * @param isPlaying true=正在播这一项；false=历史续播点
 */
data class DetailFocus(val index: Int, val isPlaying: Boolean)

/** 正在播放的分P 快照，供列表行渲染「正在播放 + 当前进度」 */
data class PlayingTrack(val key: String, val positionMs: Long)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BiliRepository,
    private val player: PlayerManager,
    private val favoritesRepository: FavoritesRepository,
    private val historyRepository: HistoryRepository
) : ViewModel() {

    val bvid: String = checkNotNull(savedStateHandle["bvid"])

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _related = MutableStateFlow<RelatedUiState>(RelatedUiState.Idle)
    val related: StateFlow<RelatedUiState> = _related.asStateFlow()

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()

    val folders: StateFlow<List<FavoriteFolderEntity>> = favoritesRepository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _startingPlay = MutableStateFlow(false)
    private val _pendingIndex = MutableStateFlow<Int?>(null)

    /**
     * 播放启动中，取音源要等网络。接 [PlayerManager.isLoading]，
     * 让「播放全部 / 某个分P」在等待期间有明确反馈并禁止重复点击。
     */
    val startingPlay: StateFlow<Boolean> =
        combine(_startingPlay, player.state) { starting, s -> starting || s.isLoading }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 正在等起播的那一行索引 */
    val pendingIndex: StateFlow<Int?> = _pendingIndex.asStateFlow()

    /** "bvid:cid" -> 播放记录（只订阅本 BV，播放器每秒写全表时不再全量 associateBy） */
    val progress: StateFlow<Map<String, HistoryEntity>> = historyRepository.observeByBvid(bvid)
        .map { list -> list.latestPerTrack() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 正在播放的分P 快照（键 + 整秒进度），用于替代该行的「上次听到」 */
    val playingTrack: StateFlow<PlayingTrack?> = player.state
        .map { s ->
            if (s.isPlaying || s.isLoading) {
                s.currentTrack?.let { PlayingTrack("${it.bvid}:${it.cid}", s.positionMs / 1000L * 1000L) }
            } else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 唯一重点项，同时也是「继续播放」的落点：正在播的这一项优先，
     * 否则取播放记录里最近的一集；都没听过则为 null（按钮回到「播放全部」）。
     */
    val focus: StateFlow<DetailFocus?> =
        combine(state, progress, playingTrack) { s, prog, playing ->
            val list = (s as? DetailUiState.Success)?.tracks ?: return@combine null
            fun keyOf(t: Track) = "${t.bvid}:${t.cid}"
            fun idxOfKey(k: String) = list.indexOfFirst { keyOf(it) == k }.takeIf { i -> i >= 0 }

            playing?.key?.let { idxOfKey(it) }?.let { return@combine DetailFocus(it, isPlaying = true) }

            list.filter { prog.containsKey(keyOf(it)) }
                .maxByOrNull { prog[keyOf(it)]?.updatedAt ?: 0L }
                ?.let { idxOfKey(keyOf(it)) }
                ?.let { DetailFocus(it, isPlaying = false) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        load()
        observeFavorite()
        viewModelScope.launch {
            runCatching { favoritesRepository.ensureDefaultFolder() }
        }
    }

    private fun observeFavorite() {
        viewModelScope.launch {
            favoritesRepository.observeIsBvFavorited(bvid).collect { favorited ->
                _isFavorited.value = favorited
            }
        }
    }

    fun load() {
        _state.value = DetailUiState.Loading
        viewModelScope.launch {
            when (val result = repo.getViewDetail(bvid)) {
                is Result.Success -> {
                    val video = result.data
                    _state.value = DetailUiState.Success(video, video.toTracks())
                }
                is Result.Error -> _state.value = DetailUiState.Error(result.msg ?: "加载失败")
            }
        }
    }

    /**
     * 相关推荐懒加载：只在从未加载过时请求。切回 Tab / 重组 / 旋转恢复都不会重复请求，
     * 出错停在 Error 由推荐区重试按钮显式重拉。
     */
    fun ensureRelatedLoaded() {
        if (_related.value is RelatedUiState.Idle) loadRelated()
    }

    fun loadRelated() {
        _related.value = RelatedUiState.Loading
        viewModelScope.launch {
            when (val result = repo.getRelated(bvid)) {
                is Result.Success -> _related.value = RelatedUiState.Success(result.data)
                is Result.Error -> _related.value = RelatedUiState.Error(result.msg ?: "加载失败")
            }
        }
    }

    fun play(tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        // 重复点击在这里就被挡下并保持 loading 反馈，不让互斥锁静默丢弃
        if (_startingPlay.value) return
        viewModelScope.launch {
            _pendingIndex.value = index
            _startingPlay.value = true
            try {
                // 同集去重：正在播则只跳转（调用方负责 onPlayNavigate），暂停则原进度续播，不重建队列
                player.playOrResume(tracks, index)
            } finally {
                _startingPlay.value = false
                _pendingIndex.value = null
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            if (_isFavorited.value) {
                favoritesRepository.removeBvFavorite(bvid)
                _message.value = "已取消 BV 收藏"
                return@launch
            }
            val current = state.value as? DetailUiState.Success ?: run {
                _message.value = "视频信息尚未加载"
                return@launch
            }
            favoritesRepository.addBvFavorite(current.video.toFavoriteSnapshot())
            _message.value = "已收藏整本 BV"
        }
    }

    /** 长按弹层确认：把选中的 P 加入选中的收藏夹，只加不删。 */
    fun addTracksToFolders(folderIds: Set<Long>, tracks: List<Track>) {
        if (folderIds.isEmpty() || tracks.isEmpty()) return
        viewModelScope.launch {
            runCatching { favoritesRepository.addTracksToFolders(folderIds, tracks) }
                .onSuccess { result ->
                    _message.value = when {
                        result.added > 0 && result.skipped > 0 ->
                            "已收藏 ${result.added} 个P，${result.skipped} 个已存在"
                        result.added > 0 -> "已收藏 ${result.added} 个P"
                        else -> "这些 P 已在所选收藏夹中"
                    }
                }
                .onFailure { _message.value = it.message ?: "加入收藏夹失败" }
        }
    }

    /** 弹层内新建收藏夹：创建后立即收取当前选中的 P。 */
    fun createFolderAndAdd(name: String, tracks: List<Track>) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || tracks.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val folderId = favoritesRepository.createFolder(trimmed)
                favoritesRepository.addTracksToFolders(setOf(folderId), tracks)
            }.onSuccess { result ->
                _message.value = "已新建「$trimmed」并收藏 ${result.added} 个P"
            }.onFailure { _message.value = it.message ?: "新建收藏夹失败" }
        }
    }
}
