package cn.debubu.tingbili.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.data.bilibili.BiliApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

@Module
@InstallIn(SingletonComponent::class)
object AppDataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): TingBiliDatabase =
        Room.databaseBuilder(ctx, TingBiliDatabase::class.java, "tingbili.db").build()

    @Provides
    fun provideHistoryDao(db: TingBiliDatabase): HistoryDao = db.historyDao()

    @Provides
    fun providePlaylistDao(db: TingBiliDatabase): PlaylistDao = db.playlistDao()

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

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * App-level bridge module. Provides Room, DataStore and Bilibili network bindings
     * required by [cn.debubu.tingbili.core.media.PlayerManager] via [cn.debubu.tingbili.navigation.MainViewModel].
     * Kept in `:app` to avoid cross-module ksp/hilt churn for Task 6; future split:
     * - core:data → TingBiliDatabase / HistoryDao / PlaylistDao / PreferencesRepository
     * - data:bilibili → OkHttp / Json / Retrofit / BiliApi
     * No duplication exists — core:data and data:bilibili currently expose no Hilt modules.
     */
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.bilibili.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideBiliApi(retrofit: Retrofit): BiliApi = retrofit.create(BiliApi::class.java)
}
