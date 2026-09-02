package cn.debubu.tingbili.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `set step persists`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs)
        vm.setStep(30)
        advanceUntilIdle()
        assertEquals(30, vm.stepSec.first())
    }

    @Test fun `set dynamic color persists`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs)
        vm.setDynamicColor(false)
        advanceUntilIdle()
        assertEquals(false, vm.dynamicColor.first())
    }

    @Test fun `step coerced in 5..60`() = runTest(dispatcher) {
        val vm = SettingsViewModel(prefs)
        vm.setStep(100)
        advanceUntilIdle()
        assertEquals(60, vm.stepSec.first())
        vm.setStep(1)
        advanceUntilIdle()
        assertEquals(5, vm.stepSec.first())
    }
}

private fun Context.preferencesDataStoreFile(name: String): java.io.File = java.io.File(cacheDir, "$name.preferences_pb")
