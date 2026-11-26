package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality
import cn.debubu.tingbili.data.bilibili.dto.BiliFavFolder
import cn.debubu.tingbili.data.bilibili.dto.BiliSpaceVideo
import cn.debubu.tingbili.data.bilibili.dto.SubtitleFile
import cn.debubu.tingbili.data.bilibili.dto.toAllFolders
import cn.debubu.tingbili.data.bilibili.dto.toSpaceVideos
import cn.debubu.tingbili.data.bilibili.dto.SubtitleTrack
import cn.debubu.tingbili.data.bilibili.dto.SubtitleResult
import cn.debubu.tingbili.data.bilibili.dto.ViewData
import cn.debubu.tingbili.data.bilibili.dto.normalizeBiliImageUrl
import cn.debubu.tingbili.data.bilibili.dto.DashAudio
import cn.debubu.tingbili.data.bilibili.dto.selectAudio
import cn.debubu.tingbili.data.bilibili.dto.toAudioOptions
import cn.debubu.tingbili.data.bilibili.dto.toAudioUrl
import cn.debubu.tingbili.data.bilibili.dto.toLyricLines
import cn.debubu.tingbili.data.bilibili.dto.toTracks
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.dto.ReplyDetailDto
import cn.debubu.tingbili.data.bilibili.dto.ReplyMainDto
import cn.debubu.tingbili.data.bilibili.dto.toCommentDetailPage
import cn.debubu.tingbili.data.bilibili.dto.toCommentPage
import cn.debubu.tingbili.data.bilibili.model.CommentDetailPage
import cn.debubu.tingbili.data.bilibili.model.CommentPage
import cn.debubu.tingbili.data.bilibili.model.CommentSort
import cn.debubu.tingbili.data.bilibili.model.LyricLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named

class BiliRepository @Inject constructor(
    private val api: BiliApi,
    // 复用 AppDataModule 里那个带 UA/Referer/buvid 的客户端：
    // WbiInterceptor 只对 /search/ 与 /wbi/ 路径签名，拉 CDN 字幕文件不受影响。
    // 默认值只为手写构造的单元测试兜底，Hilt 注入时仍走 AppDataModule 的单例。
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    // 单测注入 StandardTestDispatcher；生产走 Dispatchers.IO（REVIEW_REPORT D-07）
    @Named(cn.debubu.tingbili.core.data.di.DISPATCHER_IO) private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 登录失效作废器：生产由 Hilt 注入单例 AuthRepository。
     * 默认 null 是为了不改动十几处测试里的手工构造；手工构造时跳过作废，
     * 保持原有行为。生产链路恒有值。
     */
    private val auth: AuthRepository? = null,
) {
    /**
     * 每个看到业务 code 的分支都调这里：命中 -101/-111 且本地有会话时，
     * 作废本地登录态（内存 + 加密存储），后续请求自动降为游客。
     * 调用方继续按原错误展示即可（稍后再看照旧显示「账号未登录」）。
     */
    private fun noteAuth(code: Int) {
        auth?.invalidateIfSessionExpired(code)
    }

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

    /**
     * 搜索类接口的风控判定。
     *
     * B 站有两种拦截形态：
     * 1. code 412/-412（硬拦）；
     * 2. **code=0 + data.v_voucher + result 为空**（软拦，最常见）。
     * 第二种不识别就会被当成“没有结果”，界面一直空白；这里统一抛 [BiliRateLimitedException]，
     * 由 PagingSource 退避后提示用户（PiliPlus 同款判定）。
     */
    private fun checkSearchCode(dtoCode: Int, dtoMessage: String, vVoucher: String? = null): String? {
        noteAuth(dtoCode)
        if (dtoCode == 412 || dtoCode == -412) {
            val detail = dtoMessage.ifBlank { "请求被拦截" }
            throw BiliRateLimitedException("请求太频繁（412），稍后再试：$detail")
        }
        if (dtoCode == 0 && !vVoucher.isNullOrBlank()) {
            throw BiliRateLimitedException("触发B站风控，请稍后再试")
        }
        if (dtoCode == 0) return null
        return dtoMessage.ifBlank { "search failed: code $dtoCode" }
    }

    suspend fun search(keyword: String): Result<List<Track>> = try {
        throttleSearch()
        val dto = api.search(keyword)
        val err = checkSearchCode(dto.code, dto.message, dto.data?.vVoucher)
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
        val err = checkSearchCode(dto.code, dto.message, dto.data?.vVoucher)
        if (err != null) throw IllegalStateException(err)
        return dto.toTracks()
    }

    /** 搜索分页 + 结果总数（搜索页结果计数条用；总数为接口返回的全量命中数） */
    suspend fun searchPageWithTotal(keyword: String, page: Int = 1): Pair<List<Track>, Int> {
        if (keyword.isBlank()) return emptyList<Track>() to 0
        throttleSearch()
        val dto = api.search(keyword, page = page)
        val err = checkSearchCode(dto.code, dto.message, dto.data?.vVoucher)
        if (err != null) throw IllegalStateException(err)
        return dto.toTracks() to (dto.data?.numResults ?: 0)
    }

    companion object {
        /** 两次搜索最小间隔：压住快速上滑时的连续 append 请求，防 412。 */
        const val SEARCH_MIN_INTERVAL_MS = 800L
        /** view / viewDetail 内存 memo TTL：回退/重进详情、历史 N 路回访直接命中，只缓存成功态 */
        const val VIEW_MEMO_TTL_MS = 300_000L

        /** 评论主列表（游标 + mode 排序，匿名可读） */
        private const val COMMENT_MAIN_URL = "https://api.bilibili.com/x/v2/reply/main"
        /**
         * 楼中楼全量（pn 翻页，匿名可读）。
         * 参数抄 PiliPlus ReplyHttp.replyReplyList：oid + root + pn + type + sort，
         * 游客态不带 csrf 也能读。
         */
        private const val COMMENT_REPLY_URL = "https://api.bilibili.com/x/v2/reply/reply"
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
                noteAuth(dto.code)
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
                noteAuth(dto.code)
                Result.Error(dto.message.ifBlank { "view failed: code ${dto.code}" })
            } else {
                val data = dto.data
                // 封面/头像可能是 http:// 明文，Android 默认禁明文会加载失败，统一升级 https
                val normalized = data.copy(
                    pic = data.pic.normalizeBiliImageUrl(),
                    pages = data.pages.map { page ->
                        page.copy(firstFrame = page.firstFrame?.takeIf { it.isNotBlank() }?.normalizeBiliImageUrl())
                    },
                    owner = data.owner?.copy(face = data.owner.face.normalizeBiliImageUrl()),
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
     * BV 评论（游标分页）。[offset] 首屏传空串，之后传上一页的 [CommentPage.nextOffset]。
     *
     * 走 [httpClient] 而不是 Retrofit：`pagination_str` 是一整段 JSON（`{"offset":"..."}`），
     * 塞进 `@Query` 需要额外包一层编码；这里直接用 HttpUrl 组query，既避免手工拼串，
     * 也复用拦截器里的 Cookie/UA/Referer（游客态可读，登录态配额更宽松）。
     *
     * 注意 oid 是 **aid**（数字），不是 bvid —— 详情页的 view 接口会带回 aid。
     */
    suspend fun getComments(
        aid: Long,
        sort: CommentSort = CommentSort.HOT,
        offset: String = "",
    ): Result<CommentPage> = withContext(io) {
        if (aid <= 0L) return@withContext Result.Error("BV 号无效，暂时读不到评论")
        try {
            val url = COMMENT_MAIN_URL.toHttpUrl().newBuilder()
                .addQueryParameter("oid", aid.toString())
                .addQueryParameter("type", "1")
                .addQueryParameter("mode", sort.mode.toString())
                .addQueryParameter("pagination_str", paginationJson(offset))
                .build()
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.Error("评论加载失败：HTTP ${response.code}")
                }
                val text = response.body.string()
                if (text.isBlank()) return@use Result.Error("评论加载失败：空响应")
                val dto = json.decodeFromString<ReplyMainDto>(text)
                if (dto.code != 0) {
                    noteAuth(dto.code)
                    Result.Error(dto.message.ifBlank { "评论加载失败：code ${dto.code}" })
                } else {
                    Result.Success(dto.toCommentPage())
                }
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "评论加载失败", e)
        }
    }

    /** 组装 `pagination_str`：offset 是 base64 风格游标，转义引号/反斜杠后塞进 JSON 字符串 */
    private fun paginationJson(offset: String): String {
        val escaped = offset.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"offset":"$escaped"}"""
    }

    /**
     * 楼中楼全量（抄 PiliPlus replyReplyList 的 REST 分支）。
     * [root] 是楼主 rpid；[pn] 从 1 开始，首屏跟详情接口一次把 root + 第一页一起带回。
     * root 被删时接口 data.root 为空，转成「评论可能已被删除」错误
     * （对 PiliPlus jumpToDialogue 失败的 toast）。
     */
    suspend fun getCommentReplies(
        aid: Long,
        root: Long,
        pn: Int = 1,
        ps: Int = 20,
    ): Result<CommentDetailPage> = withContext(io) {
        if (aid <= 0L || root <= 0L) return@withContext Result.Error("评论不存在或已被删除")
        try {
            val url = COMMENT_REPLY_URL.toHttpUrl().newBuilder()
                .addQueryParameter("oid", aid.toString())
                .addQueryParameter("type", "1")
                .addQueryParameter("root", root.toString())
                .addQueryParameter("pn", pn.coerceAtLeast(1).toString())
                .addQueryParameter("ps", ps.coerceIn(1, 20).toString())
                .addQueryParameter("sort", "1")
                .build()
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.Error("评论加载失败：HTTP ${response.code}")
                }
                val text = response.body.string()
                if (text.isBlank()) return@use Result.Error("评论加载失败：空响应")
                val dto = json.decodeFromString<ReplyDetailDto>(text)
                if (dto.code != 0) {
                    noteAuth(dto.code)
                    Result.Error(dto.message.ifBlank { "评论加载失败：code ${dto.code}" })
                } else {
                    dto.toCommentDetailPage()?.let { Result.Success(it) }
                        ?: Result.Error("评论可能已被删除")
                }
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "评论加载失败", e)
        }
    }

    /**
     * 取音频直链（主链）：qn 下发偏好档位做服务端鉴权，返回后再按偏好 rank 二次精选（见 selectAudio）。
     * 默认偏好 132K（免登录可得最佳档）；服务端只给低档时诚实回落，不断播。
     * 备链语义见 [getPlayUrls]；这里只给主链，保持老调用方行为不变。
     */
    suspend fun getPlayUrl(
        bvid: String,
        cid: Long,
        preferQuality: Int = AudioQuality.DEFAULT_CODE,
        /** true = 不带登录 Cookie 走游客身份（只在高档被风控时兜底，音质可能降级） */
        guest: Boolean = false,
        /** 实际选中的音频轨回调：调用方用它的 bandwidth 给播放信息页做码率兜底 */
        onAudioSelected: ((DashAudio) -> Unit)? = null,
    ): Result<String> =
        when (val r = getPlayUrls(bvid, cid, preferQuality, guest, onAudioSelected)) {
            is Result.Success -> Result.Success(r.data.urls.first())
            is Result.Error -> r
        }

    /**
     * 取音频候选直链：首元素恒为主链，其后是 playurl 备链（PiliPlus 选线路同款数据源）。
     * 主链的 CDN 节点挂掉（建连超时/403）时调用方按序切备链，不用重新鉴权。
     */
    suspend fun getPlayUrls(
        bvid: String,
        cid: Long,
        preferQuality: Int = AudioQuality.DEFAULT_CODE,
        guest: Boolean = false,
        onAudioSelected: ((DashAudio) -> Unit)? = null,
    ): Result<PlayCandidates> = try {
        val dto = api.playUrl(bvid, cid, qn = preferQuality, guest = guest)
        if (dto.code != 0) {
            noteAuth(dto.code)
            Result.Error(dto.message.ifBlank { "playurl failed: code ${dto.code}" })
        } else {
            val selected = dto.selectAudio(preferQuality)
            selected?.let { onAudioSelected?.invoke(it) }
            val urls = selected?.allUrls().orEmpty()
                .ifEmpty {
                    listOfNotNull(
                        dto.data?.durl?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                    )
                }
            if (urls.isEmpty()) {
                Result.Error("no play url")
            } else {
                Result.Success(
                    PlayCandidates(
                        urls = urls,
                        bandwidthBps = selected?.bandwidth ?: 0,
                        audioCode = selected?.id ?: 0,
                    )
                )
            }
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "playurl failed", e)
    }

    /**
     * 音质面板用：本次服务端实际返回的可用音频轨（id/带宽），展示可用性与当前有效档。
     * qn 必须与播放请求（[getPlayUrls]）同口径传偏好 code，否则服务端按默认档鉴权，
     * 面板会漏掉高档（偏好 Hi-Res 时面板只显示 192K，与实际播放不符）。
     */
    suspend fun getAudioOptions(
        bvid: String,
        cid: Long,
        preferQuality: Int = AudioQuality.DEFAULT_CODE,
    ): Result<List<DashAudio>> = try {
        val dto = api.playUrl(bvid, cid, qn = preferQuality)
        if (dto.code != 0) {
            noteAuth(dto.code)
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
            noteAuth(dto.code)
            Result.Error(dto.message.ifBlank { "subtitle failed: code ${dto.code}" })
        } else {
            Result.Success(dto.toTracks())
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "subtitle failed", e)
    }

    // ── 我的页:B站账号体系(只读,需登录 Cookie) ──

    suspend fun getAllFavFolders(upMid: Long): Result<List<BiliFavFolder>> = try {
        val dto = api.favFoldersAll(upMid)
        if (dto.code != 0) {
            noteAuth(dto.code)
            Result.Error(dto.message.ifBlank { "fav folders failed: code ${dto.code}" })
        } else Result.Success(dto.toAllFolders())
    } catch (e: Exception) {
        Result.Error(e.message ?: "fav folders failed", e)
    }

    suspend fun getFavMedias(mediaId: Long, pn: Int = 1, ps: Int = 20): Result<Pair<List<BiliSpaceVideo>, Boolean>> = try {
        val dto = api.favMedias(mediaId, pn, ps)
        if (dto.code != 0) {
            noteAuth(dto.code)
            Result.Error(dto.message.ifBlank { "fav medias failed: code ${dto.code}" })
        } else Result.Success(dto.toSpaceVideos())
    } catch (e: Exception) {
        Result.Error(e.message ?: "fav medias failed", e)
    }

    suspend fun getToview(pn: Int = 1, ps: Int = 20): Result<Pair<List<BiliSpaceVideo>, Int>> = try {
        val dto = api.toview(pn, ps)
        if (dto.code != 0) {
            noteAuth(dto.code)
            Result.Error(dto.message.ifBlank { "toview failed: code ${dto.code}" })
        } else Result.Success(dto.toSpaceVideos() to (dto.data?.count ?: 0))
    } catch (e: Exception) {
        Result.Error(e.message ?: "toview failed", e)
    }

    /**
     * B 站历史（游标分页）：首屏 max=0/viewAt=0，上拉用上一页最后一条的 oid/viewAt。
     * 返回 DTO 而不是映射后的列表——调用方需要游标才能翻页；feature 层经此统一出口，不直连 BiliApi（REVIEW_REPORT D-01）。
     */
    suspend fun getBiliHistory(
        max: Long = 0L,
        viewAt: Long = 0L,
        ps: Int = 20,
    ): Result<cn.debubu.tingbili.data.bilibili.dto.BiliHistoryDto> = try {
        val dto = api.historyCursor(ps = ps, max = max, viewAt = viewAt)
        if (dto.code != 0) {
            noteAuth(dto.code)
            Result.Error(dto.message.ifBlank { "history failed: code ${dto.code}" })
        } else Result.Success(dto)
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
    suspend fun loadSubtitleLinesResult(url: String): Result<List<LyricLine>> = withContext(io) {
        try {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use Result.Error("字幕下载失败：${response.code}")
                val text = response.body.string()
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

/**
 * 一次 playurl 解析出的全部候选直链：[urls] 首元素恒为主链，其后是备链（原序）。
 * [bandwidthBps]/[audioCode] 是实际选中档（码率兜底 + 底部标签显示实际生效档）。
 */
data class PlayCandidates(
    val urls: List<String>,
    val bandwidthBps: Int = 0,
    val audioCode: Int = 0,
)
