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
    val createdAt: Long = System.currentTimeMillis(),
    /** 听单封面 URL（取首条 BV 的 pic，空白听单为 null，前端展示默认占位） */
    val cover: String? = null
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

    @Query("UPDATE PlaylistEntity SET cover = :cover WHERE id = :playlistId")
    suspend fun updateCover(playlistId: Long, cover: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrack(t: PlaylistTrackEntity)

    @Query("SELECT * FROM PlaylistTrackEntity WHERE playlistId=:id ORDER BY `order`")
    suspend fun getTracks(id: Long): List<PlaylistTrackEntity>

    @Query("SELECT * FROM PlaylistEntity WHERE id=:id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Query("SELECT * FROM PlaylistEntity WHERE id=:id")
    fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM PlaylistTrackEntity WHERE playlistId=:id ORDER BY `order`")
    fun observeTracks(id: Long): Flow<List<PlaylistTrackEntity>>

    @Query("UPDATE PlaylistEntity SET name = :name WHERE id = :playlistId")
    suspend fun updateName(playlistId: Long, name: String)

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

    @Query("UPDATE PlaylistTrackEntity SET `order`=:newOrder WHERE playlistId=:playlistId AND bvid=:bvid AND cid=:cid")
    suspend fun updateOrder(playlistId: Long, bvid: String, cid: Long, newOrder: Int)
}
