package cn.debubu.tingbili.core.data.di

import android.content.Context
import cn.debubu.tingbili.core.data.network.AndroidNetworkMonitor
import cn.debubu.tingbili.core.data.network.NetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 全局网络感知：单例随进程，core:media 与各 feature 经 core:data 共享同一份信号。 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkMonitorModule {

    @Provides
    @Singleton
    fun provideNetworkMonitor(@ApplicationContext context: Context): NetworkMonitor =
        AndroidNetworkMonitor(context)
}
