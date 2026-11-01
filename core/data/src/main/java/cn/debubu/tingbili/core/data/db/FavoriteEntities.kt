package cn.debubu.tingbili.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 默认收藏夹固定 id，所有入口先 ensure 再写入。 */
const val DEFAULT_FOLDER_ID: Long = 1L

@Entity(tableName = "FavoriteFolderEntity")
data class FavoriteFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)

@Entity(
    tableName = "FavoriteTrackEntity",
    primaryKeys = ["folderId", "bvid", "cid"],
    foreignKeys = [
        ForeignKey(
            entity = FavoriteFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    // (bvid,cid) 供切歌/收藏态查询（WHERE bvid=? AND cid=?）走索引，不再全表扫（REVIEW_REPORT E-07）
    indices = [Index("folderId"), Index(value = ["bvid", "cid"])],
)
data class FavoriteTrackEntity(
    val folderId: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val videoTitle: String? = null,
    val pageIndex: Int? = null,
    val order: Int = 0,
)

@Entity(tableName = "BvFavoriteEntity")
data class BvFavoriteEntity(
    @PrimaryKey val bvid: String,
    val title: String,
    val author: String = "",
    val authorMid: Long? = null,
    val cover: String = "",
    val durationMs: Long = 0,
    val pageCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long? = null,
)

@Entity(
    tableName = "BvFavoritePageEntity",
    primaryKeys = ["bvid", "cid"],
    foreignKeys = [
        ForeignKey(
            entity = BvFavoriteEntity::class,
            parentColumns = ["bvid"],
            childColumns = ["bvid"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bvid")],
)
data class BvFavoritePageEntity(
    val bvid: String,
    val cid: Long,
    val pageIndex: Int,
    val title: String,
    val durationMs: Long = 0,
    val order: Int = 0,
)

data class FavoriteFolderSummary(
    @Embedded val folder: FavoriteFolderEntity,
    val trackCount: Int,
    val totalDurationMs: Long,
    val firstCover: String?,
    val lastPlayedPageIndex: Int?,
    val lastPlayedPositionMs: Long?,
)
