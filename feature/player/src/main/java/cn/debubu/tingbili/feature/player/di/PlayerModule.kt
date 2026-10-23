package cn.debubu.tingbili.feature.player.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 字幕管线 IO 调度器：万行字幕 JSON 编解码 + Room 读写不在主线程；单测直传 TestDispatcher。 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    @Named("subtitleIo")
    fun provideSubtitleIo(): CoroutineDispatcher = Dispatchers.IO
}
