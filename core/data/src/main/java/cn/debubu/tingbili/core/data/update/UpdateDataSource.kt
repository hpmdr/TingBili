package cn.debubu.tingbili.core.data.update

import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 更新数据源口子：生产走 GitHub API，单测注入 Fake。 */
interface UpdateDataSource {
    suspend fun fetchLatest(): Result<GitHubRelease>
}

/**
 * GitHub Releases 直查（独立 OkHttp，不走 B 站的 WbiInterceptor/Cookie 栈）。
 *
 * 注意匿名限额 60 次/小时：自动检查每天最多一次 + 手动入口，远够不上。
 */
@Singleton
class GitHubUpdateDataSource @Inject constructor() : UpdateDataSource {

    /** 单测换 MockWebServer 地址（生产恒为 LATEST_URL）。 */
    @VisibleForTesting
    var endpoint: String = UpdateChecker.LATEST_URL

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun fetchLatest(): Result<GitHubRelease> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "TingBili")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("github ${resp.code}"))
                    }
                    val raw = resp.body.string()
                    Result.success(json.decodeFromString<GitHubRelease>(raw))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
