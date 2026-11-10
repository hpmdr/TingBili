package cn.debubu.tingbili.feature.mine

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.update.GitHubRelease
import cn.debubu.tingbili.core.data.update.UpdateDataSource
import cn.debubu.tingbili.core.data.model.ImageFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.IOException
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

    @Test fun `image format persists`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.setImageFormat(ImageFormat.JPEG)
        advanceUntilIdle()
        assertEquals(ImageFormat.JPEG, prefs.imageFormat.first())
    }

    @Test fun `add and remove keyword`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.addHomeKeyword("白夜行")
        advanceUntilIdle()
        assertTrue(prefs.homeKeywords.first().contains("白夜行"))
        vm.removeHomeKeyword("白夜行")
        advanceUntilIdle()
        assertTrue(!prefs.homeKeywords.first().contains("白夜行"))
    }

    @Test fun `remove keeps at least one`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs, update, context)
        advanceUntilIdle()
        val cur = prefs.homeKeywords.first()
        assertTrue(cur.isNotEmpty())
        cur.forEach { vm.removeHomeKeyword(it) }
        advanceUntilIdle()
        assertTrue(prefs.homeKeywords.first().isNotEmpty())
    }
    @Test fun `bulk keywords are trimmed and deduplicated`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.setHomeKeywords(listOf("  悬疑  ", "", "悬疑", "有声书"))
        advanceUntilIdle()
        assertEquals(listOf("悬疑", "有声书"), prefs.homeKeywords.first())
    }

    @Test fun `bulk keywords keep one when all blank`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.setHomeKeywords(listOf(" ", ""))
        advanceUntilIdle()
        assertEquals(1, prefs.homeKeywords.first().size)
    }

    @Test fun `manual check newer shows available`() = runTest(dispatcher) {
        update.result = Result.success(
            GitHubRelease(tag = "v9.9.9", body = "- x", publishedAt = "2999-01-01T00:00:00Z")
        )
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        val st = vm.updateState.value
        assertTrue(st is GeneralSettingsViewModel.UpdateUiState.Available)
        assertEquals("v9.9.9", (st as GeneralSettingsViewModel.UpdateUiState.Available).tag)
    }

    @Test fun `manual check older shows up to date`() = runTest(dispatcher) {
        update.result = Result.success(
            GitHubRelease(tag = "v0.0.1", publishedAt = "2020-01-01T00:00:00Z")
        )
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        assertEquals(
            GeneralSettingsViewModel.UpdateUiState.UpToDate,
            vm.updateState.value,
        )
    }

    @Test fun `manual check failure shows failed`() = runTest(dispatcher) {
        update.result = Result.failure(IOException("offline"))
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        assertEquals(
            GeneralSettingsViewModel.UpdateUiState.Failed,
            vm.updateState.value,
        )
    }

    @Test fun `skip records release time`() = runTest(dispatcher) {
        update.result = Result.success(
            GitHubRelease(tag = "v9.9.9", publishedAt = "2999-01-01T00:00:00Z")
        )
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.checkUpdate()
        advanceUntilIdle()
        vm.skipRelease()
        advanceUntilIdle()
        assertEquals(32472144000L, prefs.skippedReleaseSec.first())
        assertEquals(
            GeneralSettingsViewModel.UpdateUiState.Idle,
            vm.updateState.value,
        )
    }

    @Test fun `auto check switch persists`() = runTest(dispatcher) {
        val vm = GeneralSettingsViewModel(prefs, update, context)
        vm.setAutoCheckUpdate(false)
        advanceUntilIdle()
        assertEquals(false, prefs.autoCheckUpdate.first())
    }
}
