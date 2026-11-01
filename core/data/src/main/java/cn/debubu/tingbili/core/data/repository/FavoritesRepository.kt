package cn.debubu.tingbili.core.data.repository

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.BvFavoritePageEntity
import cn.debubu.tingbili.core.data.db.DEFAULT_FOLDER_ID
import cn.debubu.tingbili.core.data.db.FavoriteDao
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.db.FavoriteFolderSummary
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.core.data.model.AddTracksResult
import cn.debubu.tingbili.core.data.model.BvFavoriteSnapshot
import cn.debubu.tingbili.core.data.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 收藏写路径的「曾经有收藏」标记口子：生产实现写 DataStore，单测传 no-op。
 * 放在仓储层而不是 VM/UI 层，是为了覆盖所有加收藏路径（加入收藏夹/最爱/BV 收藏）。
 */
fun interface FavoriteEverRecorder {
    suspend fun mark()
}

interface FavoritesRepository {
    fun observeFolderSummaries(): Flow<List<FavoriteFolderSummary>>
    fun observeFolders(): Flow<List<FavoriteFolderEntity>>
    fun observeFolder(id: Long): Flow<FavoriteFolderEntity?>
    /** 一次性读取（历史续播等非 UI 场景） */
    suspend fun getFolder(id: Long): FavoriteFolderEntity?
    fun observeTracks(folderId: Long): Flow<List<FavoriteTrackEntity>>
    suspend fun getTracks(folderId: Long): List<FavoriteTrackEntity>
    fun observeBvFavorites(): Flow<List<BvFavoriteEntity>>
    fun observeBvFavorite(bvid: String): Flow<BvFavoriteEntity?>
    fun observeBvPages(bvid: String): Flow<List<BvFavoritePageEntity>>
    fun observeFolderIdsContaining(bvid: String, cid: Long): Flow<Set<Long>>
    fun observeTrackInFolder(folderId: Long, bvid: String, cid: Long): Flow<Boolean>
    fun observeIsBvFavorited(bvid: String): Flow<Boolean>

    suspend fun ensureDefaultFolder(): Long
    suspend fun createFolder(name: String): Long
    suspend fun renameFolder(id: Long, name: String)
    suspend fun deleteFolder(id: Long): Boolean
    suspend fun reorderFolders(orderedIds: List<Long>)
    suspend fun addTracksToFolders(folderIds: Set<Long>, tracks: List<Track>): AddTracksResult
    suspend fun removeTrack(folderId: Long, bvid: String, cid: Long)
    suspend fun toggleDefaultTrack(track: Track): Boolean
    suspend fun toggleTrackInFolder(folderId: Long, track: Track): Boolean

    suspend fun addBvFavorite(snapshot: BvFavoriteSnapshot)
    suspend fun removeBvFavorite(bvid: String)
    suspend fun refreshBvFavorite(snapshot: BvFavoriteSnapshot)
    suspend fun hasAnyFavorite(): Boolean
}

@Singleton
class DefaultFavoritesRepository @Inject constructor(
    private val db: TingBiliDatabase,
    private val dao: FavoriteDao,
    private val favoriteEverRecorder: FavoriteEverRecorder,
) : FavoritesRepository {

    /** 单测/不关心启动落地 tab 的调用方：标记写 no-op（8 处测试仍用两参构造）。 */
    @VisibleForTesting
    constructor(db: TingBiliDatabase, dao: FavoriteDao) : this(
        db, dao, FavoriteEverRecorder { },
    ) {
    }

    override fun observeFolderSummaries(): Flow<List<FavoriteFolderSummary>> =
        dao.observeFolderSummaries()

    override fun observeFolders(): Flow<List<FavoriteFolderEntity>> = dao.observeFolders()

    override fun observeFolder(id: Long): Flow<FavoriteFolderEntity?> = dao.observeFolder(id)

    override suspend fun getFolder(id: Long): FavoriteFolderEntity? = dao.getFolder(id)

    override fun observeTracks(folderId: Long): Flow<List<FavoriteTrackEntity>> =
        dao.observeTracks(folderId)

    override suspend fun getTracks(folderId: Long): List<FavoriteTrackEntity> =
        dao.getTracks(folderId)

    override fun observeBvFavorites(): Flow<List<BvFavoriteEntity>> = dao.observeBvFavorites()

    override fun observeBvFavorite(bvid: String): Flow<BvFavoriteEntity?> =
        dao.observeBvFavorite(bvid)

    override fun observeBvPages(bvid: String): Flow<List<BvFavoritePageEntity>> =
        dao.observeBvPages(bvid)

    override fun observeFolderIdsContaining(bvid: String, cid: Long): Flow<Set<Long>> =
        dao.observeFolderIdsContaining(bvid, cid).map { it.toSet() }

    override fun observeTrackInFolder(
        folderId: Long,
        bvid: String,
        cid: Long,
    ): Flow<Boolean> = dao.observeTrackInFolder(folderId, bvid, cid)

    override fun observeIsBvFavorited(bvid: String): Flow<Boolean> =
        dao.observeIsBvFavorited(bvid)

    override suspend fun ensureDefaultFolder(): Long {
        dao.getDefaultFolder()?.let { return it.id }
        dao.insertFolder(
            FavoriteFolderEntity(
                id = DEFAULT_FOLDER_ID,
                name = "我的最爱",
                isDefault = true,
                sortOrder = 0,
            )
        )
        return checkNotNull(dao.getDefaultFolder()) { "默认收藏夹创建失败" }.id
    }

    override suspend fun createFolder(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "收藏夹名称不能为空" }
        return db.withTransaction {
            ensureDefaultFolder()
            val sortOrder = dao.maxFolderSortOrder() + 1
            dao.insertFolder(FavoriteFolderEntity(name = trimmed, sortOrder = sortOrder))
        }
    }

    override suspend fun renameFolder(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.renameFolder(id, trimmed)
    }

    override suspend fun deleteFolder(id: Long): Boolean {
        if (id == DEFAULT_FOLDER_ID) return false
        return dao.deleteFolder(id) > 0
    }

    override suspend fun reorderFolders(orderedIds: List<Long>) {
        db.withTransaction {
            orderedIds
                .filter { it != DEFAULT_FOLDER_ID }
                .forEachIndexed { index, id -> dao.updateFolderSort(id, index) }
        }
    }

    override suspend fun addTracksToFolders(
        folderIds: Set<Long>,
        tracks: List<Track>,
    ): AddTracksResult {
        if (folderIds.isEmpty() || tracks.isEmpty()) return AddTracksResult(0, 0)
        val result = db.withTransaction {
            ensureDefaultFolder()
            var added = 0
            var skipped = 0
            folderIds.forEach { folderId ->
                val base = if (folderId == DEFAULT_FOLDER_ID) {
                    (dao.minOrder(folderId) ?: 0) - tracks.size
                } else {
                    dao.maxOrder(folderId) + 1
                }
                tracks.forEachIndexed { index, track ->
                    val rowId = dao.insertTrack(track.toEntity(folderId, base + index))
                    if (rowId == -1L) skipped++ else added++
                }
            }
            AddTracksResult(added = added, skipped = skipped)
        }
        if (result.added > 0) favoriteEverRecorder.mark()
        return result
    }

    override suspend fun removeTrack(folderId: Long, bvid: String, cid: Long) {
        dao.deleteTrack(folderId, bvid, cid)
    }

    override suspend fun toggleDefaultTrack(track: Track): Boolean {
        val added = db.withTransaction {
            val folderId = ensureDefaultFolder()
            val exists = dao.isTrackInFolder(folderId, track.bvid, track.cid)
            if (exists) {
                dao.deleteTrack(folderId, track.bvid, track.cid)
                false
            } else {
                val order = (dao.minOrder(folderId) ?: 0) - 1
                dao.insertTrack(track.toEntity(folderId, order))
                true
            }
        }
        if (added) favoriteEverRecorder.mark()
        return added
    }

    override suspend fun toggleTrackInFolder(folderId: Long, track: Track): Boolean {
        if (folderId == DEFAULT_FOLDER_ID) return toggleDefaultTrack(track)
        val added = db.withTransaction {
            val exists = dao.isTrackInFolder(folderId, track.bvid, track.cid)
            if (exists) {
                dao.deleteTrack(folderId, track.bvid, track.cid)
                false
            } else {
                val order = dao.maxOrder(folderId) + 1
                dao.insertTrack(track.toEntity(folderId, order))
                true
            }
        }
        if (added) favoriteEverRecorder.mark()
        return added
    }

    override suspend fun addBvFavorite(snapshot: BvFavoriteSnapshot) {
        favoriteEverRecorder.mark()
        db.withTransaction {
            val now = System.currentTimeMillis()
            val existing = dao.getBvFavorite(snapshot.bvid)
            val addedAt = existing?.addedAt ?: now
            dao.upsertBvFavorite(snapshot.toEntity(addedAt = addedAt, lastSyncedAt = now))
            dao.clearBvPages(snapshot.bvid)
            dao.insertBvPages(snapshot.toPageEntities())
        }
    }

    override suspend fun removeBvFavorite(bvid: String) {
        dao.deleteBvFavorite(bvid)
    }

    override suspend fun refreshBvFavorite(snapshot: BvFavoriteSnapshot) {
        db.withTransaction {
            val existing = dao.getBvFavorite(snapshot.bvid) ?: return@withTransaction
            val now = System.currentTimeMillis()
            dao.upsertBvFavorite(snapshot.toEntity(addedAt = existing.addedAt, lastSyncedAt = now))
            dao.clearBvPages(snapshot.bvid)
            dao.insertBvPages(snapshot.toPageEntities())
        }
    }

    override suspend fun hasAnyFavorite(): Boolean = dao.hasAnyFavorite()
}

private fun Track.toEntity(folderId: Long, order: Int): FavoriteTrackEntity =
    FavoriteTrackEntity(
        folderId = folderId,
        bvid = bvid,
        cid = cid,
        title = title,
        author = author,
        cover = cover,
        durationMs = durationMs,
        videoTitle = videoTitle,
        pageIndex = pageIndex,
        order = order,
    )

private fun BvFavoriteSnapshot.toEntity(addedAt: Long, lastSyncedAt: Long): BvFavoriteEntity =
    BvFavoriteEntity(
        bvid = bvid,
        title = title,
        author = author,
        authorMid = authorMid,
        cover = cover,
        durationMs = durationMs,
        pageCount = pages.size,
        addedAt = addedAt,
        lastSyncedAt = lastSyncedAt,
    )

private fun BvFavoriteSnapshot.toPageEntities(): List<BvFavoritePageEntity> =
    pages.mapIndexed { index, page ->
        BvFavoritePageEntity(
            bvid = bvid,
            cid = page.cid,
            pageIndex = page.pageIndex,
            title = page.title,
            durationMs = page.durationMs,
            order = index,
        )
    }
