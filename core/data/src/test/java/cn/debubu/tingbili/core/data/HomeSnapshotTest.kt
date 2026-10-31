package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.HomeSnapshotEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeSnapshotTest {

    @Test
    fun `snapshot save and get roundtrips`() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.homeSnapshotDao()
        assertNull(dao.get())
        dao.save(HomeSnapshotEntity(keywordsJson = "[\"a\"]", tracksJson = "[{\"bvid\":\"BV1\"}]"))
        val got = dao.get()
        assertNotNull(got)
        assertEquals("[\"a\"]", got!!.keywordsJson)
        assertEquals(got.id, dao.observe().first()!!.id)
        dao.clear()
        assertNull(dao.get())
        db.close()
    }

}
