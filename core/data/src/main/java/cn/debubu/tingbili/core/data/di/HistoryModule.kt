package cn.debubu.tingbili.core.data.di

import cn.debubu.tingbili.core.data.repository.DefaultHistoryRepository
import cn.debubu.tingbili.core.data.repository.HistoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HistoryModule {

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: DefaultHistoryRepository): HistoryRepository
}