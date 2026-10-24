package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.PlayQueueSnapshotEntity
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QueueManager 单测：队列真相源（恢复/快照/通知），transport 无关。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueManagerTest {

    class FakePlayQueueDao : cn.debubu.tingbili.core.data.db.PlayQueueDao {
        var snapshot: PlayQueueSnapshotEntity? = null
        override suspend fun get(): PlayQueueSnapshotEntity? = snapshot
        override suspend fun save(s: PlayQueueSnapshotEntity) { snapshot = s }
        override suspend fun clear() { snapshot = null }
    }

    private lateinit var dao: FakePlayQueueDao
    private lateinit var prefs: PreferencesRepository
    private lateinit var manager: QueueManager
    private val notified = mutableListOf<Triple<List<Track>, Int, String?>>()
    private val listener = object : QueueManager.Listener {
        override fun onQueueChanged(queue: List<Track>, index: Int, sourceTitle: String?) {
            notified += Triple(queue, index, sourceTitle)
        }
    }

    @Before
    fun setUp() {
        dao = FakePlayQueueDao()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_queue_prefs_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file },
        )
        prefs = PreferencesRepository(ds)
        manager = QueueManager(dao, prefs)
    }

    private fun track(bvid: String, cid: Long, duration: Long = 10000) =
        Track(bvid, cid, "t", "a", "", duration, null)

    @Test
    fun `restore empty returns null and keeps empty state`() = runTest {
        manager.attach(this, listener)
        assertNull(manager.restore())
        assertTrue(manager.queue.isEmpty())
        assertEquals(-1, manager.currentIndex)
        manager.restore() // 幂等：不再打扰
    }

    @Test
    fun `restore snapshot applies queue index and clamped position`() = runTest {
        manager.attach(this, listener)
        val tracks = listOf(track("BV1", 1), track("BV2", 2, 20000))
        dao.snapshot = PlayQueueSnapshotEntity(
            tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(tracks),
            currentIndex = 1,
        )
        prefs.setLastPosition(5500L)
        val r = manager.restore()
        runCurrent()
        assertEquals(2, r?.queue?.size)
        assertEquals(1, r?.index)
        assertEquals(5500L, r?.positionMs)
        assertEquals(1, manager.currentIndex)
        assertEquals("BV2", manager.queue[manager.currentIndex].bvid)
    }

    @Test
    fun `restore clamps position beyond duration`() = runTest {
        manager.attach(this, listener)
        dao.snapshot = PlayQueueSnapshotEntity(
            tracksJson = cn.debubu.tingbili.core.data.db.PlayQueueJson.encode(listOf(track("BV1", 1, 1000))),
            currentIndex = 0,
        )
        prefs.setLastPosition(99999L)
        val r = manager.restore()
        assertEquals(1000L, r?.positionMs)
    }

    @Test
    fun `setActiveQueue persists snapshot and notifies`() = runTest {
        manager.attach(this, listener)
        manager.setActiveQueue(listOf(track("BV1", 1), track("BV2", 2)), 1, "书")
        runCurrent()
        assertEquals(1, manager.currentIndex)
        assertEquals("书", manager.sourceTitle)
        assertEquals(1, notified.last().second)
        val decoded = cn.debubu.tingbili.core.data.db.PlayQueueJson.decode(dao.snapshot!!.tracksJson)
        assertEquals(2, decoded.size)
        assertEquals(1, dao.snapshot!!.currentIndex)
        assertEquals("书", dao.snapshot!!.sourceTitle)
    }

    @Test
    fun `adoptTracks holds without valid index`() = runTest {
        manager.attach(this, listener)
        manager.adoptTracks(listOf(track("BV1", 1)), "搜")
        assertEquals(-1, manager.currentIndex)
        assertEquals(1, manager.queue.size)
        assertEquals("搜", notified.last().third)
        assertNull(dao.snapshot) // 无有效下标不落快照
    }

    @Test
    fun `moveCurrentTo ignores out-of-range`() = runTest {
        manager.attach(this, listener)
        manager.setActiveQueue(listOf(track("BV1", 1)), 0, null)
        runCurrent()
        notified.clear()
        manager.moveCurrentTo(5)
        assertEquals(0, manager.currentIndex)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `moveCurrentTo syncs and persists`() = runTest {
        manager.attach(this, listener)
        manager.setActiveQueue(listOf(track("BV1", 1), track("BV1", 2)), 0, null)
        runCurrent()
        manager.moveCurrentTo(1)
        runCurrent()
        assertEquals(1, manager.currentIndex)
        assertEquals(1, notified.last().second)
        assertEquals(1, dao.snapshot!!.currentIndex)
    }
}
