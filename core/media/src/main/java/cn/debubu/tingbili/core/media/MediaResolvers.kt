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
interface PlayUrlResolver {
    /** 取可播直链；解析失败/风控返回 null（调用方跳过该集，不断播）。 */
    suspend fun resolve(bvid: String, cid: Long): String?
}

interface CatalogResolver {
    /** 取 BV 的分P 列表；失败返回空（调用方按原 Track 照播）。 */
    suspend fun tracksOf(bvid: String): List<Track>
}

interface ArtworkResolver {
    /** 封面 CDN 裁剪（B 站 @WxH 规格）；空封面返回 null。 */
    fun artwork(cover: String, format: ImageFormat, width: Int = 480, height: Int = 480): Uri?
}
