package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.SubtitleDiskEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleDiskCacheTest {

    private fun db() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), TingBiliDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test
    fun `disk save get prune roundtrips`() = runTest {
        val dao = db().subtitleDiskDao()
        assertNull(dao.get("BV1:1"))
        dao.save(SubtitleDiskEntity("BV1:1", linesJson = "[{}]", label = "中文", fetchedAt = 3L))
        assertNotNull(dao.get("BV1:1"))
        // 按新旧只留 1 集：更旧的被裁
        dao.save(SubtitleDiskEntity("BV2:2", fetchedAt = 5L))
        dao.prune(1)
        assertNull(dao.get("BV1:1"))
        assertNotNull(dao.get("BV2:2"))
        dao.clear()
        assertNull(dao.get("BV2:2"))
    }

}
