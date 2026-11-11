package cn.debubu.tingbili.di

import cn.debubu.tingbili.backup.AppBackupStore
import cn.debubu.tingbili.core.data.backup.BackupStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** BackupStore 接口在 core:data，实现放 :app（跨 Room/DataStore/登录仓储）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {

    @Binds
    @Singleton
    abstract fun bindBackupStore(impl: AppBackupStore): BackupStore
}
