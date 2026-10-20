package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 首页发现流快照（SWR）：只存首屏，冷启动秒开旧数据、后台再刷新。
 * 单行表（id=0），有界 60 条，TTL 由调用方判断（默认 15min）。
 */
@Entity
data class HomeSnapshotEntity(
    @PrimaryKey val id: Int = 0,
    /** 策展关键词顺序（JSON），仅用于调试/可观测，不做门控 */
    val keywordsJson: String = "[]",
    /** 首屏 Track 列表（JSON，List<Track>） */
    val tracksJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface HomeSnapshotDao {
    @Query("SELECT * FROM HomeSnapshotEntity WHERE id = 0")
    suspend fun get(): HomeSnapshotEntity?

    @Query("SELECT * FROM HomeSnapshotEntity WHERE id = 0")
    fun observe(): Flow<HomeSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(e: HomeSnapshotEntity)

    @Query("DELETE FROM HomeSnapshotEntity")
    suspend fun clear()
}
