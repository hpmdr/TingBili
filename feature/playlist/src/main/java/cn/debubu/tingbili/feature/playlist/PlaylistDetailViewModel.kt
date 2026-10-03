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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 正在播放的曲目快照，供列表行渲染「正在播放 + 当前进度」 */
data class PlayingTrack(val key: String, val positionMs: Long)

/**
 * 列表里被重点标出的那一项。一个列表同时只会有一个。
 * @param index 集合内下标
 * @param isPlaying true=正在播这一项（显示实时进度）；false=历史续播点（显示上次听到的进度）
 */
data class TrackFocus(val index: Int, val isPlaying: Boolean)

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

    private val _startingPlay = MutableStateFlow(false)
    private val _pendingIndex = MutableStateFlow<Int?>(null)

    /**
     * 播放启动中。PlayerManager 取音源要发网络请求，这段时间里按钮/行必须给反馈：
     * 否则页面纹丝不动，用户会以为没点上而反复点击。
     * `_startingPlay` 覆盖「点击→取音源」，`PlayerManager.isLoading` 覆盖「已取到→真正起播」。
     */
    val startingPlay: StateFlow<Boolean> =
        combine(_startingPlay, player.state) { starting, s -> starting || s.isLoading }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 正在等起播的那一行索引；用于在列表项上就地显示进度 */
    val pendingIndex: StateFlow<Int?> = _pendingIndex.asStateFlow()

    /**
     * 正在播放的那一集：键 + 实时进度。
     * 列表里用它替代「上次听到」——正在播的条目，历史进度马上就会被冲掉，
     * 显示旧的反而误导；直接给当前进度更有用。
     * 位置按整秒取整：播放器每 500ms 推一次，而文案精度只有秒。
     * 只在真正播放/缓冲时给出：暂停或播完后 currentTrack 不会清空，
     * 否则会一直挂着「正在播放」和一个停住不动的进度。
     */
    val playingTrack: StateFlow<PlayingTrack?> = player.state
        .map { s ->
            if (s.isPlaying || s.isLoading) {
                s.currentTrack?.let {
                    PlayingTrack(key = "${it.bvid}:${it.cid}", positionMs = s.positionMs / 1000L * 1000L)
                }
            } else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 列表里唯一要重点显示的那一项，同时也是「继续播放」的落点：
     * - 正在播这一项 → isPlaying=true（正在播的时候从这里续播最合理）
     * - 否则取播放记录里 updatedAt 最近的那一集 → 历史续播点
     * - 都没听过 → null，按钮回到「全部播放」并从第一条开始
     */
    val focus: StateFlow<TrackFocus?> =
        combine(tracks, progress, playingTrack) { list, prog, playing ->
            fun keyOf(e: PlaylistTrackEntity) = "${e.bvid}:${e.cid}"
            fun idxOfKey(k: String) = list.indexOfFirst { keyOf(it) == k }.takeIf { i -> i >= 0 }

            playing?.key?.let { idxOfKey(it) }?.let { return@combine TrackFocus(it, isPlaying = true) }

            list.filter { prog.containsKey(keyOf(it)) }
                .maxByOrNull { prog[keyOf(it)]?.updatedAt ?: 0L }
                ?.let { idxOfKey(keyOf(it)) }
                ?.let { TrackFocus(it, isPlaying = false) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
        // 本地再挡一次重复点击，给出确定的「已在处理」反馈，而不是让互斥锁静默丢弃
        if (_startingPlay.value) return
        viewModelScope.launch {
            _pendingIndex.value = startIndex
            _startingPlay.value = true
            try {
                val entities = dao.getTracks(playlistId)
                if (entities.isEmpty()) {
                    _message.value = "暂无内容"
                    return@launch
                }
                val safe = startIndex.coerceIn(0, entities.lastIndex)
                player.play(entities.map { it.toTrack() }, safe, playlist.value?.name ?: "收藏")
            } finally {
                _startingPlay.value = false
                _pendingIndex.value = null
            }
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
