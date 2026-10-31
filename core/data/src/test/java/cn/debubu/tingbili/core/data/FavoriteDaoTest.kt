package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.BvFavoritePageEntity
import cn.debubu.tingbili.core.data.db.DEFAULT_FOLDER_ID
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavoriteDaoTest {

    private fun db(): TingBiliDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun folder(
        id: Long = 0,
        name: String = "收藏夹",
        isDefault: Boolean = false,
        sortOrder: Int = 0,
    ) = FavoriteFolderEntity(
        id = id,
        name = name,
        isDefault = isDefault,
        sortOrder = sortOrder,
    )

    @Test
    fun `folder summaries include counts cover and last played`() = runTest {
        val db = db()
        val dao = db.favoriteDao()
        val folderId = dao.insertFolder(folder(name = "听书"))
        dao.insertTrack(
            FavoriteTrackEntity(
                folderId = folderId,
                bvid = "BV1",
                cid = 1,
                title = "P1",
                cover = "https://cover/1",
                durationMs = 1_000,
                pageIndex = 1,
                order = 0,
            )
        )
        dao.insertTrack(
            FavoriteTrackEntity(
                folderId = folderId,
                bvid = "BV1",
                cid = 2,
                title = "P2",
                cover = "https://cover/2",
                durationMs = 2_000,
                pageIndex = 2,
                order = 1,
            )
        )
        db.historyDao().save(
            HistoryEntity(
                bvid = "BV1",
                cid = 2,
                positionMs = 12_345,
                updatedAt = 100,
            )
        )

        val summary = dao.observeFolderSummaries().first().first { it.folder.id == folderId }
        assertEquals(2, summary.trackCount)
        assertEquals(3_000L, summary.totalDurationMs)
        assertEquals("https://cover/1", summary.firstCover)
        assertEquals(2, summary.lastPlayedPageIndex)
        assertEquals(12_345L, summary.lastPlayedPositionMs)
        db.close()
    }

    @Test
    fun `duplicate track is ignored and folder delete cascades`() = runTest {
        val db = db()
        val dao = db.favoriteDao()
        val folderId = dao.insertFolder(folder(name = "听书"))
        val row = FavoriteTrackEntity(
            folderId = folderId,
            bvid = "BV1",
            cid = 1,
            title = "P1",
            order = 0,
        )
        dao.insertTrack(row)
        dao.insertTrack(row)
        assertEquals(1, dao.getTracks(folderId).size)

        assertEquals(1, dao.deleteFolder(folderId))
        assertTrue(dao.getTracks(folderId).isEmpty())
        db.close()
    }

    @Test
    fun `default folder cannot be deleted and max sort ignores it`() = runTest {
        val db = db()
        val dao = db.favoriteDao()
        dao.insertFolder(folder(id = DEFAULT_FOLDER_ID, name = "我的最爱", isDefault = true))
        dao.insertFolder(folder(name = "夹1", sortOrder = 3))
        dao.insertFolder(folder(name = "夹2", sortOrder = 7))

        assertEquals(7, dao.maxFolderSortOrder())
        assertEquals(0, dao.deleteFolder(DEFAULT_FOLDER_ID))
        assertEquals("我的最爱", dao.getFolder(DEFAULT_FOLDER_ID)?.name)
        db.close()
    }

    @Test
    fun `membership queries work per folder and per track`() = runTest {
        val db = db()
        val dao = db.favoriteDao()
        val folderId = dao.insertFolder(folder(name = "夹1"))
        dao.insertTrack(
            FavoriteTrackEntity(
                folderId = folderId,
                bvid = "BV1",
                cid = 2,
                title = "P2",
                order = 0,
            )
        )

        assertTrue(dao.isTrackInFolder(folderId, "BV1", 2))
        assertFalse(dao.isTrackInFolder(folderId, "BV1", 3))
        assertEquals(listOf(folderId), dao.observeFolderIdsContaining("BV1", 2).first())
        assertTrue(dao.observeTrackInFolder(folderId, "BV1", 2).first())
        db.close()
    }

    @Test
    fun `deleting bv favorite cascades cached pages`() = runTest {
        val db = db()
        val dao = db.favoriteDao()
        dao.upsertBvFavorite(
            BvFavoriteEntity(
                bvid = "BV1",
                title = "书",
                pageCount = 2,
                addedAt = 1,
                lastSyncedAt = 2,
            )
        )
        dao.insertBvPages(
            listOf(
                BvFavoritePageEntity("BV1", 1, 1, "P1", 1_000, 0),
                BvFavoritePageEntity("BV1", 2, 2, "P2", 2_000, 1),
            )
        )
        assertEquals(2, dao.getBvPages("BV1").size)

        dao.deleteBvFavorite("BV1")
        assertTrue(dao.getBvPages("BV1").isEmpty())
        assertNull(dao.getBvFavorite("BV1"))
        db.close()
    }

    @Test
    fun `has any favorite considers folder tracks and bv favorites`() = runTest {
        val db = db()
        val dao = db.favoriteDao()
        assertFalse(dao.hasAnyFavorite())

        val folderId = dao.insertFolder(folder(name = "夹1"))
        dao.insertTrack(
            FavoriteTrackEntity(
                folderId = folderId,
                bvid = "BV1",
                cid = 1,
                title = "P1",
                order = 0,
            )
        )
        assertTrue(dao.hasAnyFavorite())

        dao.deleteFolder(folderId)
        assertFalse(dao.hasAnyFavorite())

        dao.upsertBvFavorite(BvFavoriteEntity(bvid = "BV2", title = "书"))
        assertTrue(dao.hasAnyFavorite())
        db.close()
    }
}
