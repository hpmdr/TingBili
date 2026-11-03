package cn.debubu.tingbili.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteFolderEntity::class,
        FavoriteTrackEntity::class,
        BvFavoriteEntity::class,
        BvFavoritePageEntity::class,
        HistoryEntity::class,
        HomeSnapshotEntity::class,
        SubtitleDiskEntity::class,
        PlayUrlCacheEntity::class,
        PlayQueueSnapshotEntity::class,
    ],
    // v16：HistoryEntity 补 bvid/updatedAt 索引、FavoriteTrackEntity 补 (bvid,cid) 索引（REVIEW_REPORT E-07）。
    // v17：PlayUrlCacheEntity 补 bandwidthBps 列（Hi-Res FLAC 容器无码率，播放信息页兜底用）。
    // v18：PlayUrlCacheEntity 补 audioCode 列（底部音质标签显示实际生效档）。
    //      简单加列，写手写迁移避免破坏性升级清掉收藏/历史。
    version = 18,
    exportSchema = false,
)
abstract class TingBiliDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun homeSnapshotDao(): HomeSnapshotDao
    abstract fun subtitleDiskDao(): SubtitleDiskDao
    abstract fun playUrlCacheDao(): PlayUrlCacheDao
    abstract fun playQueueDao(): PlayQueueDao
}
