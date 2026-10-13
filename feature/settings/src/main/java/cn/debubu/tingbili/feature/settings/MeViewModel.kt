package cn.debubu.tingbili.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.SimpleCache
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.PlaylistDao
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的」页数据：
 * - 收藏数 = 歌单数；历史数 = 今日新增条数；缓存 = 音频缓存实际占用
 * - 听视频累计时长暂无数据源，未登录态头部显示引导文案
 */
@HiltViewModel
class MeViewModel @Inject constructor(
    playlistDao: PlaylistDao,
    historyDao: HistoryDao,
    private val audioCache: SimpleCache
) : ViewModel() {

    val playlistCount: StateFlow<Int> = playlistDao.observePlaylists()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val historyTodayCount: StateFlow<Int> = historyDao.observeAll()
        .map { list ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val todayStart = cal.timeInMillis
            list.count { it.updatedAt >= todayStart }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        refreshCache()
    }

    fun refreshCache() {
        viewModelScope.launch {
            _cacheBytes.value = try {
                audioCache.cacheSpace
            } catch (_: Exception) { 0L }
        }
    }
}
