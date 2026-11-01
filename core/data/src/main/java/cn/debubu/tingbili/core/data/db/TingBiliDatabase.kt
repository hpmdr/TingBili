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
    // 单人测试期允许破坏性升级：不写 15→16 迁移，由 fallbackToDestructiveMigration 重建（升级即清库）。
    version = 16,
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
