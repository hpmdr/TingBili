package cn.debubu.tingbili.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 协程调度器注入点（REVIEW_REPORT D-07）：仓储/VM 里的 `Dispatchers.IO` 硬编码让单测只能用真实线程，
 * `runTest` 的虚拟时间也管不到。统一从这里注入，单测传 `StandardTestDispatcher` 即确定性。
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    @Named(DISPATCHER_IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

const val DISPATCHER_IO = "ioDispatcher"