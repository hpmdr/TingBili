package cn.debubu.tingbili.backup

import android.content.Context
import androidx.room.withTransaction
import cn.debubu.tingbili.core.data.backup.AuthBackup
import cn.debubu.tingbili.core.data.backup.BackupData
import cn.debubu.tingbili.core.data.backup.BackupPayload
import cn.debubu.tingbili.core.data.backup.BackupStore
import cn.debubu.tingbili.core.data.backup.BvFavoriteBackup
import cn.debubu.tingbili.core.data.backup.BvPageBackup
import cn.debubu.tingbili.core.data.backup.FolderBackup
import cn.debubu.tingbili.core.data.backup.HistoryBackup
import cn.debubu.tingbili.core.data.backup.ImportMode
import cn.debubu.tingbili.core.data.backup.ImportResult
import cn.debubu.tingbili.core.data.backup.TrackBackup
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.BvFavoritePageEntity
import cn.debubu.tingbili.core.data.db.DEFAULT_FOLDER_ID
import cn.debubu.tingbili.core.data.db.FavoriteDao
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份实现放 :app：同时需要 Room、DataStore 和 data:bilibili 的登录仓储，
 * 只有聚合层不会产生模块反向依赖（同 AppCacheManager 的思路）。
 */
@Singleton
class AppBackupStore @Inject constructor(
    private val db: TingBiliDatabase,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val prefs: PreferencesRepository,
    private val auth: AuthRepository,
    @ApplicationContext private val context: Context,
) : BackupStore {

    override suspend fun export(includeAuth: Boolean): BackupPayload = withContext(Dispatchers.IO) {
        val session = if (includeAuth) auth.sessionNow() else null
        BackupPayload(
            appVersion = appVersion(),
            exportedAt = System.currentTimeMillis(),
            includesAuth = session != null,
            data = BackupData(
                settings = prefs.snapshotSettings(),
                folders = favoriteDao.getAllFolders().map { it.toBackup() },
                tracks = favoriteDao.getAllTracks().map { it.toBackup() },
                bvFavorites = favoriteDao.getAllBvFavorites().map { it.toBackup() },
                bvPages = favoriteDao.getAllBvPages().map { it.toBackup() },
                history = historyDao.getAll().map { it.toBackup() },
                auth = session?.toBackup(),
            ),
        )
    }

    override suspend fun import(payload: BackupPayload, mode: ImportMode): ImportResult =
        withContext(Dispatchers.IO) {
            val data = payload.data
            val dbResult = db.withTransaction {
                if (mode == ImportMode.REPLACE) {
                    favoriteDao.clearTracks()
                    favoriteDao.clearFolders()
                    favoriteDao.clearAllBvPages()
                    favoriteDao.clearAllBvFavorites()
                    historyDao.clearAll()
                }

                val defaultFolderId = ensureDefaultFolder()
                val localFolders = favoriteDao.getAllFolders().toMutableList()
                val folderIdMap = mutableMapOf<Long, Long>()
                var foldersAdded = 0
                var foldersMatched = 0

                data.folders.forEach { folder ->
                    if (folder.isDefault) {
                        folderIdMap[folder.id] = defaultFolderId
                        return@forEach
                    }
                    val sameName = localFolders.firstOrNull { !it.isDefault && it.name == folder.name }
                    if (sameName != null) {
                        folderIdMap[folder.id] = sameName.id
                        foldersMatched++
                    } else {
                        val newId = favoriteDao.insertFolder(
                            FavoriteFolderEntity(
                                name = folder.name,
                                isDefault = false,
                                createdAt = folder.createdAt,
                                sortOrder = folder.sortOrder,
                            )
                        )
                        if (newId > 0L) {
                            folderIdMap[folder.id] = newId
                            localFolders += FavoriteFolderEntity(
                                id = newId,
                                name = folder.name,
                                isDefault = false,
                                createdAt = folder.createdAt,
                                sortOrder = folder.sortOrder,
                            )
                            foldersAdded++
                        }
                    }
                }

                val tracks = data.tracks.mapNotNull { track ->
                    val folderId = folderIdMap[track.folderId] ?: return@mapNotNull null
                    FavoriteTrackEntity(
                        folderId = folderId,
                        bvid = track.bvid,
                        cid = track.cid,
                        title = track.title,
                        author = track.author,
                        cover = track.cover,
                        durationMs = track.durationMs,
                        videoTitle = track.videoTitle,
                        pageIndex = track.pageIndex,
                        order = track.order,
                    )
                }
                val insertedRows = favoriteDao.insertTracksIgnore(tracks)
                val tracksAdded = insertedRows.count { it != -1L }
                val tracksSkipped = insertedRows.size - tracksAdded

                val bvFavorites = data.bvFavorites.map { it.toEntity() }
                if (bvFavorites.isNotEmpty()) favoriteDao.insertBvFavorites(bvFavorites)
                data.bvFavorites.forEach { favorite ->
                    // 合并时也按 bvid 替换分 P，避免旧分 P 残留。
                    favoriteDao.clearBvPages(favorite.bvid)
                    val pages = data.bvPages
                        .filter { it.bvid == favorite.bvid }
                        .map { it.toEntity() }
                    if (pages.isNotEmpty()) favoriteDao.insertBvPages(pages)
                }

                var historyAdded = 0
                var historyUpdated = 0
                if (mode == ImportMode.REPLACE) {
                    val rows = data.history.map { it.toEntity(folderIdMap) }
                    if (rows.isNotEmpty()) {
                        historyDao.insertAll(rows)
                        historyAdded = rows.size
                    }
                } else {
                    data.history.forEach { history ->
                        val row = history.toEntity(folderIdMap)
                        val existing = historyDao.getSource(row.sourceType, row.sourceId)
                        when {
                            existing == null -> {
                                historyDao.save(row)
                                historyAdded++
                            }
                            row.updatedAt >= existing.updatedAt -> {
                                historyDao.save(row)
                                historyUpdated++
                            }
                        }
                    }
                }

                ImportResult(
                    foldersAdded = foldersAdded,
                    foldersMatched = foldersMatched,
                    tracksAdded = tracksAdded,
                    tracksSkipped = tracksSkipped,
                    bvFavorites = bvFavorites.size,
                    historyAdded = historyAdded,
                    historyUpdated = historyUpdated,
                    settingsRestored = true,
                )
            }

            // DataStore 与加密存储不参与 Room 事务：数据库成功后再写，失败不回滚 DB。
            prefs.restoreSettings(data.settings)
            if (favoriteDao.hasAnyFavorite()) prefs.markFavoriteEver()

            var authRestored = false
            if (payload.includesAuth) {
                data.auth?.takeIf { it.sessdata.isNotBlank() }?.let {
                    auth.save(it.toSession())
                    authRestored = true
                }
            }
            dbResult.copy(authRestored = authRestored)
        }

    private suspend fun ensureDefaultFolder(): Long {
        favoriteDao.getDefaultFolder()?.let { return it.id }
        favoriteDao.insertFolder(
            FavoriteFolderEntity(
                id = DEFAULT_FOLDER_ID,
                name = "我的最爱",
                isDefault = true,
                sortOrder = 0,
            )
        )
        return favoriteDao.getDefaultFolder()?.id ?: DEFAULT_FOLDER_ID
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun FavoriteFolderEntity.toBackup(): FolderBackup =
    FolderBackup(id, name, isDefault, createdAt, sortOrder)

private fun FavoriteTrackEntity.toBackup(): TrackBackup =
    TrackBackup(folderId, bvid, cid, title, author, cover, durationMs, videoTitle, pageIndex, order)

private fun BvFavoriteEntity.toBackup(): BvFavoriteBackup =
    BvFavoriteBackup(bvid, title, author, authorMid, cover, durationMs, pageCount, addedAt, lastSyncedAt)

private fun BvFavoritePageEntity.toBackup(): BvPageBackup =
    BvPageBackup(bvid, cid, pageIndex, title, durationMs, order)

private fun HistoryEntity.toBackup(): HistoryBackup =
    HistoryBackup(bvid, cid, positionMs, updatedAt, listenDurationMs, sourceType, sourceId, sourceTitle)

private fun BvFavoriteBackup.toEntity(): BvFavoriteEntity =
    BvFavoriteEntity(bvid, title, author, authorMid, cover, durationMs, pageCount, addedAt, lastSyncedAt)

private fun BvPageBackup.toEntity(): BvFavoritePageEntity =
    BvFavoritePageEntity(bvid, cid, pageIndex, title, durationMs, order)

private fun HistoryBackup.toEntity(folderIdMap: Map<Long, Long>): HistoryEntity {
    val mappedSourceId = if (sourceType == HistoryEntity.SOURCE_FOLDER) {
        idOfFolder(sourceId, folderIdMap) ?: sourceId
    } else {
        sourceId.ifBlank { bvid }
    }
    return HistoryEntity(
        bvid = bvid,
        cid = cid,
        positionMs = positionMs,
        updatedAt = updatedAt,
        listenDurationMs = listenDurationMs,
        sourceType = sourceType,
        sourceId = mappedSourceId,
        sourceTitle = sourceTitle,
    )
}

private fun idOfFolder(sourceId: String, folderIdMap: Map<Long, Long>): String? =
    sourceId.toLongOrNull()?.let { folderIdMap[it] }?.toString()

private fun BiliSession.toBackup(): AuthBackup =
    AuthBackup(sessdata, biliJct, uid, userName, face, refreshToken, extras)

private fun AuthBackup.toSession(): BiliSession =
    BiliSession(sessdata, biliJct, uid, userName, face, refreshToken, extras)
