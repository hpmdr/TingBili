package cn.debubu.tingbili.core.media

import android.net.Uri
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track

/**
 * 播放器对外的数据源契约：core:media 只认接口，不认 B 站。
 *
 * - 换音源（本地/播客）只需换实现，PlayerManager/ServicePlayerModule 零改动；
 * - data:bilibili 用 BiliMediaResolvers 一次实现三个（见 data:bilibili/di/ResolverModule）。
 */
/** resolve 的结果：直链 + 实际选中档（标称码率 bps / 音质 code，未知为 0）。 */
data class ResolvedAudio(
    val url: String,
    val bandwidthBps: Int = 0,
    val audioCode: Int = 0,
    /** 主链之外的备链（首元素恒为主链 [url]，此处只放备链；失败重试按序消费） */
    val backupUrls: List<String> = emptyList(),
)

interface PlayUrlResolver {
    /**
     * 取可播直链；解析失败/风控返回 null（调用方跳过该集，不断播）。
     * 断网类失败直接抛 IOException（cause 链保留，认 isConnectivityError），
     * 调用方不得吞成 null：要给过期缓存兜底 + 错误分类留依据。
     *
     * [ResolvedAudio.bandwidthBps] 会随直链一起进磁盘缓存：Hi-Res 的 FLAC 容器头
     * 不写码率，播放信息页的「码率」靠它兜底（见 [AudioBitrateRegistry]）。
     */
    suspend fun resolve(bvid: String, cid: Long): ResolvedAudio?
}

interface CatalogResolver {
    /** 取 BV 的分P 列表；失败返回空（调用方按原 Track 照播）。 */
    suspend fun tracksOf(bvid: String): List<Track>
}

interface ArtworkResolver {
    /**
     * 通知栏/锁屏/车机封面。封面尺寸由实现方按"统一小图档"决定（与列表共用同一 URL，
     * 不额外引入第三种尺寸）；空封面返回 null。
     */
    fun artwork(cover: String, format: ImageFormat): Uri?
}
