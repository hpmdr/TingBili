package cn.debubu.tingbili.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** DataStore + Repository 归 core:data 所有（文件名 tingbili_prefs 不变，老数据无缝）。 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { ctx.preferencesDataStoreFile("tingbili_prefs") }
        )

    @Provides
    @Singleton
    fun providePreferencesRepository(ds: DataStore<Preferences>): PreferencesRepository =
        PreferencesRepository(ds)
}
