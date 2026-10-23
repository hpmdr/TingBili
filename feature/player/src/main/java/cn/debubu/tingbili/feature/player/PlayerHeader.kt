package cn.debubu.tingbili.feature.player

import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.ui.chapterLabel

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
 * 封面下方章节行：分 P 序号已知时加中文集数前缀（`第1集 · M 1-30`），
 * 让用户一眼看懂这是正在听的这一集；单集/序号未知时与 [chapterLabel] 一致。
 * 只看序号不看总数：整单播放时队列 track 的 pageCount 常是默认值，
 * 但“上次听到 P1”这种文案本就只认序号，这里保持一致。
 */
internal fun playerChapterLabel(track: Track?): String {
    val name = track?.title?.takeIf { it.isNotBlank() } ?: return "未播放"
    val pageIndex = track.pageIndex
    return if (pageIndex != null && pageIndex > 0) "第${pageIndex}集 · $name"
    else chapterLabel(name, pageIndex, track.pageCount)
}
