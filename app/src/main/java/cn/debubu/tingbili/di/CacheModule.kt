package cn.debubu.tingbili.di

import cn.debubu.tingbili.cache.AppCacheManager
import cn.debubu.tingbili.core.data.cache.CacheManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CacheModule {

    @Binds
    abstract fun bindCacheManager(impl: AppCacheManager): CacheManager
}
