package cn.debubu.tingbili.core.media

import java.util.concurrent.ConcurrentHashMap

/**
 * 实际选中音频档的登记（解析直链时顺手记录）：播放信息页的「码率」兜底 + 播放页底部
 * 音质短标签显示「实际生效档」都靠它。
 *
 * 为什么需要：
 * - Hi-Res 的 FLAC-in-MP4 容器头不写码率，`Format.bitrate` 是 LENGTH_UNSET；
 * - 底部标签若直接读偏好，用户设了 Hi-Res 但视频只有 192K 时会显示错误的档位。
 *
 * 进程内小表，只做诊断展示；键与媒体缓存一致用 `bvid:cid`。
 */
object AudioBitrateRegistry {
    data class Entry(val bandwidthKbps: Int, val audioCode: Int)

    /** 队列 + 历史量级很小，超过这个数直接清空重建，防止长期运行无限增长。 */
    private const val MAX_ENTRIES = 256

    private val map = ConcurrentHashMap<String, Entry>()

    /** [bandwidthBps] <= 0 时只记档位不记码率（如 durl 兜底轨）。 */
    fun put(bvid: String, cid: Long, bandwidthBps: Int, audioCode: Int) {
        if (map.size > MAX_ENTRIES) map.clear()
        map[key(bvid, cid)] = Entry(
            bandwidthKbps = bandwidthBps.takeIf { it > 0 }?.div(1000) ?: 0,
            audioCode = audioCode,
        )
    }

    fun entryOf(bvid: String, cid: Long): Entry? = map[key(bvid, cid)]

    fun kbpsOf(bvid: String, cid: Long): Int? = entryOf(bvid, cid)?.bandwidthKbps?.takeIf { it > 0 }

    fun codeOf(bvid: String, cid: Long): Int? = entryOf(bvid, cid)?.audioCode?.takeIf { it > 0 }

    /** 单测用：清空登记表 */
    fun clear() { map.clear() }

    private fun key(bvid: String, cid: Long) = "$bvid:$cid"
}
