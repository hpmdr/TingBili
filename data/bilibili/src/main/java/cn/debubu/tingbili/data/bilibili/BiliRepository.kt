package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.dto.SubtitleFile
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.normalizeBiliImageUrl
import cn.debubu.tingbili.data.bilibili.dto.toAudioUrl
import cn.debubu.tingbili.data.bilibili.dto.toLyricLines
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class BiliRepository @Inject constructor(
    private val api: BiliApi,
    // 复用 AppDataModule 里那个带 UA/Referer/buvid 的客户端：
    // WbiInterceptor 只对 /search/ 与 /wbi/ 路径签名，拉 CDN 字幕文件不受影响。
    // 默认值只为手写构造的单元测试兜底，Hilt 注入时仍走 AppDataModule 的单例。
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun search(keyword: String): Result<List<Track>> = try {
        val dto = api.search(keyword)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "search failed: code ${dto.code}" })
        } else {
            Result.Success(dto.toTracks())
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "search failed", e)
    }

    /**
     * Paging3 entry — returns raw List<Track> or throws on error.
     * Used by BiliPagingSource; page 1-indexed.
     */
    suspend fun searchPage(keyword: String, page: Int = 1): List<Track> {
        if (keyword.isBlank()) return emptyList()
        val dto = api.search(keyword, page = page)
        if (dto.code != 0) throw IllegalStateException(dto.message.ifBlank { "search failed: code ${dto.code}" })
        return dto.toTracks()
    }

    suspend fun getView(bvid: String): Result<List<Track>> = try {
        val dto = api.view(bvid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "view failed: code ${dto.code}" })
        } else {
            val tracks = dto.toTracks()
            Result.Success(tracks)
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "view failed", e)
    }

    /** 详情页：返回完整 ViewData（含简介/UP主/统计/分P），调用方自行转 Track */
    suspend fun getViewDetail(bvid: String): Result<ViewData> = try {
        val dto = api.view(bvid)
        if (dto.code != 0 || dto.data == null) {
            Result.Error(dto.message.ifBlank { "view failed: code ${dto.code}" })
        } else {
            val data = dto.data
            // 封面/头像可能是 http:// 明文，Android 默认禁明文会加载失败，统一升级 https
            val normalized = data.copy(
                pic = data.pic.normalizeBiliImageUrl(),
                owner = data.owner?.copy(face = data.owner.face.normalizeBiliImageUrl())
            )
            Result.Success(normalized)
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "view failed", e)
    }

    suspend fun getPlayUrl(bvid: String, cid: Long): Result<String> = try {
        val dto = api.playUrl(bvid, cid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "playurl failed: code ${dto.code}" })
        } else {
            val url = dto.toAudioUrl()
            if (url.isNullOrBlank()) {
                Result.Error("no play url")
            } else {
                Result.Success(url)
            }
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "playurl failed", e)
    }

    /**
     * 取字幕要两跳：`/x/player/wbi/v2` 只给出字幕轨列表，
     * 选中一条后再去它的 `subtitle_url` 下载正文 `{"body":[...]}`。
     *
     * 视频压根没有字幕时返回空列表，不算错误。
     */
    suspend fun getSubtitle(bvid: String, cid: Long): Result<List<LyricLine>> = try {
        val dto = api.subtitle(bvid, cid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "subtitle failed: code ${dto.code}" })
        } else {
            val track = dto.toTracks().firstOrNull()
            Result.Success(if (track == null) emptyList() else fetchLyricLines(track.url))
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "subtitle failed", e)
    }

    /** 下载并解析字幕正文；CDN 返回 404 / 空体 / 非预期格式都按「没字幕」处理 */
    private suspend fun fetchLyricLines(url: String): List<LyricLine> = withContext(Dispatchers.IO) {
        runCatching {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) emptyList() else json.decodeFromString<SubtitleFile>(text).toLyricLines()
            }
        }.getOrDefault(emptyList())
    }
}
