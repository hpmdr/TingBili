package cn.debubu.tingbili.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class, HistoryEntity::class, HomeSnapshotEntity::class, SubtitleDiskEntity::class, PlayUrlCacheEntity::class, PlayQueueSnapshotEntity::class],
    version = 10,
    exportSchema = false
)
abstract class TingBiliDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun homeSnapshotDao(): HomeSnapshotDao
    abstract fun subtitleDiskDao(): SubtitleDiskDao
    abstract fun playUrlCacheDao(): PlayUrlCacheDao
    abstract fun playQueueDao(): PlayQueueDao

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
        /** 纯新增非空列 listenDurationMs（本地累计收听时长），老数据从 0 开始统计 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE HistoryEntity ADD COLUMN listenDurationMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** 新增首页快照单行表（SWR 秒开），老库直接建表即可，无数据迁移 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `HomeSnapshotEntity` (`id` INTEGER NOT NULL, `keywordsJson` TEXT NOT NULL, `tracksJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        /** 新增字幕磁盘快照表（SWR 秒显旧字幕），老库直接建表即可，无数据迁移 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `SubtitleDiskEntity` (`cacheKey` TEXT NOT NULL, `tracksJson` TEXT NOT NULL, `index` INTEGER NOT NULL, `linesJson` TEXT NOT NULL, `label` TEXT NOT NULL, `isAi` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))")
            }
        }
        /** 新增 playurl 磁盘缓存表（替代 DataStore 大 Map，老 JSON 由启动迁移搬运后删除） */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `PlayUrlCacheEntity` (`key` TEXT NOT NULL, `url` TEXT NOT NULL, `fetchedAtMs` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }
        /** 新增播放队列快照单行表（替代 DataStore 三键分写，原子落盘防错位） */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `PlayQueueSnapshotEntity` (`id` INTEGER NOT NULL, `tracksJson` TEXT NOT NULL, `currentIndex` INTEGER NOT NULL, `sourceTitle` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}
