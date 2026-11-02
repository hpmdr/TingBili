package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.model.episodeLabel
import cn.debubu.tingbili.core.data.model.queueLabel

/**
 * 播放页顶栏标题：优先 BV 合集名（[Track.videoTitle]），退化到分 P 名。
 *
 * 分 P 名常是 UP 主随手起的（如 “M　1-30”），只有合集名能回答“在听哪本书/哪个合集”；
 * 搜索进来的单集没有分 P 名差异，退化结果即视频标题。
 */
internal fun playerHeaderTitle(track: Track?): String =
    track?.videoTitle?.takeIf { it.isNotBlank() }
        ?: track?.title?.takeIf { it.isNotBlank() }
        ?: "未播放"

/**
 * 封面下方章节行：`03 · 章节名`（队列序号 + 分 P 名），与通知栏/迷你条统一口径。
 *
 * 混播收藏夹里各条分属不同 BV，`P{n}` 各说各话；队列序号才是统一口径。
 * `queueIndex` 为 0-based 的当前下标；无效（<0）时退化到分 P 标题。
 */
internal fun playerChapterLabel(track: Track?, queueIndex: Int = -1): String {
    if (track == null || track.title.isBlank()) return "未播放"
    if (queueIndex >= 0) return track.queueLabel(queueIndex)
    return track.episodeLabel()
}
