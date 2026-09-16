package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.core.data.model.Track

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
