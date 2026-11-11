package cn.debubu.tingbili.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    // ── 收藏夹 ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FavoriteFolderEntity): Long

    @Query("SELECT * FROM FavoriteFolderEntity WHERE id = :id")
    suspend fun getFolder(id: Long): FavoriteFolderEntity?

    @Query("SELECT * FROM FavoriteFolderEntity WHERE id = :id")
    fun observeFolder(id: Long): Flow<FavoriteFolderEntity?>

    @Query("SELECT * FROM FavoriteFolderEntity WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultFolder(): FavoriteFolderEntity?

    @Query("SELECT * FROM FavoriteFolderEntity ORDER BY isDefault DESC, sortOrder ASC, createdAt ASC")
    fun observeFolders(): Flow<List<FavoriteFolderEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM FavoriteFolderEntity WHERE isDefault = 0")
    suspend fun maxFolderSortOrder(): Int

    @Query(
        """
        SELECT p.*,
               COUNT(t.bvid) AS trackCount,
               COALESCE(SUM(t.durationMs), 0) AS totalDurationMs,
               (
                   SELECT t4.cover FROM FavoriteTrackEntity t4
                   WHERE t4.folderId = p.id AND t4.cover != ''
                   ORDER BY t4.`order` LIMIT 1
               ) AS firstCover,
               (
                   SELECT t2.pageIndex FROM FavoriteTrackEntity t2
                   INNER JOIN HistoryEntity h2 ON h2.bvid = t2.bvid AND h2.cid = t2.cid
                   WHERE t2.folderId = p.id
                   ORDER BY h2.updatedAt DESC LIMIT 1
               ) AS lastPlayedPageIndex,
               (
                   SELECT h3.positionMs FROM FavoriteTrackEntity t3
                   INNER JOIN HistoryEntity h3 ON h3.bvid = t3.bvid AND h3.cid = t3.cid
                   WHERE t3.folderId = p.id
                   ORDER BY h3.updatedAt DESC LIMIT 1
               ) AS lastPlayedPositionMs
        FROM FavoriteFolderEntity p
        LEFT JOIN FavoriteTrackEntity t ON t.folderId = p.id
        GROUP BY p.id
        ORDER BY p.isDefault DESC, p.sortOrder ASC, p.createdAt ASC
        """
    )
    fun observeFolderSummaries(): Flow<List<FavoriteFolderSummary>>

    @Query("UPDATE FavoriteFolderEntity SET name = :name WHERE id = :id AND isDefault = 0")
    suspend fun renameFolder(id: Long, name: String)

    @Query("UPDATE FavoriteFolderEntity SET sortOrder = :sortOrder WHERE id = :id AND isDefault = 0")
    suspend fun updateFolderSort(id: Long, sortOrder: Int)

    @Query("DELETE FROM FavoriteFolderEntity WHERE id = :id AND isDefault = 0")
    suspend fun deleteFolder(id: Long): Int

    // ── 夹内 P ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: FavoriteTrackEntity): Long

    @Query("SELECT * FROM FavoriteTrackEntity WHERE folderId = :folderId ORDER BY `order`")
    suspend fun getTracks(folderId: Long): List<FavoriteTrackEntity>

    @Query("SELECT * FROM FavoriteTrackEntity WHERE folderId = :folderId ORDER BY `order`")
    fun observeTracks(folderId: Long): Flow<List<FavoriteTrackEntity>>

    @Query("SELECT COALESCE(MAX(`order`), -1) FROM FavoriteTrackEntity WHERE folderId = :folderId")
    suspend fun maxOrder(folderId: Long): Int

    @Query("SELECT MIN(`order`) FROM FavoriteTrackEntity WHERE folderId = :folderId")
    suspend fun minOrder(folderId: Long): Int?

    @Query("DELETE FROM FavoriteTrackEntity WHERE folderId = :folderId AND bvid = :bvid AND cid = :cid")
    suspend fun deleteTrack(folderId: Long, bvid: String, cid: Long)

    @Query("SELECT folderId FROM FavoriteTrackEntity WHERE bvid = :bvid AND cid = :cid")
    fun observeFolderIdsContaining(bvid: String, cid: Long): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM FavoriteTrackEntity WHERE folderId = :folderId AND bvid = :bvid AND cid = :cid)")
    fun observeTrackInFolder(folderId: Long, bvid: String, cid: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM FavoriteTrackEntity WHERE folderId = :folderId AND bvid = :bvid AND cid = :cid)")
    suspend fun isTrackInFolder(folderId: Long, bvid: String, cid: Long): Boolean

    // ── BV 收藏 ────────────────────────────────────────────

    @Upsert
    suspend fun upsertBvFavorite(favorite: BvFavoriteEntity)

    @Query("DELETE FROM BvFavoriteEntity WHERE bvid = :bvid")
    suspend fun deleteBvFavorite(bvid: String)

    @Query("SELECT * FROM BvFavoriteEntity WHERE bvid = :bvid")
    suspend fun getBvFavorite(bvid: String): BvFavoriteEntity?

    @Query("SELECT * FROM BvFavoriteEntity WHERE bvid = :bvid")
    fun observeBvFavorite(bvid: String): Flow<BvFavoriteEntity?>

    @Query("SELECT * FROM BvFavoriteEntity ORDER BY addedAt DESC")
    fun observeBvFavorites(): Flow<List<BvFavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM BvFavoriteEntity WHERE bvid = :bvid)")
    fun observeIsBvFavorited(bvid: String): Flow<Boolean>

    // ── BV 缓存分 P ────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBvPages(pages: List<BvFavoritePageEntity>)

    @Query("DELETE FROM BvFavoritePageEntity WHERE bvid = :bvid")
    suspend fun clearBvPages(bvid: String)

    @Query("SELECT * FROM BvFavoritePageEntity WHERE bvid = :bvid ORDER BY `order`")
    fun observeBvPages(bvid: String): Flow<List<BvFavoritePageEntity>>

    @Query("SELECT * FROM BvFavoritePageEntity WHERE bvid = :bvid ORDER BY `order`")
    suspend fun getBvPages(bvid: String): List<BvFavoritePageEntity>

    // ── 冷启动 ─────────────────────────────────────────────

    @Query("SELECT (SELECT COUNT(*) FROM FavoriteTrackEntity) + (SELECT COUNT(*) FROM BvFavoriteEntity) > 0")
    suspend fun hasAnyFavorite(): Boolean

    // ── 备份/恢复：全量读写与清空 ─────────────────────────

    @Query("SELECT * FROM FavoriteFolderEntity")
    suspend fun getAllFolders(): List<FavoriteFolderEntity>

    @Query("SELECT * FROM FavoriteTrackEntity")
    suspend fun getAllTracks(): List<FavoriteTrackEntity>

    @Query("SELECT * FROM BvFavoriteEntity")
    suspend fun getAllBvFavorites(): List<BvFavoriteEntity>

    @Query("SELECT * FROM BvFavoritePageEntity")
    suspend fun getAllBvPages(): List<BvFavoritePageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FavoriteFolderEntity>)

    /** 合并导入用：主键冲突的行跳过，返回每行 rowId（-1=已存在）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIgnore(tracks: List<FavoriteTrackEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBvFavorites(favorites: List<BvFavoriteEntity>)

    @Query("DELETE FROM FavoriteTrackEntity")
    suspend fun clearTracks()

    @Query("DELETE FROM FavoriteFolderEntity")
    suspend fun clearFolders()

    @Query("DELETE FROM BvFavoritePageEntity")
    suspend fun clearAllBvPages()

    @Query("DELETE FROM BvFavoriteEntity")
    suspend fun clearAllBvFavorites()
}
