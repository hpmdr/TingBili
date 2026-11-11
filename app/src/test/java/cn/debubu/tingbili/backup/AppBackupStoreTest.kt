package cn.debubu.tingbili.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.debubu.tingbili.core.data.backup.BackupData
import cn.debubu.tingbili.core.data.backup.BackupPayload
import cn.debubu.tingbili.core.data.backup.BvFavoriteBackup
import cn.debubu.tingbili.core.data.backup.FolderBackup
import cn.debubu.tingbili.core.data.backup.HistoryBackup
import cn.debubu.tingbili.core.data.backup.ImportMode
import cn.debubu.tingbili.core.data.backup.SettingsBackup
import cn.debubu.tingbili.core.data.backup.TrackBackup
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.data.db.BvFavoriteEntity
import cn.debubu.tingbili.core.data.db.BvFavoritePageEntity
import cn.debubu.tingbili.core.data.db.FavoriteFolderEntity
import cn.debubu.tingbili.core.data.db.FavoriteTrackEntity
import cn.debubu.tingbili.core.data.db.HistoryEntity
import cn.debubu.tingbili.core.data.db.TingBiliDatabase
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppBackupStoreTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TingBiliDatabase
    private lateinit var prefs: PreferencesRepository
    private lateinit var authStore: FakeAuthStore
    private lateinit var auth: AuthRepository
    private lateinit var store: AppBackupStore

    private class FakeAuthStore(var session: BiliSession? = null) : AuthStore {
        override fun load(): BiliSession? = session
        override fun save(session: BiliSession?) {
            this.session = session
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TingBiliDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val file = context.preferencesDataStoreFile("test_backup_${System.nanoTime()}")
        if (file.exists()) file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { file },
        )
        prefs = PreferencesRepository(dataStore)
        authStore = FakeAuthStore()
        auth = AuthRepository(
            json = Json { ignoreUnknownKeys = true },
            store = authStore,
            io = Dispatchers.Unconfined,
        )
        store = AppBackupStore(db, db.favoriteDao(), db.historyDao(), prefs, auth, context)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seed() {
        val folderId = db.favoriteDao().insertFolder(
            FavoriteFolderEntity(name = "我的最爱", isDefault = true)
        )
        db.favoriteDao().insertTrack(
            FavoriteTrackEntity(folderId, "BV1", 1001, "曲目", order = 0)
        )
        db.favoriteDao().upsertBvFavorite(
            BvFavoriteEntity(bvid = "BV1", title = "合集", pageCount = 1)
        )
        db.favoriteDao().insertBvPages(
            listOf(BvFavoritePageEntity("BV1", 1001, 1, "P1"))
        )
        db.historyDao().save(
            HistoryEntity(
                bvid = "BV1",
                cid = 1001,
                positionMs = 5000,
                updatedAt = 1000L,
                listenDurationMs = 3000L,
                sourceType = HistoryEntity.SOURCE_BV,
                sourceId = "BV1",
                sourceTitle = "合集",
            )
        )
        prefs.setStep(30)
        prefs.setAudioQuality(30280)
        prefs.setHomeKeywords(listOf("悬疑"))
        auth.save(BiliSession("SESS", "JCT", 42, "user"))
    }

    @Test
    fun `export includes favorites history settings and auth`() = runTest(dispatcher) {
        seed()
        val payload = store.export(includeAuth = true)

        assertEquals("tingbili-backup", payload.format)
        assertTrue(payload.includesAuth)
        assertEquals("SESS", payload.data.auth?.sessdata)
        assertEquals(1, payload.data.folders.size)
        assertEquals(1, payload.data.tracks.size)
        assertEquals(1, payload.data.bvFavorites.size)
        assertEquals(1, payload.data.bvPages.size)
        assertEquals(1, payload.data.history.size)
        assertEquals(30, payload.data.settings.stepSec)
        assertEquals(30280, payload.data.settings.audioQuality)
        assertEquals(listOf("悬疑"), payload.data.settings.homeKeywords)
    }

    @Test
    fun `replace import restores everything`() = runTest(dispatcher) {
        seed()
        val payload = store.export(includeAuth = true)

        db.favoriteDao().clearTracks()
        db.favoriteDao().clearFolders()
        db.favoriteDao().clearAllBvPages()
        db.favoriteDao().clearAllBvFavorites()
        db.historyDao().clearAll()
        auth.save(null)
        prefs.setStep(5)
        advanceUntilIdle()

        val result = store.import(payload, ImportMode.REPLACE)

        assertEquals(1, result.tracksAdded)
        assertEquals(1, result.historyAdded)
        assertEquals(1, result.bvFavorites)
        assertTrue(result.authRestored)
        assertEquals(30, prefs.stepSec.first())
        assertEquals("SESS", auth.sessionNow()?.sessdata)
        assertEquals(1, db.favoriteDao().getAllTracks().size)
        assertEquals(1, db.favoriteDao().getAllBvFavorites().size)
        assertEquals(1, db.favoriteDao().getAllBvPages().size)
        assertEquals(5000L, db.historyDao().getAll().first().positionMs)
        assertTrue(db.favoriteDao().hasAnyFavorite())
    }

    @Test
    fun `merge maps folder by name and skips duplicate track`() = runTest(dispatcher) {
        val localFolderId = db.favoriteDao().insertFolder(
            FavoriteFolderEntity(name = "合集A", isDefault = false)
        )
        db.favoriteDao().insertTrack(
            FavoriteTrackEntity(localFolderId, "BV1", 1001, "曲目", order = 0)
        )
        db.historyDao().save(
            HistoryEntity("BV1", 1001, 100, 2000L, sourceId = "BV1")
        )

        val payload = BackupPayload(
            data = BackupData(
                folders = listOf(FolderBackup(99, "合集A")),
                tracks = listOf(TrackBackup(99, "BV1", 1001, "曲目")),
                history = listOf(HistoryBackup("BV1", 1001, 5000, 1000L, sourceId = "BV1")),
            )
        )
        val result = store.import(payload, ImportMode.MERGE)

        assertEquals(1, result.foldersMatched)
        assertEquals(0, result.tracksAdded)
        assertEquals(1, result.tracksSkipped)
        assertEquals(1, db.favoriteDao().getAllFolders().count { !it.isDefault })
        // 本地历史更新（2000 > 1000），不能被打包里的旧进度覆盖
        assertEquals(100L, db.historyDao().getLatestForTrack("BV1", 1001)?.positionMs)
    }

    @Test
    fun `merge updates history when backup is newer`() = runTest(dispatcher) {
        db.historyDao().save(
            HistoryEntity("BV1", 1001, 100, 1000L, sourceId = "BV1")
        )
        val payload = BackupPayload(
            data = BackupData(
                history = listOf(HistoryBackup("BV1", 1001, 9000, 3000L, sourceId = "BV1")),
            )
        )

        val result = store.import(payload, ImportMode.MERGE)

        assertEquals(1, result.historyUpdated)
        assertEquals(9000L, db.historyDao().getLatestForTrack("BV1", 1001)?.positionMs)
    }

    @Test
    fun `import without auth keeps current session`() = runTest(dispatcher) {
        auth.save(BiliSession("CURRENT", "JCT", 7, "me"))
        advanceUntilIdle()

        val payload = BackupPayload(includesAuth = false, data = BackupData())
        val result = store.import(payload, ImportMode.MERGE)

        assertFalse(result.authRestored)
        assertEquals("CURRENT", auth.sessionNow()?.sessdata)
    }

    @Test
    fun `restore clamps out of range settings`() = runTest(dispatcher) {
        val payload = BackupPayload(
            data = BackupData(
                settings = SettingsBackup(stepSec = 999, speed = 99f, audioQuality = 12345),
            )
        )

        store.import(payload, ImportMode.MERGE)

        assertEquals(60, prefs.stepSec.first())
        assertEquals(3f, prefs.speed.first())
        assertEquals(PreferencesRepository.DEFAULT_AUDIO_QUALITY, prefs.audioQuality.first())
    }

    @Test
    fun `export without auth omits session`() = runTest(dispatcher) {
        auth.save(BiliSession("SESS", "JCT", 42, "user"))
        advanceUntilIdle()

        val payload = store.export(includeAuth = false)

        assertFalse(payload.includesAuth)
        assertNotNull(payload.data)
        assertEquals(null, payload.data.auth)
    }
}
