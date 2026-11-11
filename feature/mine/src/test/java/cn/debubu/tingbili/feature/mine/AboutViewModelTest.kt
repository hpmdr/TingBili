package cn.debubu.tingbili.feature.mine

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.update.GitHubRelease
import cn.debubu.tingbili.core.data.update.UpdateDataSource
import java.io.IOException
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
class AboutViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var prefs: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.preferencesDataStoreFile("test_about_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file }
        )
        prefs = PreferencesRepository(dataStore)
    }

    private class FakeUpdate(var result: Result<GitHubRelease> = Result.failure(IOException("offline"))) :
        UpdateDataSource {
        override suspend fun fetchLatest(): Result<GitHubRelease> = result
    }

    private val update = FakeUpdate()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `version label starts with v`() {
        val vm = AboutViewModel(prefs, update, context)
        assertTrue(vm.versionLabel.startsWith("v"))
    }

    @Test fun `manual check newer shows available`() = runTest(dispatcher) {
        update.result = Result.success(
            GitHubRelease(tag = "v9.9.9", body = "- x", publishedAt = "2999-01-01T00:00:00Z")
        )
        val vm = AboutViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        val st = vm.updateState.value
        assertTrue(st is AboutViewModel.UpdateUiState.Available)
        assertEquals("v9.9.9", (st as AboutViewModel.UpdateUiState.Available).tag)
    }

    @Test fun `manual check older shows up to date`() = runTest(dispatcher) {
        update.result = Result.success(
            GitHubRelease(tag = "v0.0.1", publishedAt = "2020-01-01T00:00:00Z")
        )
        val vm = AboutViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        assertEquals(
            AboutViewModel.UpdateUiState.UpToDate,
            vm.updateState.value,
        )
    }

    @Test fun `manual check failure shows failed`() = runTest(dispatcher) {
        update.result = Result.failure(IOException("offline"))
        val vm = AboutViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        assertEquals(
            AboutViewModel.UpdateUiState.Failed,
            vm.updateState.value,
        )
    }

    @Test fun `skip records release time`() = runTest(dispatcher) {
        update.result = Result.success(
            GitHubRelease(tag = "v9.9.9", publishedAt = "2999-01-01T00:00:00Z")
        )
        val vm = AboutViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        vm.skipRelease()
        advanceUntilIdle()
        assertEquals(32472144000L, prefs.skippedReleaseSec.first())
        assertEquals(
            AboutViewModel.UpdateUiState.Idle,
            vm.updateState.value,
        )
    }

    @Test fun `auto check switch persists`() = runTest(dispatcher) {
        val vm = AboutViewModel(prefs, update, context)
        vm.setAutoCheckUpdate(false)
        advanceUntilIdle()
        assertEquals(false, prefs.autoCheckUpdate.first())
    }

    @Test fun `manual check records last check time`() = runTest(dispatcher) {
        update.result = Result.failure(IOException("offline"))
        val vm = AboutViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        assertTrue(prefs.lastUpdateCheckMs.first() > 0L)
    }
}
