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
    version = 15,
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
