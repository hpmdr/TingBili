package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality
import cn.debubu.tingbili.data.bilibili.dto.BiliFavFolder
import cn.debubu.tingbili.data.bilibili.dto.BiliSpaceVideo
import cn.debubu.tingbili.data.bilibili.dto.SubtitleFile
import cn.debubu.tingbili.data.bilibili.dto.toFolders
import cn.debubu.tingbili.data.bilibili.dto.toSpaceVideos
import cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack
import cn.debubu.tingbili.data.bilibili.dto.SubtitleResult
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.normalizeBiliImageUrl
import cn.debubu.tingbili.data.bilibili.dto.DashAudio
import cn.debubu.tingbili.data.bilibili.dto.toAudioOptions
import cn.debubu.tingbili.data.bilibili.dto.toAudioUrl
import cn.debubu.tingbili.data.bilibili.dto.toLyricLines
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val searchMutex = Mutex()
    private var lastSearchAtMs = 0L

    private suspend fun throttleSearch() {
        searchMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = SEARCH_MIN_INTERVAL_MS - (now - lastSearchAtMs)
            if (wait > 0) delay(wait)
            lastSearchAtMs = System.currentTimeMillis()
        }
    }

    private fun checkSearchCode(dtoCode: Int, dtoMessage: String): String? {
        if (dtoCode == 0) return null
        if (dtoCode == 412 || dtoCode == -412) {
            val detail = dtoMessage.ifBlank { "请求被拦截" }
            throw BiliRateLimitedException("请求太频繁（412），稍后再试：$detail")
        }
        return dtoMessage.ifBlank { "search failed: code $dtoCode" }
    }

    suspend fun search(keyword: String): Result<List<Track>> = try {
        throttleSearch()
        val dto = api.search(keyword)
        val err = checkSearchCode(dto.code, dto.message)
        if (err != null) {
            Result.Error(err)
        } else {
            Result.Success(dto.toTracks())
        }
    } catch (e: BiliRateLimitedException) {
        Result.Error(e.message ?: "请求太频繁（412），稍后再试", e)
    } catch (e: Exception) {
        Result.Error(e.message ?: "search failed", e)
    }

    /**
     * Paging3 entry — returns raw List<Track> or throws on error.
     * Used by BiliPagingSource; page 1-indexed.
     */
    suspend fun searchPage(keyword: String, page: Int = 1): List<Track> {
        if (keyword.isBlank()) return emptyList()
        throttleSearch()
        val dto = api.search(keyword, page = page)
        val err = checkSearchCode(dto.code, dto.message)
        if (err != null) throw IllegalStateException(err)
        return dto.toTracks()
    }

    /** 搜索分页 + 结果总数（搜索页结果计数条用；总数为接口返回的全量命中数） */
    suspend fun searchPageWithTotal(keyword: String, page: Int = 1): Pair<List<Track>, Int> {
        if (keyword.isBlank()) return emptyList<Track>() to 0
        throttleSearch()
        val dto = api.search(keyword, page = page)
        val err = checkSearchCode(dto.code, dto.message)
        if (err != null) throw IllegalStateException(err)
        return dto.toTracks() to (dto.data?.numResults ?: 0)
    }

    companion object {
        /** 两次搜索最小间隔：压住快速上滑时的连续 append 请求，防 412。 */
        const val SEARCH_MIN_INTERVAL_MS = 800L
        /** view / viewDetail 内存 memo TTL：回退/重进详情、历史 N 路回访直接命中，只缓存成功态 */
        const val VIEW_MEMO_TTL_MS = 300_000L
    }

    private data class ViewEntry(val tracks: List<Track>, val fetchedAtMs: Long)
    private data class DetailEntry(val data: ViewData, val fetchedAtMs: Long)
    private val viewMutex = Mutex()
    private val viewCache = mutableMapOf<String, ViewEntry>()
    private val detailCache = mutableMapOf<String, DetailEntry>()

    suspend fun getView(bvid: String): Result<List<Track>> {
        if (bvid.isNotBlank()) {
            viewMutex.withLock {
                viewCache[bvid]?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < VIEW_MEMO_TTL_MS }
                    ?.let { return Result.Success(it.tracks) }
            }
        }
        return try {
            val dto = api.view(bvid)
            if (dto.code != 0) {
                Result.Error(dto.message.ifBlank { "view failed: code ${dto.code}" })
            } else {
                val tracks = dto.toTracks()
                if (bvid.isNotBlank()) {
                    viewMutex.withLock { viewCache[bvid] = ViewEntry(tracks, System.currentTimeMillis()) }
                }
                Result.Success(tracks)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "view failed", e)
        }
    }

    /**
     * 详情页：返回完整 ViewData（含简介/UP主/统计/分P），调用方自行转 Track。
     * 成功态内存 memo 5min；[forceRefresh] 给“刷新当前内容”用，跳过缓存直取最新。
     */
    suspend fun getViewDetail(bvid: String, forceRefresh: Boolean = false): Result<ViewData> {
        if (!forceRefresh && bvid.isNotBlank()) {
            viewMutex.withLock {
                detailCache[bvid]?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < VIEW_MEMO_TTL_MS }
                    ?.let { return Result.Success(it.data) }
            }
        }
        return try {
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
                if (bvid.isNotBlank()) {
                    viewMutex.withLock { detailCache[bvid] = DetailEntry(normalized, System.currentTimeMillis()) }
                }
                Result.Success(normalized)
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "view failed", e)
        }
    }

    /**
     * 相关推荐（懒加载：详情 VM 只在用户首次切到推荐 Tab 时调这里）。
     * 失败不阻塞详情主内容（调用方在推荐区单独展示重试）。
     * 接口无分页，一次约 10~20 条；过滤掉当前视频自己。
     */
    suspend fun getRelated(bvid: String): Result<List<Track>> = try {
        val dto = api.related(bvid)
        val err = checkSearchCode(dto.code, dto.message)
        if (err != null) {
            Result.Error(err)
        } else {
            Result.Success(dto.toTracks().filter { it.bvid != bvid })
        }
    } catch (e: BiliRateLimitedException) {
        Result.Error(e.message ?: "请求太频繁（412），稍后再试", e)
    } catch (e: Exception) {
        Result.Error(e.message ?: "related failed", e)
    }

    /**
     * 取音频直链：qn 下发偏好档位做服务端鉴权，返回后再按偏好 rank 二次精选（见 selectAudio）。
     * 默认偏好 132K（免登录可得最佳档）；服务端只给低档时诚实回落，不断播。
     */
    suspend fun getPlayUrl(
        bvid: String,
        cid: Long,
        preferQuality: Int = AudioQuality.DEFAULT_CODE,
    ): Result<String> = try {
        val dto = api.playUrl(bvid, cid, qn = preferQuality)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "playurl failed: code ${dto.code}" })
        } else {
            val url = dto.toAudioUrl(preferQuality)
            if (url.isNullOrBlank()) {
                Result.Error("no play url")
            } else {
                Result.Success(url)
            }
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "playurl failed", e)
    }

    /** 音质面板用：本次服务端实际返回的可用音频轨（id/带宽），展示可用性与当前有效档。 */
    suspend fun getAudioOptions(bvid: String, cid: Long): Result<List<DashAudio>> = try {
        val dto = api.playUrl(bvid, cid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "playurl failed: code ${dto.code}" })
        } else {
            Result.Success(dto.toAudioOptions())
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
    suspend fun getSubtitle(bvid: String, cid: Long): Result<SubtitleResult> = try {
        when (val tracks = listSubtitleTracks(bvid, cid)) {
            is Result.Error -> tracks
            is Result.Success -> {
                val track = tracks.data.firstOrNull()
                if (track == null) {
                    Result.Success(SubtitleResult())
                } else {
                    // 正文为空（404/被清理）按没字幕处理，label 也不带
                    val lines = loadSubtitleLines(track.url)
                    if (lines.isEmpty()) Result.Success(SubtitleResult())
                    else Result.Success(SubtitleResult(lines, track.displayLabel, track.isAi))
                }
            }
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "subtitle failed", e)
    }

    /** 列出可用字幕轨（已按中文优先、人工优先排好，供字幕切换用） */
    suspend fun listSubtitleTracks(bvid: String, cid: Long): Result<List<SubtitleTrack>> = try {
        val dto = api.subtitle(bvid, cid)
        if (dto.code != 0) {
            Result.Error(dto.message.ifBlank { "subtitle failed: code ${dto.code}" })
        } else {
            Result.Success(dto.toTracks())
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "subtitle failed", e)
    }

    // ── 我的页:B站账号体系(只读,需登录 Cookie) ──

    suspend fun getFavFolders(upMid: Long, pn: Int = 1, ps: Int = 20): Result<Pair<List<BiliFavFolder>, Boolean>> = try {
        val dto = api.favFolders(pn, ps, upMid)
        if (dto.code != 0) Result.Error(dto.message.ifBlank { "fav folders failed: code ${dto.code}" })
        else Result.Success(dto.toFolders())
    } catch (e: Exception) {
        Result.Error(e.message ?: "fav folders failed", e)
    }

    suspend fun getFavMedias(mediaId: Long, pn: Int = 1, ps: Int = 20): Result<Pair<List<BiliSpaceVideo>, Boolean>> = try {
        val dto = api.favMedias(mediaId, pn, ps)
        if (dto.code != 0) Result.Error(dto.message.ifBlank { "fav medias failed: code ${dto.code}" })
        else Result.Success(dto.toSpaceVideos())
    } catch (e: Exception) {
        Result.Error(e.message ?: "fav medias failed", e)
    }

    suspend fun getToview(pn: Int = 1, ps: Int = 20): Result<Pair<List<BiliSpaceVideo>, Int>> = try {
        val dto = api.toview(pn, ps)
        if (dto.code != 0) Result.Error(dto.message.ifBlank { "toview failed: code ${dto.code}" })
        else Result.Success(dto.toSpaceVideos() to (dto.data?.count ?: 0))
    } catch (e: Exception) {
        Result.Error(e.message ?: "toview failed", e)
    }

    suspend fun getBiliHistory(max: Long = 0L, viewAt: Long = 0L, ps: Int = 20): Result<List<BiliSpaceVideo>> = try {
        val dto = api.historyCursor(ps = ps, max = max, viewAt = viewAt)
        if (dto.code != 0) Result.Error(dto.message.ifBlank { "history failed: code ${dto.code}" })
        else Result.Success(dto.toSpaceVideos())
    } catch (e: Exception) {
        Result.Error(e.message ?: "history failed", e)
    }

    /** 下载并解析字幕正文；CDN 返回 404 / 空体 / 非预期格式都按「没字幕」处理 */
    suspend fun loadSubtitleLines(url: String): List<LyricLine> =
        when (val r = loadSubtitleLinesResult(url)) {
            is Result.Success -> r.data
            is Result.Error -> emptyList()
        }

    /**
     * 同上，但区分「失败」与「空」：HTTP 非 2xx / 异常 / 解析失败走 Error（UI 给重试），
     * 空正文走 Success(empty)（UI 按无字幕处理）。CDN 抽风是已知问题，吞掉就没法重试。
     */
    suspend fun loadSubtitleLinesResult(url: String): Result<List<LyricLine>> = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use Result.Error("字幕下载失败：${response.code}")
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) return@use Result.Success(emptyList())
                try {
                    Result.Success(json.decodeFromString<SubtitleFile>(text).toLyricLines())
                } catch (e: Exception) {
                    Result.Error("字幕解析失败", e)
                }
            }
        } catch (e: Exception) {
            Result.Error("字幕网络异常", e)
        }
    }
}

/** B 站搜索风控（412）：调用方应退避重试，不要连点。 */
class BiliRateLimitedException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
