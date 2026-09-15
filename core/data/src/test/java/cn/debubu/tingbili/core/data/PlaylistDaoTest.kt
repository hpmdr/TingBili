package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.core.data.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistDaoTest {

    @Test
    fun `playlist insert and track join persists`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val pid = db.playlistDao().insert(PlaylistEntity(name = "听书"))
        val track = Track("BV1xx", 1, "P1", "up", "", 1000, null)
        db.playlistDao().addTrack(
            PlaylistTrackEntity(
                playlistId = pid,
                bvid = track.bvid,
                cid = track.cid,
                title = track.title,
                order = 0
            )
        )
        assertEquals(1, db.playlistDao().getTracks(pid).size)
        db.close()
    }

    @Test
    fun `playlist summaries include track count and total duration`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.playlistDao()
        val playlistId = dao.insert(PlaylistEntity(name = "听书"))
        val emptyPlaylistId = dao.insert(PlaylistEntity(name = "空收藏"))
        dao.addTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                bvid = "BV1",
                cid = 1,
                title = "P1",
                order = 0,
                durationMs = 1_000
            )
        )
        dao.addTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                bvid = "BV1",
                cid = 2,
                title = "P2",
                order = 1,
                durationMs = 2_000
            )
        )

        val summaries = dao.observePlaylistSummaries().first()
        val summary = summaries.first { it.playlist.id == playlistId }
        val emptySummary = summaries.first { it.playlist.id == emptyPlaylistId }

        assertEquals(2, summary.trackCount)
        assertEquals(3_000L, summary.totalDurationMs)
        assertEquals(0, emptySummary.trackCount)
        assertEquals(0L, emptySummary.totalDurationMs)
        db.close()
    }
}
