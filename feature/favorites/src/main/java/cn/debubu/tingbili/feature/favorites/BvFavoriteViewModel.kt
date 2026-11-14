package cn.debubu.tingbili.feature.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.BvFavoritePageEntity
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.repository.HistoryRepository
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.latestPerTrack
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.model.toTrack
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import cn.debubu.tingbili.core.media.PlayerManager
import cn.debubu.tingbili.data.bilibili.BiliRepository
import cn.debubu.tingbili.data.bilibili.dto.toFavoriteSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface BvRefreshState {
    data object Idle : BvRefreshState
    data object Refreshing : BvRefreshState
    data object Success : BvRefreshState
    data class Failed(
        val reason: String,
        val invalid: Boolean = false,
    ) : BvRefreshState
}

@HiltViewModel
class BvFavoriteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val favoritesRepository: FavoritesRepository,
    private val biliRepository: BiliRepository,
    private val historyRepository: HistoryRepository,
    private val player: PlayerManager,
) : ViewModel() {

    val bvid: String = savedStateHandle.get<String>("bvid")
        ?: error("Missing bvid in " + savedStateHandle.keys())

    val favorite: StateFlow<BvFavoriteEntity?> =
        favoritesRepository.observeBvFavorite(bvid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<BvFavoritePageEntity>> =
        favoritesRepository.observeBvPages(bvid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<FavoriteFolderEntity>> =
        favoritesRepository.observeFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cachedTracks: StateFlow<List<Track>> =
        combine(favorite, pages) { fav, pageList ->
            if (fav == null) emptyList() else pageList.map { it.toTrack(fav) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshState = MutableStateFlow<BvRefreshState>(BvRefreshState.Idle)
    val refreshState: StateFlow<BvRefreshState> = _refreshState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _startingPlay = MutableStateFlow(false)
    private val _pendingIndex = MutableStateFlow<Int?>(null)

    val startingPlay: StateFlow<Boolean> =
        combine(_startingPlay, player.state) { starting, s -> starting || s.isLoading }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pendingIndex: StateFlow<Int?> = _pendingIndex.asStateFlow()

    val progress: StateFlow<Map<String, HistoryEntity>> =
        historyRepository.observeByBvid(bvid)
            .map { list -> list.latestPerTrack() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val playingTrack: StateFlow<PlayingTrack?> = player.state
        .map { s ->
            if (s.isPlaying || s.isLoading) {
                s.currentTrack?.let {
                    PlayingTrack(key = "${it.bvid}:${it.cid}", positionMs = s.positionMs / 1000L * 1000L)
                }
            } else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val focus: StateFlow<TrackFocus?> =
        combine(pages, progress, playingTrack) { list, prog, playing ->
            fun keyOf(e: BvFavoritePageEntity) = "${e.bvid}:${e.cid}"
            fun idxOfKey(k: String) = list.indexOfFirst { keyOf(it) == k }.takeIf { i -> i >= 0 }

            playing?.key?.let { idxOfKey(it) }?.let { return@combine TrackFocus(it, isPlaying = true) }

            list.filter { prog.containsKey(keyOf(it)) }
                .maxByOrNull { prog[keyOf(it)]?.updatedAt ?: 0L }
                ?.let { idxOfKey(keyOf(it)) }
                ?.let { TrackFocus(it, isPlaying = false) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            runCatching { favoritesRepository.ensureDefaultFolder() }
        }
    }

    fun refresh() {
        if (_refreshState.value is BvRefreshState.Refreshing) return
        viewModelScope.launch {
            _refreshState.value = BvRefreshState.Refreshing
            when (val result = biliRepository.getViewDetail(bvid, forceRefresh = true)) {
                is Result.Success -> {
                    runCatching {
                        favoritesRepository.refreshBvFavorite(result.data.toFavoriteSnapshot())
                    }.onFailure {
                        _message.value = it.message ?: "写入缓存失败"
                    }
                    _refreshState.value = BvRefreshState.Success
                }
                is Result.Error -> {
                    _refreshState.value = result.toBvRefreshFailure()
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun playAll(startIndex: Int = 0) {
        if (_startingPlay.value) return
        val fav = favorite.value ?: return
        viewModelScope.launch {
            _pendingIndex.value = startIndex
            _startingPlay.value = true
            try {
                val pageList = pages.value
                if (pageList.isEmpty()) {
                    _message.value = "暂无分P"
                    return@launch
                }
                val tracks = pageList.map { it.toTrack(fav) }
                val safe = startIndex.coerceIn(0, tracks.lastIndex)
                // 同集去重：正在播则只跳转，暂停则原进度续播，不重建队列
                player.playOrResume(tracks, safe, fav.title)
            } finally {
                _startingPlay.value = false
                _pendingIndex.value = null
            }
        }
    }

    fun playAt(index: Int) = playAll(index)

    fun removeFavorite(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { favoritesRepository.removeBvFavorite(bvid) }
                .onSuccess { onDone() }
                .onFailure { _message.value = it.message ?: "取消收藏失败" }
        }
    }

    fun addToFolders(folderIds: Set<Long>, tracks: List<Track>) {
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

internal fun Result.Error.toBvRefreshFailure(): BvRefreshState.Failed {
    val text = msg
    val invalid = listOf("不可见", "不存在", "已删除", "失效", "啥都木有", "审核").any {
        text.contains(it)
    }
    val network = throwable is IOException
    val reason = when {
        invalid -> text.ifBlank { "BV 已失效或不可见" }
        network -> "网络异常，请检查网络连接"
        text.isNotBlank() -> text
        else -> "刷新失败"
    }
    return BvRefreshState.Failed(reason = reason, invalid = invalid)
}
