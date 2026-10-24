package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.model.Track

/** 队列项去重键：B 站 BV + 分 P cid（与播放器 mediaId 口径一致）。 */
internal fun Track.queueKey(): Pair<String, Long> = bvid to cid

/**
 * 队列刷新计划：纯计算，不碰 transport/IO/协程，可裸跑 JVM 单测。
 *
 * 输入旧队列 + 在播下标 + B 站最新列表，算出最终队列/在播落点/增删改计数/
 * 待删旧下标/删后 transport 期望顺序。在播项被删则原位保留（拍板语义），播完再消失。
 */
internal data class RefreshPlan(
    val final: List<Track>,
    /** 在播项在 final 中的下标（恒 >= 0：在播被删也会被加回） */
    val playingFinalIdx: Int,
    val removed: Int,
    val updated: Int,
    val deletions: List<Int>,
    /** 删完后 transport 的期望顺序（immutable，splice 内不再边走边改语义） */
    val base: List<Track>,
    val addedTracks: List<Track>,
    /** transport 对不上时的 added 口径（新增 key 数，与旧 fallback 分支一致） */
    val addedIfNoTransport: Int,
    /** 在播项在删后 transport 中的下标（删到之前的项要同步挪） */
    val playingTransportIdx: Int,
)

/** 刷新计划的纯计算入口（PlayerManager.refreshQueue 的决策部分，执行部分见 splice）。 */
internal object QueueRefreshPlanner {
    fun compute(
        old: List<Track>,
        currentIndex: Int,
        newTracks: List<Track>,
    ): RefreshPlan {
        val newKeys = newTracks.map { it.queueKey() }.toSet()
        val oldKeys = old.map { it.queueKey() }.toSet()
        val playing = old[currentIndex]
        // 最终队列：新顺序；在播被删则在原下标处保留
        val final = newTracks.toMutableList()
        if (playing.queueKey() !in newKeys) final.add(currentIndex.coerceIn(0, final.size), playing)
        val removed = old.count { it.queueKey() !in newKeys && it.queueKey() != playing.queueKey() }
        val updated = final.count { f -> old.find { it.queueKey() == f.queueKey() }?.let { it != f } == true }
        // 待删旧下标（倒序，不含在播项）；删完后 transport 顺序 == base
        val deletions = old.indices.sortedDescending()
            .filter { i -> i != currentIndex && old[i].queueKey() !in newKeys }
        val base = old.filterIndexed { i, t -> i == currentIndex || t.queueKey() in newKeys }
        // 新增项（base 里没有的）：调用方批量解析 playurl
        val addedTracks = final.filter { f -> base.none { it.queueKey() == f.queueKey() } }
        val addedIfNoTransport = final.count { it.queueKey() !in oldKeys }
        val playingFinalIdx = final.indexOfFirst { it.queueKey() == playing.queueKey() }
        return RefreshPlan(
            final = final,
            playingFinalIdx = playingFinalIdx,
            removed = removed,
            updated = updated,
            deletions = deletions,
            base = base,
            addedTracks = addedTracks,
            addedIfNoTransport = addedIfNoTransport,
            playingTransportIdx = currentIndex - deletions.count { it < currentIndex },
        )
    }
}
