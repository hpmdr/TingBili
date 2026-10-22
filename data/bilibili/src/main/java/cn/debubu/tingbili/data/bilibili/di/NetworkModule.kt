package cn.debubu.tingbili.data.bilibili.di

import cn.debubu.tingbili.data.bilibili.BiliApi
import cn.debubu.tingbili.data.bilibili.BuvidProvider
import cn.debubu.tingbili.data.bilibili.WbiInterceptor
import cn.debubu.tingbili.data.bilibili.WbiSigner
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * B 站网络栈归 data:bilibili 所有（之前挤在 :app 的 AppDataModule）。
 * OkHttp 超时与 NetworkModule 同步：connect 10s / read 15s / call 20s。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Provides
    @Singleton
    fun provideWbiSigner(json: Json): WbiSigner = WbiSigner(json)

    @Provides
    @Singleton
    fun provideBuvidProvider(): BuvidProvider = BuvidProvider()

    @Provides
    @Singleton
    fun provideOkHttp(
        wbiSigner: WbiSigner,
        buvidProvider: BuvidProvider,
        authRepository: AuthRepository,
    ): OkHttpClient =
        OkHttpClient.Builder()
            // 超时兜底：无超时时 412/弱网会 hang 住 load 协程堆积
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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
