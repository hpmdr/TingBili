package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.PlayQueueJson
import cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayQueueDaoTest {

    private fun db(): TingBiliDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun track(bvid: String, cid: Long) =
        Track(bvid, cid, "P", "up", "", durationMs = 1000L, subtitleUrl = null)

    @Test
    fun `save and get round trip`() = runTest {
        val dao = db().playQueueDao()
        assertNull(dao.get())
        val tracks = listOf(
            track("BV1", 1),
            track("BV1", 2).copy(firstFrame = "https://first", pageCount = 2),
        )
        dao.save(
            PlayQueueSnapshotEntity(
                tracksJson = PlayQueueJson.encode(tracks),
                currentIndex = 1,
                sourceTitle = "书",
            )
        )
        val got = dao.get()!!
        assertEquals(1, got.currentIndex)
        assertEquals("书", got.sourceTitle)
        val decoded = PlayQueueJson.decode(got.tracksJson)
        assertEquals(2, decoded.size)
        assertEquals("BV1", decoded.first().bvid)
        assertEquals(2L, decoded[1].cid)
        assertEquals("https://first", decoded[1].firstFrame)
        assertEquals(2, decoded[1].pageCount)
    }

    @Test
    fun `save overwrites single row`() = runTest {
        val dao = db().playQueueDao()
        dao.save(PlayQueueSnapshotEntity(tracksJson = PlayQueueJson.encode(listOf(track("A", 1))), currentIndex = 0))
        dao.save(PlayQueueSnapshotEntity(tracksJson = PlayQueueJson.encode(listOf(track("B", 2))), currentIndex = 0))
        assertEquals("B", PlayQueueJson.decode(dao.get()!!.tracksJson).single().bvid)
    }

    @Test
    fun `decode corrupt json yields empty`() {
        assertTrue(PlayQueueJson.decode("not-json{{{").isEmpty())
    }

    @Test
    fun `shuffle order round trips with snapshot`() = runTest {
        val dao = db().playQueueDao()
        dao.save(
            PlayQueueSnapshotEntity(
                tracksJson = PlayQueueJson.encode(listOf(track("BV1", 1), track("BV1", 2))),
                currentIndex = 0,
                shuffleEnabled = true,
                shuffleOrderJson = PlayQueueJson.encodeOrder(listOf(0, 1)),
            )
        )
        val got = dao.get()!!
        assertEquals(listOf(0, 1), PlayQueueJson.decodeOrder(got.shuffleOrderJson))
        // 损坏的顺序串也如实存取，语义校验在 QueueManager 层
        assertTrue(PlayQueueJson.decodeOrder("not-json{{{").isEmpty())
    }
}
