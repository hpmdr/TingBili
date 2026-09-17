package cn.debubu.tingbili.feature.settings

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.model.ThemeMode
import java.io.File
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: PreferencesRepository
    private lateinit var audioCache: SimpleCache

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_settings_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
        audioCache = SimpleCache(
            File(context.cacheDir, "settings_cache_${System.nanoTime()}"),
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(context)
        )
    }

    @After
    fun tearDown() {
        audioCache.release()
        Dispatchers.resetMain()
    }

    @Test fun `set step persists`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs, audioCache)
        vm.setStep(30)
        advanceUntilIdle()
        assertEquals(30, prefs.stepSec.first())
    }

    @Test fun `set theme mode persists`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs, audioCache)
        vm.setThemeMode(ThemeMode.CUSTOM)
        advanceUntilIdle()
        assertEquals(ThemeMode.CUSTOM, prefs.themeMode.first())
    }

    @Test fun `set custom theme color persists and selects custom mode`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs, audioCache)
        val customColor = 0xFF00AEEC.toInt()
        vm.setCustomThemeColor(customColor)
        advanceUntilIdle()
        assertEquals(customColor, prefs.customThemeColor.first())
        assertEquals(ThemeMode.CUSTOM, prefs.themeMode.first())
    }

    @Test fun `set cache max persists`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs, audioCache)
        vm.setCacheMaxMb(1024)
        advanceUntilIdle()
        assertEquals(1024, prefs.cacheMaxMb.first())
    }

    @Test fun `cache max coerced in range 100 to 2048`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs, audioCache)
        vm.setCacheMaxMb(10000)
        advanceUntilIdle()
        assertEquals(2048, prefs.cacheMaxMb.first())
        vm.setCacheMaxMb(1)
        advanceUntilIdle()
        assertEquals(100, prefs.cacheMaxMb.first())
    }

    @Test fun `step coerced in range 5 to 60`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs, audioCache)
        vm.setStep(100)
        advanceUntilIdle()
        assertEquals(60, prefs.stepSec.first())
        vm.setStep(1)
        advanceUntilIdle()
        assertEquals(5, prefs.stepSec.first())
    }
}

private fun Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
