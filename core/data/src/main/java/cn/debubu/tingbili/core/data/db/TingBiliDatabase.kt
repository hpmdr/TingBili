package cn.debubu.tingbili.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TingBiliDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
}
