package cn.debubu.tingbili.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.debubu.tingbili.core.data.db.FavoriteDao
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HomeSnapshotDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayQueueDao
import cn.debubu.tingbili.core.data.db.SubtitleDiskDao
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room 归 core:data 所有。
 *
 * 收藏系统这次是明确的“全库清空”升级：旧版（v11 及以前）不再提供 Migration，
 * 升级时由 Room 直接 drop 所有表并重建当前 schema。
 * v12→v13 只给播放队列快照补 `sourceFolderId`（队列跟随收藏夹），手写 Migration 保数据。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** v13：播放队列记住来源收藏夹，冷启动恢复后仍能跟随夹内增删/排序。 */
    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE PlayQueueSnapshotEntity ADD COLUMN sourceFolderId INTEGER")
        }
    }

    /** v14：播放列表自己的正/倒序与随机开关（随当前队列保存，不是全局偏好）。 */
    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE PlayQueueSnapshotEntity ADD COLUMN playOrder INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE PlayQueueSnapshotEntity ADD COLUMN shuffleEnabled INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /**
     * v15：历史从「一个 BV 一行」升级为「一个播单一行」（BV / 收藏夹），
     * 主键改为 (sourceType, sourceId)；旧数据全部视为 BV 播单，位置不丢。
     */
    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `HistoryEntity_new` (
                    `bvid` TEXT NOT NULL,
                    `cid` INTEGER NOT NULL,
                    `positionMs` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `listenDurationMs` INTEGER NOT NULL,
                    `sourceType` INTEGER NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `sourceTitle` TEXT,
                    PRIMARY KEY(`sourceType`, `sourceId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `HistoryEntity_new`
                    (`bvid`, `cid`, `positionMs`, `updatedAt`, `listenDurationMs`, `sourceType`, `sourceId`, `sourceTitle`)
                SELECT `bvid`, `cid`, `positionMs`, `updatedAt`, `listenDurationMs`, 0, `bvid`, NULL
                FROM `HistoryEntity`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `HistoryEntity`")
            db.execSQL("ALTER TABLE `HistoryEntity_new` RENAME TO `HistoryEntity`")
        }
    }

    /** v17：直链缓存补 bandwidthBps（Hi-Res FLAC 容器无码率，播放信息页兜底用）。 */
    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `PlayUrlCacheEntity` ADD COLUMN `bandwidthBps` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    /** v18：直链缓存补 audioCode（底部音质标签显示实际生效档）。 */
    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `PlayUrlCacheEntity` ADD COLUMN `audioCode` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): TingBiliDatabase =
        Room.databaseBuilder(ctx, TingBiliDatabase::class.java, "tingbili.db")
            .addMigrations(
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )
            // 注意：不要再接 fallbackToDestructiveMigrationOnDowngrade(true)，
            // 它会把 requireMigration 重新设为 true，导致升级 fallback 失效。
            // 这个重载本身已设置 allowDestructiveMigrationOnDowngrade = true。
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideFavoriteDao(db: TingBiliDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideHistoryDao(db: TingBiliDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideHomeSnapshotDao(db: TingBiliDatabase): HomeSnapshotDao = db.homeSnapshotDao()

    @Provides
    fun provideSubtitleDiskDao(db: TingBiliDatabase): SubtitleDiskDao = db.subtitleDiskDao()

    @Provides
    fun providePlayUrlCacheDao(db: TingBiliDatabase): PlayUrlCacheDao = db.playUrlCacheDao()

    @Provides
    fun providePlayQueueDao(db: TingBiliDatabase): PlayQueueDao = db.playQueueDao()
}
