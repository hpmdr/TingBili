package cn.debubu.tingbili.feature.settings

import android.content.Context
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
class AppearanceViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_appearance_${System.nanoTime()}")
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

    @Test fun `set theme mode persists`() = runTest(dispatcher) {
        val vm = AppearanceViewModel(prefs)
        vm.setThemeMode(ThemeMode.CUSTOM)
        advanceUntilIdle()
        assertEquals(ThemeMode.CUSTOM, prefs.themeMode.first())
    }

    @Test fun `set custom theme color persists and selects custom mode`() = runTest(dispatcher) {
        val vm = AppearanceViewModel(prefs)
        val customColor = 0xFF00AEEC.toInt()
        vm.setCustomThemeColor(customColor)
        advanceUntilIdle()
        assertEquals(customColor, prefs.customThemeColor.first())
        assertEquals(ThemeMode.CUSTOM, prefs.themeMode.first())
    }
}
