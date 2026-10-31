package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.data.model.Track
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** PlayUrlPipeline + 惰性直链解析单测（MediaItem 需 Robolectric）。 */
@RunWith(RobolectricTestRunner::class)
class PlayUrlPipelineTest {

    class FakeCache : PlayUrlCacheDao {
        val map = mutableMapOf<String, PlayUrlCacheEntity>()
        var pruneExpiredCalls = 0
        override suspend fun getAll(): List<PlayUrlCacheEntity> = map.values.toList()
        override suspend fun get(key: String): PlayUrlCacheEntity? = map[key]
        override suspend fun putAll(rows: List<PlayUrlCacheEntity>) { rows.forEach { map[it.key] = it } }
        override suspend fun pruneExpired(cutoffMs: Long) {
            pruneExpiredCalls++
            map.entries.removeIf { it.value.fetchedAtMs < cutoffMs }
        }
        override suspend fun delete(key: String) {
            map.remove(key)
        }
        override suspend fun pruneOverflow(keep: Int) {
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
        override fun artwork(cover: String, format: ImageFormat): android.net.Uri? = null
    }

    private lateinit var cache: FakeCache
    private lateinit var resolvers: FakeResolvers
    private lateinit var pipeline: PlayUrlPipeline
    private lateinit var audioUrls: AudioUrlResolver

    @Before
    fun setUp() {
        cache = FakeCache()
        resolvers = FakeResolvers()
        pipeline = PlayUrlPipeline(resolvers, resolvers)
        audioUrls = AudioUrlResolver(resolvers, cache)
    }

    private fun track(cid: Long = 1L) = Track("BV1", cid, "t", "a", "", 1000, null)

    @Test
    fun `buildItem metadata title includes part number`() {
        val part = Track(
            bvid = "BV1",
            cid = 7,
            title = "295-308",
            author = "a",
            cover = "",
            durationMs = 1000,
            subtitleUrl = null,
            videoTitle = "无限流悬疑多人有声《请活到天明》",
            pageIndex = 17,
        )
        val item = pipeline.buildItem(part, ImageFormat.WEBP)
        assertEquals("P17 · 295-308", item.mediaMetadata.title)
    }

    @Test
    fun `buildItem carries lazy uri and stable cache key`() {
        val item = pipeline.buildItem(track(7), ImageFormat.WEBP)
        assertEquals("BV1:7", item.mediaId)
        assertEquals("BV1:7", item.localConfiguration?.customCacheKey)
        val uri = item.localConfiguration!!.uri
        assertTrue(PlayUrlPipeline.isLazyUri(uri))
        assertEquals("BV1", PlayUrlPipeline.bvidOf(uri))
        assertEquals(7L, PlayUrlPipeline.cidOf(uri))
        // 惰性 URI 不是可直接播放的 http 直链
        assertFalse(uri.toString().startsWith("http"))
    }

    @Test
    fun `fresh cache skips network`() {
        cache.map["BV1:1"] = PlayUrlCacheEntity("BV1:1", "http://cached/x.mp3", System.currentTimeMillis())
        assertEquals("http://cached/x.mp3", audioUrls.resolveBlocking("BV1", 1L))
        assertEquals(0, resolvers.resolveCalls)
    }

    @Test
    fun `miss resolves once and writes cache`() {
        assertEquals("http://example.com/a.mp3", audioUrls.resolveBlocking("BV1", 1L))
        assertEquals(1, resolvers.resolveCalls)
        assertEquals("http://example.com/a.mp3", cache.map["BV1:1"]?.url)
        assertEquals(1, cache.pruneExpiredCalls)
    }

    @Test
    fun `stale cache is used when resolve fails`() {
        resolvers.nextUrl = null
        cache.map["BV1:2"] = PlayUrlCacheEntity("BV1:2", "http://cached/old.mp3", 1L)
        assertEquals("http://cached/old.mp3", audioUrls.resolveBlocking("BV1", 2L))
    }

    @Test
    fun `resolve failure without cache throws retryable error`() {
        resolvers.nextUrl = null
        val e = assertThrows(IOException::class.java) { audioUrls.resolveBlocking("BV1", 3L) }
        assertTrue(e.message!!.contains(AudioUrlResolver.RESOLVE_FAILED_MARK))
        assertTrue(pipeline.isRecoverable(
            androidx.media3.common.PlaybackException(
                e.message, e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        ))
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
    fun `lazy uri round trips`() {
        val uri = AudioUrlResolver.lazyUri("BVabc", 42L)
        assertEquals("BVabc", PlayUrlPipeline.bvidOf(uri))
        assertEquals(42L, PlayUrlPipeline.cidOf(uri))
        assertNull(PlayUrlPipeline.bvidOf(android.net.Uri.parse("http://x/y")))
    }
}
