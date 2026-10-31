package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.DEFAULT_FOLDER_ID
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.core.data.model.BvFavoritePageSnapshot
import cn.debubu.tingbili.core.data.model.BvFavoriteSnapshot
import cn.debubu.tingbili.core.data.model.Track
import cn.debubu.tingbili.core.data.repository.DefaultFavoritesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavoritesRepositoryTest {

    private fun db(): TingBiliDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun track(cid: Long, bvid: String = "BV1"): Track = Track(
        bvid = bvid,
        cid = cid,
        title = "P$cid",
        author = "up",
        cover = "https://cover/$cid",
        durationMs = cid * 1_000,
        subtitleUrl = null,
        pageCount = 3,
        videoTitle = "书",
        pageIndex = cid.toInt(),
    )

    private fun snapshot(
        title: String = "书",
        pages: List<BvFavoritePageSnapshot> = listOf(
            BvFavoritePageSnapshot(1, 1, "P1", 1_000),
            BvFavoritePageSnapshot(2, 2, "P2", 2_000),
        ),
    ): BvFavoriteSnapshot = BvFavoriteSnapshot(
        bvid = "BV1",
        title = title,
        author = "up",
        authorMid = 42,
        cover = "https://cover",
        durationMs = 3_000,
        pages = pages,
    )

    @Test
    fun `ensure default folder is stable and id 1`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())

        assertEquals(DEFAULT_FOLDER_ID, repo.ensureDefaultFolder())
        assertEquals(DEFAULT_FOLDER_ID, repo.ensureDefaultFolder())
        val default = repo.observeFolders().first().first()
        assertTrue(default.isDefault)
        assertEquals("我的最爱", default.name)
        db.close()
    }

    @Test
    fun `create folder appends sort order after default`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())

        repo.ensureDefaultFolder()
        val first = repo.createFolder("夹1")
        val second = repo.createFolder("夹2")

        val folders = repo.observeFolders().first()
        assertEquals(
            listOf(DEFAULT_FOLDER_ID, first, second),
            folders.map { it.id },
        )
        assertEquals(listOf(0, 1), folders.filterNot { it.isDefault }.map { it.sortOrder })
        db.close()
    }

    @Test
    fun `default folder newest first and manual append`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())
        val defaultId = repo.ensureDefaultFolder()
        val manualId = repo.createFolder("手动")

        repo.addTracksToFolders(setOf(defaultId), listOf(track(1), track(2)))
        var defaultTracks = repo.observeFolderSummaries().first()
            .first { it.folder.id == defaultId }
        assertEquals(2, defaultTracks.trackCount)

        repo.addTracksToFolders(setOf(defaultId), listOf(track(3)))
        val ordered = db.favoriteDao().getTracks(defaultId)
        assertEquals(listOf(3L, 1L, 2L), ordered.map { it.cid })

        val manualResult = repo.addTracksToFolders(setOf(manualId), listOf(track(1), track(2)))
        assertEquals(2, manualResult.added)
        assertEquals(0, manualResult.skipped)
        assertEquals(listOf(1L, 2L), db.favoriteDao().getTracks(manualId).map { it.cid })

        val duplicate = repo.addTracksToFolders(setOf(manualId), listOf(track(1)))
        assertEquals(0, duplicate.added)
        assertEquals(1, duplicate.skipped)
        db.close()
    }

    @Test
    fun `toggle default and manual membership`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())
        val defaultId = repo.ensureDefaultFolder()
        val manualId = repo.createFolder("手动")

        assertTrue(repo.toggleDefaultTrack(track(1)))
        assertTrue(repo.observeTrackInFolder(defaultId, "BV1", 1).first())
        assertFalse(repo.toggleDefaultTrack(track(1)))
        assertFalse(repo.observeTrackInFolder(defaultId, "BV1", 1).first())

        assertTrue(repo.toggleTrackInFolder(manualId, track(2)))
        assertTrue(repo.observeTrackInFolder(manualId, "BV1", 2).first())
        assertFalse(repo.toggleTrackInFolder(manualId, track(2)))
        assertFalse(repo.observeTrackInFolder(manualId, "BV1", 2).first())
        db.close()
    }

    @Test
    fun `reorder folders persists manual order and skips default`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())
        val defaultId = repo.ensureDefaultFolder()
        val a = repo.createFolder("A")
        val b = repo.createFolder("B")
        val c = repo.createFolder("C")

        repo.reorderFolders(listOf(c, defaultId, a, b))

        val folders = repo.observeFolders().first()
        assertEquals(defaultId, folders.first().id)
        assertEquals(listOf(c, a, b), folders.filterNot { it.isDefault }.map { it.id })
        assertEquals(listOf(0, 1, 2), folders.filterNot { it.isDefault }.map { it.sortOrder })
        db.close()
    }

    @Test
    fun `default folder cannot be deleted`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())
        val defaultId = repo.ensureDefaultFolder()

        assertFalse(repo.deleteFolder(defaultId))
        assertNotNull(db.favoriteDao().getFolder(defaultId))
        db.close()
    }

    @Test
    fun `add refresh remove bv favorite preserves addedAt`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())
        val dao = db.favoriteDao()

        repo.addBvFavorite(snapshot())
        val first = dao.getBvFavorite("BV1")
        assertNotNull(first)
        assertEquals(2, first!!.pageCount)
        assertEquals(2, dao.getBvPages("BV1").size)
        val addedAt = first.addedAt

        repo.refreshBvFavorite(snapshot(title = "新标题", pages = listOf(BvFavoritePageSnapshot(3, 1, "P1", 3_000))))
        val refreshed = dao.getBvFavorite("BV1")
        assertEquals("新标题", refreshed?.title)
        assertEquals(1, refreshed?.pageCount)
        assertEquals(addedAt, refreshed?.addedAt)
        assertEquals(1, dao.getBvPages("BV1").size)
        assertTrue((refreshed?.lastSyncedAt ?: 0L) >= addedAt)

        repo.removeBvFavorite("BV1")
        assertNull(dao.getBvFavorite("BV1"))
        assertTrue(dao.getBvPages("BV1").isEmpty())
        db.close()
    }

    @Test
    fun `has any favorite reflects tracks and bv favorites`() = runTest {
        val db = db()
        val repo = DefaultFavoritesRepository(db, db.favoriteDao())
        assertFalse(repo.hasAnyFavorite())

        repo.toggleDefaultTrack(track(1))
        assertTrue(repo.hasAnyFavorite())
        repo.toggleDefaultTrack(track(1))
        assertFalse(repo.hasAnyFavorite())

        repo.addBvFavorite(snapshot())
        assertTrue(repo.hasAnyFavorite())
        db.close()
    }
}
