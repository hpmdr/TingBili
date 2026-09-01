package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.PlaylistEntity
import cn.debubu.tingbili.core.data.db.PlaylistTrackEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.core.data.model.Track
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
}
