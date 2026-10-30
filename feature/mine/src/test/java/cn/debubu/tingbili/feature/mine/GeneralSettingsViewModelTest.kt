package cn.debubu.tingbili.feature.mine

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ImageFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeneralSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_general_settings_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `image format persists`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs)
        vm.setImageFormat(ImageFormat.JPEG)
        advanceUntilIdle()
        assertEquals(ImageFormat.JPEG, prefs.imageFormat.first())
    }

    @Test fun `add and remove keyword`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs)
        vm.addHomeKeyword("白夜行")
        advanceUntilIdle()
        assertTrue(prefs.homeKeywords.first().contains("白夜行"))
        vm.removeHomeKeyword("白夜行")
        advanceUntilIdle()
        assertTrue(!prefs.homeKeywords.first().contains("白夜行"))
    }

    @Test fun `remove keeps at least one`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs)
        advanceUntilIdle()
        val cur = prefs.homeKeywords.first()
        assertTrue(cur.isNotEmpty())
        cur.forEach { vm.removeHomeKeyword(it) }
        advanceUntilIdle()
        assertTrue(prefs.homeKeywords.first().isNotEmpty())
    }
    @Test fun `bulk keywords are trimmed and deduplicated`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs)
        vm.setHomeKeywords(listOf("  悬疑  ", "", "悬疑", "有声书"))
        advanceUntilIdle()
        assertEquals(listOf("悬疑", "有声书"), prefs.homeKeywords.first())
    }

    @Test fun `bulk keywords keep one when all blank`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs)
        vm.setHomeKeywords(listOf(" ", ""))
        advanceUntilIdle()
        assertEquals(1, prefs.homeKeywords.first().size)
    }
}