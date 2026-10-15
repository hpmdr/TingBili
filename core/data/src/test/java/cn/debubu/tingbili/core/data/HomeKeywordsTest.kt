package cn.debubu.tingbili.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import java.io.File
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 回归：播放进度每 500ms 写一次 DataStore（LAST_POSITION_MS），
 * homeKeywords 必须去重，否则首页推荐流会被反复重建（无限刷新）。
 */
@RunWith(RobolectricTestRunner::class)
class HomeKeywordsTest {

    @Test
    fun `homeKeywords ignores unrelated datastore writes`() = runTest {
        val file = File.createTempFile("home-kw", ".preferences_pb")
        file.deleteOnExit()
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = PreferencesRepository(ds)
        repo.homeKeywords.test {
            assertEquals(
                listOf("有声小说", "有声书", "悬疑小说", "多人有声"),
                awaitItem()
            )
            // 模拟播放中的高频进度写入
            repeat(3) { repo.setLastPosition(it * 500L) }
            advanceUntilIdle()
            expectNoEvents()
            // 真正改关键词时仍正常发射
            repo.setHomeKeywords(listOf("悬疑小说"))
            assertEquals(listOf("悬疑小说"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
