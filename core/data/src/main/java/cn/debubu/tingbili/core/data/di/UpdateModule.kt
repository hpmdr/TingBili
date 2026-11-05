package cn.debubu.tingbili.core.data.di

import cn.debubu.tingbili.core.data.update.GitHubUpdateDataSource
import cn.debubu.tingbili.core.data.update.UpdateDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 更新检查绑定：feature 只依赖接口，实现归 core:data。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindUpdateDataSource(impl: GitHubUpdateDataSource): UpdateDataSource
}
