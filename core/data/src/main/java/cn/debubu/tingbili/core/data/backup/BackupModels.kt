package cn.debubu.tingbili.core.data.backup

import kotlinx.serialization.Serializable

const val BACKUP_FORMAT = "tingbili-backup"
const val BACKUP_FORMAT_VERSION = 1

/** 备份文件根节点：format/version 先校验，data 才是业务数据。 */
@Serializable
data class BackupPayload(
    val format: String = BACKUP_FORMAT,
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    /** 文件里是否真的带了登录账号（UI 预览/导入确认用）。 */
    val includesAuth: Boolean = false,
    val data: BackupData = BackupData(),
)

@Serializable
data class BackupData(
    val settings: SettingsBackup = SettingsBackup(),
    val folders: List<FolderBackup> = emptyList(),
    val tracks: List<TrackBackup> = emptyList(),
    val bvFavorites: List<BvFavoriteBackup> = emptyList(),
    val bvPages: List<BvPageBackup> = emptyList(),
    val history: List<HistoryBackup> = emptyList(),
    /** 缺省/null = 不含登录账号。 */
    val auth: AuthBackup? = null,
)

@Serializable
data class FolderBackup(
    val id: Long,
    val name: String,
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
    val sortOrder: Int = 0,
)

@Serializable
data class TrackBackup(
    val folderId: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val durationMs: Long = 0L,
    val videoTitle: String? = null,
    val pageIndex: Int? = null,
    val order: Int = 0,
)

@Serializable
data class BvFavoriteBackup(
    val bvid: String,
    val title: String,
    val author: String = "",
    val authorMid: Long? = null,
    val cover: String = "",
    val durationMs: Long = 0L,
    val pageCount: Int = 0,
    val addedAt: Long = 0L,
    val lastSyncedAt: Long? = null,
)

@Serializable
data class BvPageBackup(
    val bvid: String,
    val cid: Long,
    val pageIndex: Int,
    val title: String,
    val durationMs: Long = 0L,
    val order: Int = 0,
)

@Serializable
data class HistoryBackup(
    val bvid: String,
    val cid: Long,
    val positionMs: Long,
    val updatedAt: Long = 0L,
    val listenDurationMs: Long = 0L,
    val sourceType: Int = 0,
    val sourceId: String = "",
    val sourceTitle: String? = null,
)

/**
 * 设置快照：字段全部可空，导入时只写非空项，兼容旧备份文件缺字段。
 * 搜索历史也走 DataStore，顺手放这里一起备份。
 */
@Serializable
data class SettingsBackup(
    val stepSec: Int? = null,
    val repeatMode: Int? = null,
    val randomPlayMode: Int? = null,
    val speed: Float? = null,
    val subtitleScale: Float? = null,
    val audioHwDecode: Boolean? = null,
    val audioOffload: Boolean? = null,
    val audioQuality: Int? = null,
    val sleepFinishChapter: Boolean? = null,
    val sleepLastMinutes: Int? = null,
    val themeMode: String? = null,
    val customThemeColor: Int? = null,
    val darkMode: String? = null,
    val darkThemeStyle: String? = null,
    val imageFormat: String? = null,
    val homeKeywords: List<String>? = null,
    val cacheMaxMb: Int? = null,
    val autoCheckUpdate: Boolean? = null,
    val searchHistory: List<String>? = null,
)

/** 登录账号快照（明文；SESSDATA 等同长期密码，导出前必须强提示）。 */
@Serializable
data class AuthBackup(
    val sessdata: String,
    val biliJct: String = "",
    val uid: Long = 0L,
    val userName: String = "",
    val face: String = "",
    val refreshToken: String = "",
    val extras: Map<String, String> = emptyMap(),
)

enum class ImportMode { MERGE, REPLACE }

data class ImportResult(
    val foldersAdded: Int = 0,
    val foldersMatched: Int = 0,
    val tracksAdded: Int = 0,
    val tracksSkipped: Int = 0,
    val bvFavorites: Int = 0,
    val historyAdded: Int = 0,
    val historyUpdated: Int = 0,
    val settingsRestored: Boolean = false,
    val authRestored: Boolean = false,
)

/** 导入前预览，不落库。 */
data class BackupPreview(
    val appVersion: String,
    val exportedAt: Long,
    val includesAuth: Boolean,
    val folderCount: Int,
    val trackCount: Int,
    val bvFavoriteCount: Int,
    val historyCount: Int,
    val searchHistoryCount: Int,
    val settingsCount: Int,
)
