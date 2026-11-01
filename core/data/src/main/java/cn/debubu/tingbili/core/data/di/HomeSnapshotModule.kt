package cn.debubu.tingbili.core.data.di

import cn.debubu.tingbili.core.data.repository.DefaultHomeSnapshotRepository
import cn.debubu.tingbili.core.data.repository.HomeSnapshotRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HomeSnapshotModule {

    @Binds
    @Singleton
    abstract fun bindHomeSnapshotRepository(
        impl: DefaultHomeSnapshotRepository,
    ): HomeSnapshotRepository
}