package cn.debubu.tingbili.feature.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.data.repository.HistoryRepository
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.latestPerTrack
import cn.debubu.tingbili.core.data.model.toTrack
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import cn.debubu.tingbili.core.media.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 正在播放的曲目快照，供列表行渲染「正在播放 + 当前进度」 */
data class PlayingTrack(val key: String, val positionMs: Long)

/**
 * 列表里被重点标出的那一项。一个列表同时只会有一个。
 * @param index 夹内下标
 * @param isPlaying true=正在播这一项（显示实时进度）；false=历史续播点（显示上次听到的进度）
 */
data class TrackFocus(val index: Int, val isPlaying: Boolean)

@HiltViewModel
class FavoriteFolderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: FavoritesRepository,
    private val historyRepository: HistoryRepository,
    private val player: PlayerManager,
) : ViewModel() {

    val folderId: Long =
        savedStateHandle.get<Long>("folderId")
            ?: savedStateHandle.get<Int>("folderId")?.toLong()
            ?: savedStateHandle.get<String>("folderId")?.toLongOrNull()
            ?: error("Missing folderId in " + savedStateHandle.keys())

    val folder: StateFlow<FavoriteFolderEntity?> =
        repo.observeFolder(folderId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracks: StateFlow<List<FavoriteTrackEntity>> =
        repo.observeTracks(folderId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "bvid:cid" -> 该集的播放记录（只订阅本夹相关 BV，空夹直接空 Map，防 Room IN() 空集崩） */
    @OptIn(ExperimentalCoroutinesApi::class)
    val progress: StateFlow<Map<String, HistoryEntity>> = tracks
        .map { list -> list.map { it.bvid }.distinct() }
        .distinctUntilChanged()
        .flatMapLatest { bvids ->
            if (bvids.isEmpty()) flowOf(emptyList())
            else historyRepository.observeByBvids(bvids)
        }
        .map { list -> list.latestPerTrack() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _startingPlay = MutableStateFlow(false)
    private val _pendingIndex = MutableStateFlow<Int?>(null)

    val startingPlay: StateFlow<Boolean> =
        combine(_startingPlay, player.state) { starting, s -> starting || s.isLoading }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pendingIndex: StateFlow<Int?> = _pendingIndex.asStateFlow()

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
        combine(tracks, progress, playingTrack) { list, prog, playing ->
            fun keyOf(e: FavoriteTrackEntity) = "${e.bvid}:${e.cid}"
            fun idxOfKey(k: String) = list.indexOfFirst { keyOf(it) == k }.takeIf { i -> i >= 0 }

            playing?.key?.let { idxOfKey(it) }?.let { return@combine TrackFocus(it, isPlaying = true) }

            list.filter { prog.containsKey(keyOf(it)) }
                .maxByOrNull { prog[keyOf(it)]?.updatedAt ?: 0L }
                ?.let { idxOfKey(keyOf(it)) }
                ?.let { TrackFocus(it, isPlaying = false) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun consumeMessage() {
        _message.value = null
    }

    fun playAll(startIndex: Int = 0) {
        if (_startingPlay.value) return
        viewModelScope.launch {
            _pendingIndex.value = startIndex
            _startingPlay.value = true
            try {
                val entities = repo.getTracks(folderId)
                if (entities.isEmpty()) {
                    _message.value = "暂无内容"
                    return@launch
                }
                val safe = startIndex.coerceIn(0, entities.lastIndex)
                // 同集去重：正在播则只跳转，暂停则原进度续播，不重建队列
                player.playOrResume(
                    entities.map { it.toTrack() },
                    safe,
                    folder.value?.name ?: "收藏夹",
                    sourceFolderId = folderId,
                )
            } finally {
                _startingPlay.value = false
                _pendingIndex.value = null
            }
        }
    }

    fun playAt(index: Int) = playAll(index)

    fun remove(entity: FavoriteTrackEntity) {
        viewModelScope.launch {
            repo.removeTrack(folderId, entity.bvid, entity.cid)
        }
    }

    fun rename(newName: String) {
        val name = newName.trim()
        if (name.isBlank()) {
            _message.value = "名称不能为空"
            return
        }
        viewModelScope.launch {
            runCatching { repo.renameFolder(folderId, name) }
                .onFailure { _message.value = it.message ?: "重命名失败" }
        }
    }

    fun deleteFolder(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val deleted = runCatching { repo.deleteFolder(folderId) }
                .getOrElse { false }
            if (deleted) {
                onDone()
            } else {
                _message.value = "默认收藏夹不能删除"
            }
        }
    }
}
