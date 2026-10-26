package cn.debubu.tingbili.core.data.di

import android.content.Context
import androidx.room.Room
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HomeSnapshotDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayQueueDao
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.SubtitleDiskDao
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room 归 core:data 所有（之前挤在 :app 的 AppDataModule）。
 * Migration 全量保留，降级破坏仅放行 downgrade。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): TingBiliDatabase =
        Room.databaseBuilder(ctx, TingBiliDatabase::class.java, "tingbili.db")
            .addMigrations(
                TingBiliDatabase.MIGRATION_1_2,
                TingBiliDatabase.MIGRATION_2_3,
                TingBiliDatabase.MIGRATION_3_4,
                TingBiliDatabase.MIGRATION_4_5,
                TingBiliDatabase.MIGRATION_5_6,
                TingBiliDatabase.MIGRATION_6_7,
                TingBiliDatabase.MIGRATION_7_8,
                TingBiliDatabase.MIGRATION_8_9,
                TingBiliDatabase.MIGRATION_9_10,
                TingBiliDatabase.MIGRATION_10_11
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideHistoryDao(db: TingBiliDatabase): HistoryDao = db.historyDao()

    @Provides
    fun providePlaylistDao(db: TingBiliDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideHomeSnapshotDao(db: TingBiliDatabase): HomeSnapshotDao = db.homeSnapshotDao()

    @Provides
    fun provideSubtitleDiskDao(db: TingBiliDatabase): SubtitleDiskDao = db.subtitleDiskDao()

    @Provides
    fun providePlayUrlCacheDao(db: TingBiliDatabase): PlayUrlCacheDao = db.playUrlCacheDao()

    @Provides
    fun providePlayQueueDao(db: TingBiliDatabase): PlayQueueDao = db.playQueueDao()
}
