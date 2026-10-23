package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * playurl 直链磁盘缓存（key="$bvid:$cid"）。
 *
 * 之前住在 DataStore（整个 Map 序列化成一个 JSON，百条即百 KB protobuf 重写，
 * 读一次全量解码）。Room 按行存取，只读写命中项；TTL/上限语义与旧实现一致。
 */
@Entity
data class PlayUrlCacheEntity(
    @PrimaryKey val key: String,
    val url: String,
    val fetchedAtMs: Long = 0L,
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        url.isNotBlank() && fetchedAtMs > 0L && nowMs - fetchedAtMs < TTL_MS

    companion object {
        /** 与 PlayerManager.PROACTIVE_REFRESH_MS 对齐：提前半小时失效，留足余量 */
        const val TTL_MS = 90 * 60 * 1000L
        const val MAX_ENTRIES = 300
    }
}

@Dao
interface PlayUrlCacheDao {
    @Query("SELECT * FROM PlayUrlCacheEntity")
    suspend fun getAll(): List<PlayUrlCacheEntity>

    @Upsert
    suspend fun putAll(rows: List<PlayUrlCacheEntity>)

    /** 删过期（调用方每次写入后调一次） */
    @Query("DELETE FROM PlayUrlCacheEntity WHERE fetchedAtMs < :cutoffMs")
    suspend fun pruneExpired(cutoffMs: Long)

    /** 超上限只留最新的 keep 条 */
    @Query("DELETE FROM PlayUrlCacheEntity WHERE `key` NOT IN (SELECT `key` FROM PlayUrlCacheEntity ORDER BY fetchedAtMs DESC LIMIT :keep)")
    suspend fun pruneOverflow(keep: Int)
}
