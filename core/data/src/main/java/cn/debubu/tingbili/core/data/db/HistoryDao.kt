package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 一个 BV 只留一条历史；cid 记录"最近播放的那个分 P"，仅用于续播定位。 */
@Entity(primaryKeys = ["bvid"])
data class HistoryEntity(
    val bvid: String,
    val cid: Long,
    val positionMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
    /** 本条目累计收听时长（本地统计，不含 seek 跳变） */
    val listenDurationMs: Long = 0L
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(e: HistoryEntity)

    @Query("SELECT * FROM HistoryEntity WHERE bvid=:bvid LIMIT 1")
    suspend fun get(bvid: String): HistoryEntity?

    @Query("SELECT * FROM HistoryEntity ORDER BY updatedAt DESC")
    suspend fun getAll(): List<HistoryEntity>

    @Query("SELECT * FROM HistoryEntity ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    /** 详情/播放按 BV 订阅：播放器每秒写全表时，不再让单页跟着全量 associateBy */
    @Query("SELECT * FROM HistoryEntity WHERE bvid=:bvid")
    fun observeByBvid(bvid: String): Flow<List<HistoryEntity>>

    /** 歌单按多 BV 订阅：只收本单相关 BV，避免全表扫描 */
    @Query("SELECT * FROM HistoryEntity WHERE bvid IN (:bvids)")
    fun observeByBvids(bvids: List<String>): Flow<List<HistoryEntity>>

    /** 我的页今日收听条数：DB 侧 COUNT，不拉全表 */
    @Query("SELECT COUNT(*) FROM HistoryEntity WHERE updatedAt>=:todayStart")
    fun observeTodayCount(todayStart: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(listenDurationMs), 0) FROM HistoryEntity")
    fun observeTotalListenMs(): Flow<Long>

    @Query("DELETE FROM HistoryEntity WHERE bvid=:bvid")
    suspend fun delete(bvid: String)

    @Query("DELETE FROM HistoryEntity")
    suspend fun clearAll()
}
