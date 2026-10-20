package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 字幕磁盘快照（SWR）：冷启动秒显旧字幕、新鲜则免网络。
 *
 * 背景：SubtitleCache 原来是纯内存 LRU，进程一死就清空，冷启动恢复播放必重新拉
 * 两跳网络（轨列表+正文）。和首页快照同一个毛病，同一个解法。
 *
 * - 主键 `bvid:cid`，一行存一集：轨列表 + 当时选中轨的正文。
 * - JSON 存不透明字符串：轨/行的数据类在 data:bilibili 层，core:data 不反向依赖，
 *   编解码由调用方（SubtitleCache）负责，这里只管存取。
 * - 只存成功非空（视频后补字幕靠 TTL 过期后重拉捡回来）。
 * - 有界 50 集（调用方存完即裁，单集几小时字幕也就几十 KB）。
 */
@Entity
data class SubtitleDiskEntity(
    @PrimaryKey val cacheKey: String,
    val tracksJson: String = "[]",
    val index: Int = 0,
    val linesJson: String = "[]",
    val label: String = "",
    val isAi: Boolean = false,
    val fetchedAt: Long = 0L,
)

@Dao
interface SubtitleDiskDao {
    @Query("SELECT * FROM SubtitleDiskEntity WHERE cacheKey = :key")
    suspend fun get(key: String): SubtitleDiskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(e: SubtitleDiskEntity)

    /** 按新旧只留 [keep] 集，裁掉最旧的（调用方每次存完调一次） */
    @Query("DELETE FROM SubtitleDiskEntity WHERE cacheKey NOT IN (SELECT cacheKey FROM SubtitleDiskEntity ORDER BY fetchedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    /** 删掉整个 BV 的快照（刷新时强制重拉用，前缀 `bvid:`） */
    @Query("DELETE FROM SubtitleDiskEntity WHERE cacheKey LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)

    @Query("DELETE FROM SubtitleDiskEntity")
    suspend fun clear()
}
