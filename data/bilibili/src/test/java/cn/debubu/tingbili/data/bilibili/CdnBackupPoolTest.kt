package cn.debubu.tingbili.data.bilibili

import cn.debubu.tingbili.core.media.ResolvedAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CdnBackupPool：备链按序消费、过期回落网络、换档隔离（键带偏好）。 */
class CdnBackupPoolTest {

    private var now = 1_000_000L
    private val pool = CdnBackupPool { now }

    private fun audio(url: String) = ResolvedAudio(url, 132000, 30232)

    @Test
    fun `poll returns staged backups in order then null`() {
        pool.stage("k", listOf(audio("b1"), audio("b2")))
        assertEquals("b1", pool.poll("k")?.url)
        assertEquals("b2", pool.poll("k")?.url)
        assertNull(pool.poll("k"))
    }

    @Test
    fun `poll on unknown key returns null`() {
        assertNull(pool.poll("nope"))
    }

    @Test
    fun `stage ignores empty list`() {
        pool.stage("k", emptyList())
        assertNull(pool.poll("k"))
    }

    @Test
    fun `expired entries fall back to null`() {
        pool.stage("k", listOf(audio("b1")))
        now += CdnBackupPool.TTL_MS
        assertNull(pool.poll("k"))
        // 过期清理后同 key 可重新暂存
        pool.stage("k", listOf(audio("b2")))
        assertEquals("b2", pool.poll("k")?.url)
    }

    @Test
    fun `re-stage overwrites previous backups`() {
        pool.stage("k", listOf(audio("old")))
        pool.stage("k", listOf(audio("new")))
        assertEquals("new", pool.poll("k")?.url)
        assertNull(pool.poll("k"))
    }
}
