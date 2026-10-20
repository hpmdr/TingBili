package cn.debubu.tingbili.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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

    @Test
    fun `migration 6 to 7 creates snapshot table`() {
        // exportSchema=false，故不用 MigrationTestHelper（它要求 assets 里有 schema json），
        // 直接在空库上跑 MIGRATION_6_7，断言建表 SQL 正确且幂等。
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        TingBiliDatabase.MIGRATION_6_7.migrate(db)
        // 幂等：重复跑不抛异常（IF NOT EXISTS）
        TingBiliDatabase.MIGRATION_6_7.migrate(db)
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='HomeSnapshotEntity'").use {
            assertEquals(1, it.count)
        }
        db.query("PRAGMA table_info(HomeSnapshotEntity)").use {
            val cols = mutableListOf<String>()
            val nameIdx = it.getColumnIndex("name")
            while (it.moveToNext()) cols.add(it.getString(nameIdx))
            assertEquals(listOf("id", "keywordsJson", "tracksJson", "updatedAt"), cols)
        }
        helper.close()
    }
}
