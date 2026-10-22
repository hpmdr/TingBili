package cn.debubu.tingbili.data.bilibili.di

import cn.debubu.tingbili.core.media.ArtworkResolver
import cn.debubu.tingbili.core.media.CatalogResolver
import cn.debubu.tingbili.core.media.PlayUrlResolver
import cn.debubu.tingbili.data.bilibili.BiliMediaResolvers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** core:media 接口 → B 站实现的绑定，播放器经此与 B 站解耦。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ResolverModule {

    @Binds
    @Singleton
    abstract fun bindPlayUrlResolver(impl: BiliMediaResolvers): PlayUrlResolver

    @Binds
    @Singleton
    abstract fun bindCatalogResolver(impl: BiliMediaResolvers): CatalogResolver

    @Binds
    @Singleton
    abstract fun bindArtworkResolver(impl: BiliMediaResolvers): ArtworkResolver
}
