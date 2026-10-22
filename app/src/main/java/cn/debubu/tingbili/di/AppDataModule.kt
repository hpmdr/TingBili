package cn.debubu.tingbili.di

import cn.debubu.tingbili.core.ui.HomeScrollTopBus
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * :app 只保留必须住在壳里的绑定：
 * - AuthStore：实现 EncryptedAuthStore 用了 Security Crypto，归壳所有；
 * - HomeScrollTopBus：core:ui 暂无 Hilt 模块，单例放这里（后续 core:ui 建 di 再搬）。
 *
 * Room/DataStore 已还给 core:data（DatabaseModule/PreferencesModule），
 * B 站网络栈已还给 data:bilibili（NetworkModule）。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppDataModule {

    @Provides
    @Singleton
    fun provideHomeScrollTopBus(): HomeScrollTopBus = HomeScrollTopBus()

    @Provides
    @Singleton
    fun provideAuthStore(store: EncryptedAuthStore): AuthStore = store
}
