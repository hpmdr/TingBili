package cn.debubu.tingbili.core.data.di

import cn.debubu.tingbili.core.data.repository.DefaultFavoritesRepository
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FavoritesModule {

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(
        impl: DefaultFavoritesRepository,
    ): FavoritesRepository
}
