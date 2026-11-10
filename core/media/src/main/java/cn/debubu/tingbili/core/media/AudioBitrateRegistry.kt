package cn.debubu.tingbili.core.media

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实际选中音频档的登记（解析直链时顺手记录）：播放信息页的「码率」兜底 + 播放页底部
 * 音质短标签显示「实际生效档」都靠它。
 *
 * 为什么需要：
 * - Hi-Res 的 FLAC-in-MP4 容器头不写码率，`Format.bitrate` 是 LENGTH_UNSET；
 * - 底部标签若直接读偏好，用户设了 Hi-Res 但视频只有 192K 时会显示错误的档位。
 *
 * 进程内小表，只做诊断展示；键与媒体缓存一致用 `bvid:cid`。
 *
 * 两个概念：
 * - **实际生效档**（[put]/[codeOf]）：真正解析出直链后登记，代表「当前在播的」；
 * - **目标档**（[pendingQuality]）：用户在播放页点了切换但还没重新加载时暂存，
 *   标签优先显示它（暂停态切换要立即反馈），等真正加载后清零。
 *
 * [version] 是变更计数：任一写入后标签必须立即刷新，但 map 本身不是响应式的，
 * 故用版本号驱动下游 Flow 重组（见 PlayerViewModel.audioQualityLabel）。
 */
object AudioBitrateRegistry {
    data class Entry(val bandwidthKbps: Int, val audioCode: Int)

    /** 队列 + 历史量级很小，超过这个数直接清空重建，防止长期运行无限增长。 */
    private const val MAX_ENTRIES = 256

    private val map = ConcurrentHashMap<String, Entry>()

    /** 目标档（bvid:cid → 用户选择但尚未加载的档位）；解析生效后清零。 */
    private val pending = ConcurrentHashMap<String, Int>()

    private val _version = MutableStateFlow(0)
    /** 每次写入递增；下游据此刷新标签（map 非响应式，不能直接收集） */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** [bandwidthBps] <= 0 时只记档位不记码率（如 durl 兜底轨）。 */
    fun put(bvid: String, cid: Long, bandwidthBps: Int, audioCode: Int) {
        if (map.size > MAX_ENTRIES) map.clear()
        map[key(bvid, cid)] = Entry(
            bandwidthKbps = bandwidthBps.takeIf { it > 0 }?.div(1000) ?: 0,
            audioCode = audioCode,
        )
        pending.remove(key(bvid, cid)) // 已真正加载，目标档完成使命
        _version.value += 1
    }

    /** 记下用户选择但尚未加载的目标档（暂停态切换时标签要立即反馈）。 */
    fun setPending(bvid: String, cid: Long, audioCode: Int) {
        pending[key(bvid, cid)] = audioCode
        _version.value += 1
    }

    /**
     * 标签用的生效档：目标档优先（用户刚切完、还没加载），否则实际生效档。
     * 两者都空返回 null，由调用方回落偏好。
     */
    fun displayCodeOf(bvid: String, cid: Long): Int? =
        pending[key(bvid, cid)] ?: codeOf(bvid, cid)

    fun entryOf(bvid: String, cid: Long): Entry? = map[key(bvid, cid)]

    fun kbpsOf(bvid: String, cid: Long): Int? = entryOf(bvid, cid)?.bandwidthKbps?.takeIf { it > 0 }

    fun codeOf(bvid: String, cid: Long): Int? = entryOf(bvid, cid)?.audioCode?.takeIf { it > 0 }

    /** 单测用：清空登记表 */
    fun clear() { map.clear(); pending.clear() }

    private fun key(bvid: String, cid: Long) = "$bvid:$cid"
}
