package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** QueueRefreshPlanner 纯函数单测：无 Android 依赖，裸 JVM 可跑。 */
class QueueRefreshPlannerTest {

    private fun track(bvid: String, cid: Long, title: String = "t") =
        Track(bvid, cid, title, "a", "", 1000, null)

    @Test
    fun `append adds one with zero removals`() {
        val old = listOf(track("BV1", 1, "P1"), track("BV1", 2, "P2"))
        val plan = QueueRefreshPlanner.compute(old, 0, old + track("BV1", 3, "P3"))
        assertEquals(3, plan.final.size)
        assertEquals(0, plan.playingFinalIdx)
        assertEquals(0, plan.removed)
        assertEquals(0, plan.updated)
        assertEquals(1, plan.addedTracks.size)
        assertEquals(3L, plan.addedTracks.first().cid)
        assertEquals(1, plan.addedIfNoTransport)
        assertEquals(0, plan.deletions.size)
        assertEquals(0, plan.playingTransportIdx)
    }

    @Test
    fun `remove non-playing and rename counts updated`() {
        val old = listOf(track("BV1", 1, "P1"), track("BV1", 2, "P2"), track("BV1", 3, "P3"))
        val new = listOf(track("BV1", 1, "P1-new"), track("BV1", 2, "P2"), track("BV1", 4, "P4"))
        val plan = QueueRefreshPlanner.compute(old, 1, new)
        assertEquals(1, plan.removed) // P3 没了
        assertEquals(1, plan.updated) // P1 改名
        assertEquals(1, plan.addedTracks.size)
        assertEquals(4L, plan.addedTracks.first().cid)
        assertEquals(1, plan.playingFinalIdx) // 还在 P2 上
        assertEquals(1, plan.playingTransportIdx) // 删的是下标 2，不影响在播下标
        assertEquals(listOf(2), plan.deletions)
    }

    @Test
    fun `deleted playing episode is kept in place`() {
        val old = listOf(track("BV1", 1, "P1"), track("BV1", 2, "P2"))
        val plan = QueueRefreshPlanner.compute(old, 0, listOf(old[1]))
        assertEquals(0, plan.removed) // 在播被删不计 removed
        assertEquals(2, plan.final.size)
        assertEquals(1L, plan.final[0].cid) // 原位保留
        assertEquals(0, plan.playingFinalIdx)
    }

    @Test
    fun `deletion before playing shifts transport index`() {
        val old = listOf(track("BV1", 1, "P1"), track("BV1", 2, "P2"), track("BV1", 3, "P3"))
        val plan = QueueRefreshPlanner.compute(old, 2, listOf(track("BV1", 2, "P2"), track("BV1", 3, "P3")))
        assertEquals(1, plan.removed)
        assertEquals(1, plan.playingFinalIdx)
        assertEquals(1, plan.playingTransportIdx) // 删了下标 0，在播 transport 下标同步挪
        assertEquals(listOf(0), plan.deletions)
    }

    @Test
    fun `empty new list keeps only playing`() {
        val old = listOf(track("BV1", 1, "P1"), track("BV1", 2, "P2"))
        val plan = QueueRefreshPlanner.compute(old, 1, emptyList())
        assertEquals(1, plan.final.size)
        assertEquals(2L, plan.final.first().cid)
        assertEquals(1, plan.removed)
    }
}
