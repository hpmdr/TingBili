package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** PlayUrlPipeline 单测：解析/缓存/组装/错误判定（MediaItem 需 Robolectric）。 */
@RunWith(RobolectricTestRunner::class)
class PlayUrlPipelineTest {

    class FakeCache : cn.debubu.tingbili.core.data.db.PlayUrlCacheDao {
        val map = mutableMapOf<String, PlayUrlCacheEntity>()
        var pruneExpiredCalls = 0
        var pruneOverflowCalls = 0
        override suspend fun getAll(): List<PlayUrlCacheEntity> = map.values.toList()
        override suspend fun putAll(rows: List<PlayUrlCacheEntity>) { rows.forEach { map[it.key] = it } }
        override suspend fun pruneExpired(cutoffMs: Long) {
            pruneExpiredCalls++
            map.entries.removeIf { it.value.fetchedAtMs < cutoffMs }
        }
        override suspend fun pruneOverflow(keep: Int) {
            pruneOverflowCalls++
            map.values.sortedByDescending { it.fetchedAtMs }.drop(keep).map { it.key }.forEach { map.remove(it) }
        }
    }

    class FakeResolvers : PlayUrlResolver, CatalogResolver, ArtworkResolver {
        var nextUrl: String? = "http://example.com/a.mp3"
        var resolveCalls = 0
        var catalog: List<Track> = emptyList()
        override suspend fun resolve(bvid: String, cid: Long): String? {
            resolveCalls++
            return nextUrl
        }
        override suspend fun tracksOf(bvid: String): List<Track> = catalog
        override fun artwork(cover: String, format: ImageFormat, width: Int, height: Int): android.net.Uri? = null
    }

    private lateinit var cache: FakeCache
    private lateinit var resolvers: FakeResolvers
    private lateinit var pipeline: PlayUrlPipeline

    @Before
    fun setUp() {
        cache = FakeCache()
        resolvers = FakeResolvers()
        pipeline = PlayUrlPipeline(resolvers, resolvers, resolvers, cache)
    }

    private fun track(cid: Long = 1L) = Track("BV1", cid, "t", "a", "", 1000, null)

    @Test
    fun `cache hit avoids network`() = runTest {
        cache.map["BV1:1"] = PlayUrlCacheEntity("BV1:1", "http://cached/x.mp3", System.currentTimeMillis())
        val out = pipeline.resolveUrls(listOf(track()))
        assertEquals("http://cached/x.mp3", out[track()])
        assertEquals(0, resolvers.resolveCalls)
    }

    @Test
    fun `miss resolves and writes back`() = runTest {
        val out = pipeline.resolveUrls(listOf(track(), track(2)))
        assertEquals(2, out.size)
        assertEquals(2, resolvers.resolveCalls)
        assertEquals(2, cache.map.size)
        assertEquals(1, cache.pruneExpiredCalls)
        assertEquals(1, cache.pruneOverflowCalls)
    }

    @Test
    fun `blank url is skipped`() = runTest {
        resolvers.nextUrl = "  "
        val out = pipeline.resolveUrls(listOf(track()))
        assertTrue(out.isEmpty())
        assertTrue(cache.map.isEmpty())
    }

    @Test
    fun `resolveCid fills from catalog`() = runTest {
        resolvers.catalog = listOf(track(7).copy(durationMs = 5000))
        val r = pipeline.resolveCid(Track("BV1", 0, "t", "a", "", 0, null))
        assertEquals(7L, r.cid)
        assertEquals(5000L, r.durationMs)
    }

    @Test
    fun `resolveCid keeps track when catalog empty`() = runTest {
        val t = track(0)
        assertEquals(t, pipeline.resolveCid(t))
    }

    @Test
    fun `isRecoverable matches expired and network errors`() {
        val http = androidx.media3.common.PlaybackException(
            "403", null,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
        assertTrue(pipeline.isRecoverable(http))
        val expired = androidx.media3.common.PlaybackException(
            "url expire", null,
            androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT,
        )
        assertTrue(pipeline.isRecoverable(expired))
        val fatal = androidx.media3.common.PlaybackException(
            "boom", null,
            androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
        assertFalse(pipeline.isRecoverable(fatal))
    }

    @Test
    fun `refreshUrl writes cache`() = runTest {
        val url = pipeline.refreshUrl(track())
        assertEquals("http://example.com/a.mp3", url)
        assertEquals(url, cache.map["BV1:1"]?.url)
    }

    @Test
    fun `refreshUrl null keeps old`() = runTest {
        resolvers.nextUrl = null
        assertNull(pipeline.refreshUrl(track()))
        assertTrue(cache.map.isEmpty())
    }

    @Test
    fun `buildItem keys by bvid and cid`() {
        val item = pipeline.buildItem(track(), "http://example.com/a.mp3", ImageFormat.WEBP)
        assertEquals("BV1:1", item.mediaId)
        assertEquals("BV1:1", item.localConfiguration?.customCacheKey)
        assertEquals("http://example.com/a.mp3", item.localConfiguration?.uri.toString())
    }
}
