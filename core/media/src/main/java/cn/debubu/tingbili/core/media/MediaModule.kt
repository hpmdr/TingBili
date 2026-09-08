package cn.debubu.tingbili.core.media

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun providePlayerHandle(connection: PlaybackConnection): PlayerHandle =
        MediaControllerHandle(connection)

    @Provides
    @Singleton
    fun provideTimerManager(handle: PlayerHandle): TimerManager = TimerManager(handle)
}
