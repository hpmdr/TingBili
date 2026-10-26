package cn.debubu.tingbili.data.bilibili

import android.net.Uri
import cn.debubu.tingbili.core.data.Result
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.media.ArtworkResolver
import cn.debubu.tingbili.core.media.CatalogResolver
import cn.debubu.tingbili.core.media.PlayUrlResolver
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality
import cn.debubu.tingbili.data.bilibili.dto.BiliImageVariant
import cn.debubu.tingbili.data.bilibili.dto.biliImage
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * core:media 三个数据源接口的 B 站实现。
 * 风控语义收敛在这里：412/空直链一律转 null/空列表，播放器只管跳过。
 */
class BiliMediaResolvers @Inject constructor(
    private val repo: BiliRepository,
    private val prefs: PreferencesRepository,
) : PlayUrlResolver, CatalogResolver, ArtworkResolver {

    /** 解析直链按用户音质偏好选档（播放页底部音质入口/默认 132K），失败回落调用方跳过，不断播。 */
    override suspend fun resolve(bvid: String, cid: Long): String? {
        val prefer = try {
            prefs.audioQuality.first()
        } catch (_: Exception) {
            AudioQuality.DEFAULT_CODE
        }
        return when (val r = repo.getPlayUrl(bvid, cid, prefer)) {
            is Result.Success -> r.data.takeIf { it.isNotBlank() }
            is Result.Error -> null
        }
    }

    override suspend fun tracksOf(bvid: String): List<Track> =
        when (val v = repo.getView(bvid)) {
            is Result.Success -> v.data
            is Result.Error -> emptyList()
        }

    override fun artwork(cover: String, format: ImageFormat): Uri? =
        cover.takeIf { it.isNotBlank() }
            ?.biliImage(BiliImageVariant.ListThumb, format)
            ?.let(Uri::parse)
}
