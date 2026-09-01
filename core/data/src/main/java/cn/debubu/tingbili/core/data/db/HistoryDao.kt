package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(primaryKeys = ["bvid", "cid"])
data class HistoryEntity(
    val bvid: String,
    val cid: Long,
    val positionMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(e: HistoryEntity)

    @Query("SELECT * FROM HistoryEntity WHERE bvid=:bvid AND cid=:cid LIMIT 1")
    suspend fun get(bvid: String, cid: Long): HistoryEntity?

    @Query("SELECT * FROM HistoryEntity ORDER BY updatedAt DESC")
    suspend fun getAll(): List<HistoryEntity>

    @Query("SELECT * FROM HistoryEntity ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("DELETE FROM HistoryEntity WHERE bvid=:bvid AND cid=:cid")
    suspend fun delete(bvid: String, cid: Long)

    @Query("DELETE FROM HistoryEntity")
    suspend fun clearAll()
}
