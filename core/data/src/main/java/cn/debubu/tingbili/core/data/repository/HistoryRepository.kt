package cn.debubu.tingbili.core.data.repository

import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 播放历史的读取/维护出口（REVIEW_REPORT D-01）。
 * 之前 detail/favorites/history/mine 四个 feature 直接注入 `HistoryDao`，导致 DAO 的查询语义、
 * 失效范围、表结构散落在 UI 层；统一收口后 feature 只依赖仓储，换存储只改一处。
 */
interface HistoryRepository {

    /** 全部播单记录，按最后播放时间倒序（历史页） */
    fun observeAll(): Flow<List<HistoryEntity>>

    /** 某个 BV 的全部历史（详情页/收藏缓存页的进度与高亮） */
    fun observeByBvid(bvid: String): Flow<List<HistoryEntity>>

    /** 一批 BV 的历史（收藏夹页按页批量取，避免 N 次订阅） */
    fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>>

    /** 累计收听时长（我的页统计） */
    fun observeTotalListenMs(): Flow<Long>

    /** 今日新增播单数（我的页统计） */
    fun observeTodayCount(todayStart: Long): Flow<Int>

    suspend fun clearAll()

    suspend fun deleteSource(sourceType: Int, sourceId: String)
}

@Singleton
class DefaultHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
) : HistoryRepository {

    override fun observeAll(): Flow<List<HistoryEntity>> = dao.observeAll()

    override fun observeByBvid(bvid: String): Flow<List<HistoryEntity>> = dao.observeByBvid(bvid)

    override fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>> = dao.observeByBvids(bvids)

    override fun observeTotalListenMs(): Flow<Long> = dao.observeTotalListenMs()

    override fun observeTodayCount(todayStart: Long): Flow<Int> = dao.observeTodayCount(todayStart)

    override suspend fun clearAll() = dao.clearAll()

    override suspend fun deleteSource(sourceType: Int, sourceId: String) =
        dao.deleteSource(sourceType, sourceId)
}