package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.PlayUrlCacheEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayUrlCacheDaoTest {

    private fun db(): TingBiliDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `putAll upserts by key`() = runTest {
        val dao = db().playUrlCacheDao()
        dao.putAll(listOf(PlayUrlCacheEntity("BV1:1", "http://a", 1000L)))
        dao.putAll(listOf(PlayUrlCacheEntity("BV1:1", "http://b", 2000L)))
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("http://b", all.single().url)
    }

    @Test
    fun `pruneExpired drops stale rows`() = runTest {
        val dao = db().playUrlCacheDao()
        dao.putAll(
            listOf(
                PlayUrlCacheEntity("BV1:1", "http://a", 1000L),
                PlayUrlCacheEntity("BV1:2", "http://b", 9000L),
            )
        )
        dao.pruneExpired(5000L)
        assertEquals(listOf("BV1:2"), dao.getAll().map { it.key })
    }

    @Test
    fun `pruneOverflow keeps newest`() = runTest {
        val dao = db().playUrlCacheDao()
        dao.putAll((1..5).map { PlayUrlCacheEntity("BV1:$it", "http://x", it * 1000L) })
        dao.pruneOverflow(2)
        assertEquals(listOf("BV1:5", "BV1:4"), dao.getAll().map { it.key }.sortedDescending())
    }

    @Test
    fun `isFresh honors ttl`() {
        val fresh = PlayUrlCacheEntity("k", "http://a", 1000L)
        assertTrue(fresh.isFresh(1000L + PlayUrlCacheEntity.TTL_MS - 1))
        assertTrue(!fresh.isFresh(1000L + PlayUrlCacheEntity.TTL_MS + 1))
        assertTrue(!PlayUrlCacheEntity("k", "", 1000L).isFresh(1001L))
    }
}
