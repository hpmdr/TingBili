package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
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
    /** 收藏集封面（BV pic） */
    val cover: String? = null,
    /** 来源 BV，收藏集唯一归属；手动收藏为 null */
    val sourceBvid: String? = null,
    /** 种类：bv=从 BV 收藏，custom=手动（预留） */
    val kind: String = "bv"
)

@Entity(primaryKeys = ["playlistId", "bvid", "cid"])
data class PlaylistTrackEntity(
    val playlistId: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val order: Int,
    val author: String = "",
    val cover: String = "",
    val durationMs: Long = 0L,
    /** BV 合集名（分 P 名之外的视频总标题），老数据为 null */
    val videoTitle: String? = null,
    /** 第几个分 P（从 1 开始），老数据为 null */
    val pageIndex: Int? = null
)

data class PlaylistSummary(
    @Embedded val playlist: PlaylistEntity,
    val trackCount: Int,
    val totalDurationMs: Long,
    /** 该收藏里最近播放一集的分 P 序号（没播放过为 null） */
    val lastPlayedPageIndex: Int? = null,
    /** 该收藏里最近播放一集的进度毫秒（没播放过为 null） */
    val lastPlayedPositionMs: Long? = null
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

    @Query("SELECT * FROM PlaylistEntity WHERE sourceBvid = :bvid LIMIT 1")
    suspend fun getPlaylistByBvid(bvid: String): PlaylistEntity?

    @Query("SELECT * FROM PlaylistEntity WHERE sourceBvid = :bvid LIMIT 1")
    fun observePlaylistByBvid(bvid: String): kotlinx.coroutines.flow.Flow<PlaylistEntity?>

    @Query("UPDATE PlaylistEntity SET name = :name WHERE id = :playlistId")
    suspend fun updateName(playlistId: Long, name: String)

    @Query("SELECT * FROM PlaylistEntity ORDER BY createdAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query(
        """
        SELECT p.*,
               COUNT(t.bvid) AS trackCount,
               COALESCE(SUM(t.durationMs), 0) AS totalDurationMs,
               (
                   SELECT t2.pageIndex FROM PlaylistTrackEntity t2
                   INNER JOIN HistoryEntity h2 ON h2.bvid = t2.bvid AND h2.cid = t2.cid
                   WHERE t2.playlistId = p.id
                   ORDER BY h2.updatedAt DESC LIMIT 1
               ) AS lastPlayedPageIndex,
               (
                   SELECT h3.positionMs FROM PlaylistTrackEntity t3
                   INNER JOIN HistoryEntity h3 ON h3.bvid = t3.bvid AND h3.cid = t3.cid
                   WHERE t3.playlistId = p.id
                   ORDER BY h3.updatedAt DESC LIMIT 1
               ) AS lastPlayedPositionMs
        FROM PlaylistEntity p
        LEFT JOIN PlaylistTrackEntity t ON t.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.createdAt DESC
        """
    )
    fun observePlaylistSummaries(): Flow<List<PlaylistSummary>>

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

    /** 老收藏补齐合集名/分 P 序号（这些字段是后加的列，历史数据为空） */
    @Query(
        """
        UPDATE PlaylistTrackEntity
        SET videoTitle = :videoTitle, pageIndex = :pageIndex
        WHERE playlistId=:playlistId AND bvid=:bvid AND cid=:cid
        """
    )
    suspend fun updateTrackMeta(
        playlistId: Long,
        bvid: String,
        cid: Long,
        videoTitle: String?,
        pageIndex: Int?
    )
}
