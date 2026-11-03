package cn.debubu.tingbili.core.media

import cn.debubu.tingbili.core.data.db.PlayUrlCacheDao
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.network.CONNECTIVITY_MARK
import cn.debubu.tingbili.core.data.network.isConnectivityError
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** AudioUrlResolver 的断网/业务分流：断网透传（带 cause）不断播，业务错走旧的 null→MARK 路。 */
class AudioUrlResolverTest {

    class FakeResolver(var fn: suspend (String, Long) -> String?) : PlayUrlResolver {
        var bandwidthBps: Int = 0
        var audioCode: Int = 0
        var calls = 0
        override suspend fun resolve(bvid: String, cid: Long): ResolvedAudio? {
            calls++
            return fn(bvid, cid)?.let { ResolvedAudio(it, bandwidthBps, audioCode) }
        }
    }

    class FakeCacheDao : PlayUrlCacheDao {
        val map = mutableMapOf<String, PlayUrlCacheEntity>()
        override suspend fun getAll(): List<PlayUrlCacheEntity> = map.values.toList()
        override suspend fun get(key: String): PlayUrlCacheEntity? = map[key]
        override suspend fun putAll(rows: List<PlayUrlCacheEntity>) {
            rows.forEach { map[it.key] = it }
        }
        override suspend fun pruneExpired(cutoffMs: Long) {
            map.entries.removeIf { it.value.fetchedAtMs < cutoffMs }
        }
        override suspend fun pruneOverflow(keep: Int) {
            map.values.sortedByDescending { it.fetchedAtMs }.drop(keep).map { it.key }
                .forEach { map.remove(it) }
        }
        override suspend fun delete(key: String) {
            map.remove(key)
        }
    }

    private val now = System.currentTimeMillis()
    private fun expired(url: String) =
        PlayUrlCacheEntity("BV1:1", url, now - PlayUrlCacheEntity.TTL_MS - 1_000L)
    private fun fresh(url: String, bandwidthBps: Int = 96_000) =
        PlayUrlCacheEntity("BV1:1", url, now, bandwidthBps)

    @Test
    fun `fresh cache short-circuits without network`() {
        val resolver = FakeResolver { _, _ -> "http://new" }
        val cache = FakeCacheDao().apply { map["BV1:1"] = fresh("http://fresh") }
        assertEquals("http://fresh", AudioUrlResolver(resolver, cache).resolveBlocking("BV1", 1L))
        assertEquals(0, resolver.calls)
    }

    @Test
    fun `business null with expired cache falls back to stale`() {
        val resolver = FakeResolver { _, _ -> null }
        val cache = FakeCacheDao().apply { map["BV1:1"] = expired("http://stale") }
        assertEquals("http://stale", AudioUrlResolver(resolver, cache).resolveBlocking("BV1", 1L))
    }

    @Test
    fun `business null without cache throws resolve mark not connectivity`() {
        val r = AudioUrlResolver(FakeResolver { _, _ -> null }, FakeCacheDao())
        try {
            r.resolveBlocking("BV1", 1L)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(AudioUrlResolver.RESOLVE_FAILED_MARK in (e.message ?: ""))
            assertFalse(isConnectivityError(e))
        }
    }

    @Test
    fun `connectivity throw with expired cache falls back to stale`() {
        val resolver = FakeResolver { _, _ -> throw IOException("dns down", UnknownHostException("x")) }
        val cache = FakeCacheDao().apply { map["BV1:1"] = expired("http://stale") }
        assertEquals("http://stale", AudioUrlResolver(resolver, cache).resolveBlocking("BV1", 1L))
    }

    @Test
    fun `connectivity throw without cache rethrows with cause`() {
        val cause = UnknownHostException("dns down")
        val r = AudioUrlResolver(FakeResolver { _, _ -> throw IOException("dns down", cause) }, FakeCacheDao())
        try {
            r.resolveBlocking("BV1", 1L)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(isConnectivityError(e))
            assertTrue(CONNECTIVITY_MARK in (e.message ?: ""))
            assertTrue(e.cause?.let { isConnectivityError(it) } == true)
        }
    }

    @Test
    fun `fresh network result is returned and cached`() {
        val resolver = FakeResolver { _, _ -> "http://new" }
        val cache = FakeCacheDao()
        assertEquals("http://new", AudioUrlResolver(resolver, cache).resolveBlocking("BV1", 1L))
        assertEquals("http://new", cache.map["BV1:1"]?.url)
    }

    /** FLAC 容器没写码率：标称值 + 档位 code 要随直链进缓存，缓存命中时也登记 */
    @Test
    fun `bandwidth is registered on both network and cache paths`() {
        val resolver = FakeResolver({ _, _ -> "http://new" }).apply {
            bandwidthBps = 1_200_000
            audioCode = 30251
        }
        val cache = FakeCacheDao()
        assertEquals("http://new", AudioUrlResolver(resolver, cache).resolveBlocking("BV1", 1L))
        assertEquals(1200, AudioBitrateRegistry.kbpsOf("BV1", 1))
        assertEquals(30251, AudioBitrateRegistry.codeOf("BV1", 1))
        assertEquals(1_200_000, cache.map["BV1:1"]?.bandwidthBps)
        assertEquals(30251, cache.map["BV1:1"]?.audioCode)

        // 缓存命中路径（不打开播放器时重复播放同一集）
        AudioBitrateRegistry.clear()
        val cachedResolver = FakeResolver { _, _ -> throw AssertionError("should not hit network") }
        assertEquals("http://new", AudioUrlResolver(cachedResolver, cache).resolveBlocking("BV1", 1L))
        assertEquals(1200, AudioBitrateRegistry.kbpsOf("BV1", 1))
        assertEquals(30251, AudioBitrateRegistry.codeOf("BV1", 1))
    }

    /** 升级前写入的旧行 bandwidth=0：重解析一次把码率补上，而不是永远「读取中」 */
    @Test
    fun `zero bandwidth rows re-resolve to backfill registry`() {
        val resolver = FakeResolver { _, _ -> "http://new" }
        val cache = FakeCacheDao().apply { map["BV1:1"] = fresh("http://old", bandwidthBps = 0) }
        assertEquals("http://new", AudioUrlResolver(resolver, cache).resolveBlocking("BV1", 1L))
        assertEquals(1, resolver.calls)
        assertEquals(0, cache.map["BV1:1"]?.bandwidthBps)

        resolver.bandwidthBps = 1_200_000
        assertEquals("http://new", AudioUrlResolver(resolver, cache).resolveBlocking("BV1", 1L))
        assertEquals(1200, AudioBitrateRegistry.kbpsOf("BV1", 1))
        assertEquals(1_200_000, cache.map["BV1:1"]?.bandwidthBps)

        // 补上之后不再重复打接口
        AudioBitrateRegistry.clear()
        val noNet = FakeResolver { _, _ -> throw AssertionError("should not hit network") }
        assertEquals("http://new", AudioUrlResolver(noNet, cache).resolveBlocking("BV1", 1L))
        assertEquals(1200, AudioBitrateRegistry.kbpsOf("BV1", 1))
    }
}
