package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.model.episodeLabel

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
 * 封面下方章节行：与迷你播放器/收藏列表/通知栏统一成 `P17 · 295-308`。
 *
 * 只显示 `P{n}` 的条件复用 core:data `episodeLabel`：多 P 且序号已知才带；
 * 单 P（BV 标题与分 P 同名）不显示 `P1`，没有序号时只显示标题。
 */
internal fun playerChapterLabel(track: Track?): String {
    if (track == null || track.title.isBlank()) return "未播放"
    return track.episodeLabel()
}
