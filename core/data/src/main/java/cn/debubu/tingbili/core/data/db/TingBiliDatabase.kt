package cn.debubu.tingbili.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class, HistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TingBiliDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao

    companion object {
        /** 纯新增可空列 cover，AutoMigration 失效时的兜底手动迁移 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlaylistEntity ADD COLUMN cover TEXT")
            }
        }
    }
}
