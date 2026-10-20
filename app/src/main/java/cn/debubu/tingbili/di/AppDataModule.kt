package cn.debubu.tingbili.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.HistoryDao
import cn.debubu.tingbili.core.data.db.HomeSnapshotDao
import cn.debubu.tingbili.core.data.db.PlaylistDao
import cn.debubu.tingbili.core.data.db.SubtitleDiskDao
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.BuvidProvider
import cn.debubu.tingbili.data.bilibili.WbiInterceptor
import cn.debubu.tingbili.data.bilibili.WbiSigner
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
        Room.databaseBuilder(ctx, TingBiliDatabase::class.java, "tingbili.db")
            .addMigrations(
                TingBiliDatabase.MIGRATION_1_2,
                TingBiliDatabase.MIGRATION_2_3,
                TingBiliDatabase.MIGRATION_3_4,
                TingBiliDatabase.MIGRATION_4_5,
                TingBiliDatabase.MIGRATION_5_6,
                TingBiliDatabase.MIGRATION_6_7,
                TingBiliDatabase.MIGRATION_7_8
            )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideHistoryDao(db: TingBiliDatabase): HistoryDao = db.historyDao()

    @Provides
    fun providePlaylistDao(db: TingBiliDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideHomeSnapshotDao(db: TingBiliDatabase): HomeSnapshotDao = db.homeSnapshotDao()

    @Provides
    fun provideSubtitleDiskDao(db: TingBiliDatabase): SubtitleDiskDao = db.subtitleDiskDao()

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
    fun provideWbiSigner(json: Json): WbiSigner = WbiSigner(json)

    @Provides
    @Singleton
    fun provideBuvidProvider(): BuvidProvider = BuvidProvider()

    @Provides
    @Singleton
    fun provideAuthStore(store: EncryptedAuthStore): AuthStore = store

    @Provides
    @Singleton
    fun provideOkHttp(
        wbiSigner: WbiSigner,
        buvidProvider: BuvidProvider,
        authRepository: AuthRepository,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                // 登录态 Cookie 优先（配额更高、风控更松），游客模式回落 buvid3/4
                WbiInterceptor(wbiSigner, cookieProvider = {
                    val session = authRepository.sessionFlow.value?.toCookieHeader()
                    val buvid = buvidProvider.cookie()
                    listOfNotNull(session, buvid)
                        .map { it.trim().trim(';') }
                        .filter { it.isNotBlank() }
                        .joinToString("; ")
                        .ifBlank { null }
                })
            )
            .build()

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
