package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.db.SubtitleDiskEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @Test
    fun `migration 7 to 8 creates subtitle table`() {
        // exportSchema=false，直接在空库上跑 MIGRATION_7_8，断言建表且幂等
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sqldb = helper.writableDatabase
        TingBiliDatabase.MIGRATION_7_8.migrate(sqldb)
        TingBiliDatabase.MIGRATION_7_8.migrate(sqldb)
        sqldb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='SubtitleDiskEntity'").use {
            assertEquals(1, it.count)
        }
    }
}
