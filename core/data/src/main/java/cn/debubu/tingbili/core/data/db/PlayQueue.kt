package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 播放队列快照（单行 id=0）：队列 + 在播下标 + 合集名，一次 Upsert 原子落盘。
 *
 * 之前住 DataStore（last_queue_json/last_index/last_source_title 三个键分三次写，
 * 进程在中间被杀即出现“队列新、下标旧”的错位）。进度（positionMs 高频 10s 写）
 * 仍放 DataStore，见 PreferencesRepository.lastPositionMs。
 * tracksJson 复用 core:data.model.Track 的 kotlinx 序列化。
 */
@Entity
data class PlayQueueSnapshotEntity(
    @PrimaryKey val id: Int = 0,
    val tracksJson: String = "[]",
    val currentIndex: Int = -1,
    val sourceTitle: String? = null,
    val updatedAt: Long = 0L,
)

@Dao
interface PlayQueueDao {
    @Query("SELECT * FROM PlayQueueSnapshotEntity WHERE id = 0")
    suspend fun get(): PlayQueueSnapshotEntity?

    @Upsert
    suspend fun save(snapshot: PlayQueueSnapshotEntity)

    @Query("DELETE FROM PlayQueueSnapshotEntity")
    suspend fun clear()
}

/** 队列 JSON 编解码（与旧 DataStore 快照同格式，损坏返回空列表静默忽略） */
object PlayQueueJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(tracks: List<Track>): String =
        Json.encodeToString(ListSerializer(Track.serializer()), tracks)

    fun decode(raw: String): List<Track> = try {
        Json.decodeFromString(ListSerializer(Track.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }
}
