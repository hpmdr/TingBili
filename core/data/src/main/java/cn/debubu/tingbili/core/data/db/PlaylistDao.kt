package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(primaryKeys = ["playlistId", "bvid", "cid"])
data class PlaylistTrackEntity(
    val playlistId: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val order: Int
)

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrack(t: PlaylistTrackEntity)

    @Query("SELECT * FROM PlaylistTrackEntity WHERE playlistId=:id ORDER BY `order`")
    suspend fun getTracks(id: Long): List<PlaylistTrackEntity>

    @Query("SELECT * FROM PlaylistEntity ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM PlaylistEntity ORDER BY createdAt DESC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("DELETE FROM PlaylistTrackEntity WHERE playlistId=:playlistId AND bvid=:bvid AND cid=:cid")
    suspend fun removeTrack(playlistId: Long, bvid: String, cid: Long)

    @Query("DELETE FROM PlaylistEntity WHERE id=:id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM PlaylistTrackEntity WHERE playlistId=:playlistId")
    suspend fun clearTracks(playlistId: Long)
}
