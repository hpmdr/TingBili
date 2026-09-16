package cn.debubu.tingbili.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class, HistoryEntity::class],
    version = 5,
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
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlaylistEntity ADD COLUMN sourceBvid TEXT")
                db.execSQL("ALTER TABLE PlaylistEntity ADD COLUMN kind TEXT NOT NULL DEFAULT 'bv'")
                db.execSQL("ALTER TABLE PlaylistTrackEntity ADD COLUMN author TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE PlaylistTrackEntity ADD COLUMN cover TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE PlaylistTrackEntity ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_PlaylistEntity_sourceBvid ON PlaylistEntity(sourceBvid)")
            }
        }
        /** 纯新增可空列 videoTitle（听单曲目的合集名），老数据读出来是 null */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlaylistTrackEntity ADD COLUMN videoTitle TEXT")
            }
        }
        /** 纯新增可空列 pageIndex（听单曲目的分 P 序号），老数据读出来是 null */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlaylistTrackEntity ADD COLUMN pageIndex INTEGER")
            }
        }
    }
}
