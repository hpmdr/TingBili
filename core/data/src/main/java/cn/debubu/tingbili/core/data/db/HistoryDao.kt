package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 历史按「播单」记：一条 = 一个来源播单（BV 或收藏夹），
 * 保留最后一次播放的分 P 与位置，用于「听过什么 + 继续听」。
 *
 * - sourceType = BV：sourceId = bvid，sourceTitle = BV 标题快照；
 * - sourceType = 收藏夹：sourceId = folderId，sourceTitle = 收藏夹名快照。
 *
 * 同一个 BV 从收藏夹播过、又从搜索播过，会有两条记录（夹一条、BV 一条），符合产品语义。
 * 字段顺序把 bvid/cid 放前面、来源字段放后面并带默认值，兼容旧的构造/测试。
 */
// 索引：bvid（详情页/收藏判断按 bvid 查）、updatedAt（列表 ORDER BY 排序）。
// 复合主键只提供 sourceType+sourceId 的前缀索引，bvid/updatedAt 原本都是全表扫（REVIEW_REPORT E-07）。
@Entity(
    primaryKeys = ["sourceType", "sourceId"],
    indices = [Index("bvid"), Index("updatedAt")],
)
data class HistoryEntity(
    val bvid: String,
    val cid: Long,
    val positionMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    /** 本播单累计收听时长（本地统计，不含 seek 跳变） */
    val listenDurationMs: Long = 0L,
    val sourceType: Int = SOURCE_BV,
    /** BV 用 bvid；收藏夹用 folderId.toString() */
    val sourceId: String = bvid,
    /** 播放时的来源名快照（收藏夹名 / BV 标题）；不实时跟着改名 */
    val sourceTitle: String? = null,
) {
    companion object {
        const val SOURCE_BV = 0
        const val SOURCE_FOLDER = 1
    }
}

@Dao
interface HistoryDao {    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(e: HistoryEntity)

    /** 某个播单当前一行的快照（累计时长/最后位置） */
    @Query(
        "SELECT * FROM HistoryEntity WHERE sourceType=:sourceType AND sourceId=:sourceId LIMIT 1"
    )
    suspend fun getSource(sourceType: Int, sourceId: String): HistoryEntity?

    /** 某个分 P 最近一次的位置（无论来自 BV 还是某个收藏夹），用于自动续播兜底 */
    @Query(
        "SELECT * FROM HistoryEntity WHERE bvid=:bvid AND cid=:cid ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getLatestForTrack(bvid: String, cid: Long): HistoryEntity?

    @Query("SELECT * FROM HistoryEntity ORDER BY updatedAt DESC")
    suspend fun getAll(): List<HistoryEntity>

    @Query("SELECT * FROM HistoryEntity ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    /** 详情/播放按 BV 订阅：同 track 可能有多条来源记录，调用方按 updatedAt 取最新 */
    @Query("SELECT * FROM HistoryEntity WHERE bvid=:bvid")
    fun observeByBvid(bvid: String): Flow<List<HistoryEntity>>

    /** 收藏夹按多 BV 订阅：只收本夹相关 BV，同 track 可能有多条来源记录 */
    @Query("SELECT * FROM HistoryEntity WHERE bvid IN (:bvids)")
    fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>>

    /** 我的页今日收听条数：DB 侧 COUNT，不拉全表 */
    @Query("SELECT COUNT(*) FROM HistoryEntity WHERE updatedAt>=:todayStart")
    fun observeTodayCount(todayStart: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(listenDurationMs), 0) FROM HistoryEntity")
    fun observeTotalListenMs(): Flow<Long>

    /** 删一条播单历史；不会动收藏夹本身 */
    @Query("DELETE FROM HistoryEntity WHERE sourceType=:sourceType AND sourceId=:sourceId")
    suspend fun deleteSource(sourceType: Int, sourceId: String)

    @Query("DELETE FROM HistoryEntity")
    suspend fun clearAll()
}

/**
 * 同一分 P 可能有多条播单记录（BV 一条、收藏夹一条）。
 * 页面展示/进度焦点只认最近一次，按 updatedAt 取最新，避免旧来源覆盖新位置。
 */
fun List<HistoryEntity>.latestPerTrack(): Map<String, HistoryEntity> {
    val result = HashMap<String, HistoryEntity>(size)
    for (e in this) {
        val key = "${e.bvid}:${e.cid}"
        val old = result[key]
        if (old == null || e.updatedAt >= old.updatedAt) result[key] = e
    }
    return result
}
