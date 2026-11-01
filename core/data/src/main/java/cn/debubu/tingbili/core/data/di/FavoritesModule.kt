package cn.debubu.tingbili.core.data.di

import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.repository.DefaultFavoritesRepository
import cn.debubu.tingbili.core.data.repository.FavoriteEverRecorder
import cn.debubu.tingbili.core.data.repository.FavoritesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
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

    companion object {
        /** 收藏写路径置位「曾经有收藏」：让冷启动读一个布尔而不是首帧查 Room（REVIEW_REPORT B-02）。 */
        @Provides
        @Singleton
        fun provideFavoriteEverRecorder(prefs: PreferencesRepository): FavoriteEverRecorder =
            FavoriteEverRecorder { prefs.markFavoriteEver() }
    }
}
