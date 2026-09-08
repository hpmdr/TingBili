# 播放服务独立化（方案 A）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 ExoPlayer 的唯一所有权收归 `TingBiliPlaybackService`，UI 经 `MediaController` 播放，实现后台/锁屏/通知栏/线控与全页状态同步。

**Architecture:** 新增 `PlaybackConnection`（App 级会话连接）+ `MediaControllerHandle`（`PlayerHandle` 的 Controller 实现）；ExoPlayer 创建移入 `ServiceComponent`；`PlayerManager` 改为队列门面（接口与状态流不变）；详情页播后导航到播放页。

**Tech Stack:** Kotlin, Media3 1.4.1 (session/exoplayer), Hilt (SingletonComponent/ServiceComponent), Coroutines/StateFlow, Robolectric 单测

## Global Constraints

- AGP 8.7.3，Gradle 8.11.1，Kotlin 2.1.0，media3 1.4.1，compileSdk 36，minSdk 31，targetSdk 36。
- Java source/target 兼容 17，Gradle 守护进程跑在 JDK 21（`gradle.properties` 的本地 `org.gradle.java.home` 未暂存改动，任何任务都不要 `git add` 它）。
- 中文简明 KDoc（仓库现有风格），命名全称优先（`transport`/`connection`，不缩写）。
- 测试里所有 `PlayerManager(...)` 构造调用均为位置传参，增删参数必须同步更新全部调用点（用 grep 找全）。
- `PlayerHandle` 接口签名冻结（4 个 Fake 零改动）。
- 每个任务结束独立提交，信息格式沿用仓库习惯（`feat/fix/chore/docs` 前缀）。

---

### Task 1: PlaybackConnection（新建）

**Files:**
- Create: `core/media/src/main/java/cn/debubu/tingbili/core/media/PlaybackConnection.kt`
- Test: `core/media/src/test/java/cn/debubu/tingbili/core/media/PlaybackConnectionTest.kt`

**Interfaces:**
- Consumes: `TingBiliPlaybackService`（已存在，`MediaSessionService`）
- Produces: `PlaybackConnection.controller: StateFlow<MediaController?>`、`suspend fun awaitController(): MediaController`、`fun connect()`、`fun addListener(Player.Listener)`、`fun ensureServiceStarted()`——Task 2、Task 5 依赖这五个成员，签名冻结。

- [ ] **Step 1: Write the failing test**

```kotlin
package cn.debubu.tingbili.core.media

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackConnectionTest {

    @Test
    fun `session token points at playback service`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = PlaybackConnection(context)
        val token = connection.sessionToken
        assertTrue(token.componentName.className.endsWith("TingBiliPlaybackService"))
        connection.release()
    }

    @Test
    fun `listener fan-out keeps listeners until release`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = PlaybackConnection(context)
        var added = 0
        connection.addListener(object : Player.Listener {})
        added = connection.pendingListenerCountForTest()
        assertTrue(added == 1)
        connection.release()
        assertNotNull(connection)
    }
}
```

> 说明：`sessionToken` 与 `pendingListenerCountForTest()` 是为可测性在实现里预留的内部成员（见 Step 3 代码），Robolectric 下不真正建连，避免 MediaController 真机依赖。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "cn.debubu.tingbili.core.media.PlaybackConnectionTest" 2>&1 | tail -15`
Expected: FAIL（`PlaybackConnection` 不存在，编译失败）

- [ ] **Step 3: Write minimal implementation**

```kotlin
package cn.debubu.tingbili.core.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * App 级媒体会话连接。负责拉起前台播放服务、异步建立 MediaController、
 * 把 Player.Listener 扇出到新旧 Controller。Controller 就绪前发出的命令由调用方排队。
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 指向 TingBiliPlaybackService 的会话令牌，对外暴露以便单测不断言实现细节。 */
    val sessionToken: SessionToken =
        SessionToken(context, ComponentName(context, TingBiliPlaybackService::class.java))

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pendingListeners = mutableListOf<Player.Listener>()

    /** Controller 就绪前暂存监听器，就绪后一次性挂上；换 Controller 时重新挂。 */
    fun addListener(listener: Player.Listener) {
        pendingListeners += listener
        _controller.value?.addListener(listener)
    }

    /** 拉起前台播放服务（后台保活 + 通知栏的前提），幂等。 */
    fun ensureServiceStarted() {
        ContextCompat.startForegroundService(context, Intent(context, TingBiliPlaybackService::class.java))
    }

    /** 建立会话连接，重复调用无副作用。 */
    fun connect() {
        if (controllerFuture != null) return
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = future.get()
                pendingListeners.forEach { controller.addListener(it) }
                _controller.value = controller
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** 挂起直到 Controller 就绪（内部自动 connect）。 */
    suspend fun awaitController(): MediaController {
        connect()
        return controller.filterNotNull().first()
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        pendingListeners.clear()
        _controller.value = null
    }

    @VisibleForTesting
    internal fun pendingListenerCountForTest(): Int = pendingListeners.size
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "cn.debubu.tingbili.core.media.PlaybackConnectionTest" 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL，2 tests passed

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/java/cn/debubu/tingbili/core/media/PlaybackConnection.kt core/media/src/test/java/cn/debubu/tingbili/core/media/PlaybackConnectionTest.kt
git commit -m "feat(media): add PlaybackConnection for MediaController session"
```

### Task 2: MediaControllerHandle（新建）

**Files:**
- Create: `core/media/src/main/java/cn/debubu/tingbili/core/media/MediaControllerHandle.kt`
- Test: `core/media/src/test/java/cn/debubu/tingbili/core/media/MediaControllerHandleTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `PlaybackConnection`（`controller: StateFlow`、`addListener`）
- Produces: `MediaControllerHandle : PlayerHandle`——Task 4 的 Hilt 绑定依赖它。

- [ ] **Step 1: Write the failing test**

```kotlin
package cn.debubu.tingbili.core.media

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaControllerHandleTest {

    @Test
    fun `disconnected handle degrades to safe defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handle: PlayerHandle = MediaControllerHandle(PlaybackConnection(context))
        assertEquals(0L, handle.currentPosition)
        assertEquals(0L, handle.duration)
        assertFalse(handle.isPlaying)
        // 未连接时控制命令为 no-op，不抛异常
        handle.play()
        handle.pause()
        handle.seekTo(1000L)
        handle.release() // Service 唯一释放，此处必须为空实现
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:media:testDebugUnitTest --tests "cn.debubu.tingbili.core.media.MediaControllerHandleTest" 2>&1 | tail -8`
Expected: FAIL（类不存在，编译失败）

- [ ] **Step 3: Write minimal implementation**

```kotlin
package cn.debubu.tingbili.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PlayerHandle 的生产实现：把调用委托给当前 MediaController。
 * 未连接时读操作返回安全默认值、写操作为 no-op（调用方 play() 会 await 就绪）。
 * release() 故意为空实现——ExoPlayer 只由 TingBiliPlaybackService 释放。
 */
@Singleton
class MediaControllerHandle @Inject constructor(
    private val connection: PlaybackConnection,
) : PlayerHandle {
    private fun controller() = connection.controller.value

    override val currentPosition: Long get() = controller()?.currentPosition ?: 0L
    override val duration: Long get() = controller()?.duration ?: 0L
    override val isPlaying: Boolean get() = controller()?.isPlaying ?: false
    override var repeatMode: Int
        get() = controller()?.repeatMode ?: Player.REPEAT_MODE_OFF
        set(value) { controller()?.repeatMode = value }
    override val currentMediaItemIndex: Int get() = controller()?.currentMediaItemIndex ?: 0

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        controller()?.setMediaItems(items, startIndex, startPositionMs)
    }

    override fun prepare() { controller()?.prepare() }
    override fun play() { controller()?.play() }
    override fun pause() { controller()?.pause() }
    override fun seekTo(positionMs: Long) { controller()?.seekTo(positionMs) }
    override fun setPlaybackSpeed(speed: Float) { controller()?.setPlaybackSpeed(speed) }
    override fun addListener(listener: Player.Listener) = connection.addListener(listener)
    override fun release() { /* 唯一释放权在 Service，此处为空 */ }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:media:testDebugUnitTest --tests "cn.debubu.tingbili.core.media.MediaControllerHandleTest" 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/java/cn/debubu/tingbili/core/media/MediaControllerHandle.kt core/media/src/test/java/cn/debubu/tingbili/core/media/MediaControllerHandleTest.kt
git commit -m "feat(media): add MediaControllerHandle as production PlayerHandle"
```

### Task 3: ExoPlayer 归属 Service（ServicePlayerModule 新建 + Service 注释）

**Files:**
- Create: `core/media/src/main/java/cn/debubu/tingbili/core/media/ServicePlayerModule.kt`
- Modify: `core/media/src/main/java/cn/debubu/tingbili/core/media/TingBiliPlaybackService.kt`（仅补 KDoc/注释，逻辑不动）

**Interfaces:**
- Consumes: 无（自包含的 ExoPlayer 构建逻辑，从 MediaModule 原样搬运）
- Produces: `@ServiceScoped Player`——替换 Task 4 删除的 `@Singleton Player`，Service 注入点不变。

- [ ] **Step 1: Create ServicePlayerModule**

```kotlin
package cn.debubu.tingbili.core.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import cn.debubu.tingbili.data.bilibili.WbiSigner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

/**
 * Service 作用域的播放器供给：ExoPlayer 只在这里创建，
 * 只注入 TingBiliPlaybackService，只在 Service.onDestroy 释放。
 */
@Module
@InstallIn(ServiceComponent::class)
object ServicePlayerModule {

    @Provides
    @ServiceScoped
    fun providePlaybackPlayer(@ApplicationContext context: Context): Player {
        // B 站音频 CDN 要求带 Referer/UA，否则 403
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(WbiSigner.BROWSER_UA)
            .setDefaultRequestProperties(mapOf("Referer" to WbiSigner.REFERER))
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }
}
```

- [ ] **Step 2: 补 Service 类注释（逻辑不动）**

在 `TingBiliPlaybackService` 类 KDoc 追加一行：`注入的 Player 为 @ServiceScoped，唯一释放点为 onDestroy。`其余不动。

- [ ] **Step 3: 编译验证（此时 Singleton Player 仍在，双绑定并存，编译应通过）**

Run: `./gradlew :core:media:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/media/src/main/java/cn/debubu/tingbili/core/media/ServicePlayerModule.kt core/media/src/main/java/cn/debubu/tingbili/core/media/TingBiliPlaybackService.kt
git commit -m "feat(media): scope ExoPlayer to playback service"
```

### Task 4: MediaModule 改绑定 + 删除 ExoPlayerHandle

**Files:**
- Modify: `core/media/src/main/java/cn/debubu/tingbili/core/media/MediaModule.kt`（删 `providePlayer`，换 `providePlayerHandle` 实现）
- Modify: `core/media/src/main/java/cn/debubu/tingbili/core/media/PlayerHandle.kt`（删 `ExoPlayerHandle` 类，接口与 KDoc 保留并补传输层说明）

**Interfaces:**
- Consumes: Task 1/2/3 的产出
- Produces: Hilt 图中唯一的 `PlayerHandle` 绑定——Task 5 依赖注入成立。

- [ ] **Step 1: 改 MediaModule**

删除 `providePlayer`（原 22–47 行）与相关 import（`Context`、`AudioAttributes`、`C`、`DefaultHttpDataSource`、`ExoPlayer`、`DefaultMediaSourceFactory`、`WbiSigner`、`ApplicationContext`），`providePlayerHandle` 改为：

```kotlin
@Provides
@Singleton
fun providePlayerHandle(connection: PlaybackConnection): PlayerHandle =
    MediaControllerHandle(connection)
```

`provideTimerManager` 原样保留（签名不变，自动改走 Controller）。

- [ ] **Step 2: 删 ExoPlayerHandle，补接口 KDoc**

`PlayerHandle.kt` 删除 `ExoPlayerHandle` 类（原 27–53 行）及对其的 import；接口 KDoc 改为：

```kotlin
/**
 * 播放传输层抽象：生产走 MediaControllerHandle（经 MediaController 操作 Service 内 ExoPlayer），
 * 单测用轻量 Fake。注意 release() 语义——生产实现为空，唯一释放权在 TingBiliPlaybackService。
 */
interface PlayerHandle { ... }
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :core:media:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL（若报 `@Singleton Player` 缺失，说明仍有生产代码直接注入 `Player`，用 `grep -rn ": Player[^a-zA-Z]" --include=*.kt app core data feature` 定位并改走 `PlayerHandle`，不得新增绑定）

- [ ] **Step 4: Commit**

```bash
git add core/media/src/main/java/cn/debubu/tingbili/core/media/MediaModule.kt core/media/src/main/java/cn/debubu/tingbili/core/media/PlayerHandle.kt
git commit -m "feat(media): bind PlayerHandle to MediaController, drop direct ExoPlayer"
```

### Task 5: PlayerManager 改队列门面（transport + 拉起服务 + 乐观更新）

**Files:**
- Modify: `core/media/src/main/java/cn/debubu/tingbili/core/media/PlayerManager.kt`
- Modify（测试构造补实参）: `core/media/.../PlayerManagerTest.kt:124`、`feature/home/.../HomeViewModelTest.kt:137`、`feature/history/.../HistoryViewModelTest.kt:154`、`feature/playlist/.../PlaylistViewModelTest.kt:152`
- Test（新增）: 在 `PlayerManagerTest` 追加 `play starts foreground playback service`

**Interfaces:**
- Consumes: Task 4 的 Hilt 绑定；`PlaybackConnection.ensureServiceStarted()`（Task 1 已提供，PlayerManager 经 `@ApplicationContext` 直调 Intent，不依赖 connection，避免双依赖）
- Produces: 行为不变的 `state` 流 + 拉起语义——Task 6/8 直接受益。

- [ ] **Step 1: 找全构造调用点**

Run: `grep -rn "PlayerManager(" --include=*.kt app core data feature | grep -v "class PlayerManager"`
Expected: 命中上列 4 个测试文件（生产侧全由 Hilt 注入，无需改）

- [ ] **Step 2: 改 PlayerManager**

改动清单（其余 200+ 行不动）：
1. import 追加：`android.content.Context`、`android.content.Intent`、`androidx.core.content.ContextCompat`、`dagger.hilt.android.qualifiers.ApplicationContext`。
2. 构造参数 `private val player: PlayerHandle` 更名为 `private val transport: PlayerHandle`，末尾追加 `@ApplicationContext private val appContext: Context`。
3. 方法体内 `player.` 全部机械替换为 `transport.`。
4. `play()` 在 `if (tracks.isEmpty()) return` 之后首行插入：

```kotlin
// 先拉起前台服务：后台保活 + 通知栏的前提；幂等，重复调用无副作用。
ContextCompat.startForegroundService(
    appContext,
    Intent(appContext, TingBiliPlaybackService::class.java)
)
```

5. `play()` 在 `val safeIndex = ...` 之后插入乐观更新：

```kotlin
// 乐观更新：URL 逐个解析很慢，先让播放页/mini-player 立刻显示队列归属。
_state.update {
    it.copy(
        queue = tracks,
        currentIndex = safeIndex,
        currentTrack = tracks.getOrNull(safeIndex),
        isPlaying = false,
    )
}
```

6. 类 KDoc 追加：`传输层为 PlayerHandle（生产经 MediaController），ExoPlayer 唯一释放权在 Service。`

- [ ] **Step 3: 测试构造补实参 + 新增断言**

4 个测试文件把 `PlayerManager(fake, history, prefs, repo)` 改为 `PlayerManager(fake, history, prefs, repo, appContext)`，其中 `appContext` 取自各文件已有的 `ApplicationProvider.getApplicationContext<Context>()`（缺失则补 import `android.content.Context` 与 `androidx.test.core.app.ApplicationProvider`）。`PlayerManagerTest` 追加：

```kotlin
@Test
fun `play starts foreground playback service`() = runTest {
    playerManager.testScope = this
    val appContext = ApplicationProvider.getApplicationContext<Context>()
    playerManager.play(listOf(Track("BV1", 1, "t", "a", "", 1000, null)), 0)
    val started = org.robolectric.Shadows.shadowOf(appContext as android.app.Application)
        .startedServices
    assertTrue(started.any { it.component?.className?.endsWith("TingBiliPlaybackService") == true })
    playerManager.release()
}
```

- [ ] **Step 4: 跑受影响模块全部单测**

Run: `./gradlew :core:media:testDebugUnitTest :feature:home:testDebugUnitTest :feature:history:testDebugUnitTest :feature:playlist:testDebugUnitTest :feature:player:testDebugUnitTest 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL（全部 Fake 套件通过 + 1 个新断言通过）

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/java/cn/debubu/tingbili/core/media/PlayerManager.kt core/media/src/test/java/cn/debubu/tingbili/core/media/PlayerManagerTest.kt feature/home/src/test/java/cn/debubu/tingbili/feature/home/HomeViewModelTest.kt feature/history/src/test/java/cn/debubu/tingbili/feature/history/HistoryViewModelTest.kt feature/playlist/src/test/java/cn/debubu/tingbili/feature/playlist/PlaylistViewModelTest.kt
git commit -m "feat(media): PlayerManager starts foreground service with optimistic state"
```

### Task 6: 详情页播后导航

**Files:**
- Modify: `feature/detail/src/main/java/cn/debubu/tingbili/feature/detail/DetailScreen.kt`
- Modify: `app/src/main/java/cn/debubu/tingbili/navigation/AppNavHost.kt`

**Interfaces:**
- Consumes: Task 5 的 `play()` 语义
- Produces: “播后自动进播放页”行为——验收项。

- [ ] **Step 1: DetailScreen 加参数并调用**

`DetailScreen` 签名追加 `onPlayNavigate: () -> Unit = {}`；Success 分支改为：

```kotlin
DetailContent(
    video = s.video,
    tracks = s.tracks,
    onPlayAll = { viewModel.play(s.tracks, 0); onPlayNavigate() },
    onAddAll = { viewModel.showPlaylistPicker() },
    onPlayPage = { idx -> viewModel.play(s.tracks, idx); onPlayNavigate() }
)
```

- [ ] **Step 2: AppNavHost 传入导航**

`composable<VideoDetailRoute>` 改为：

```kotlin
DetailScreen(
    onBack = { navController.popBackStack() },
    onPlayNavigate = { navController.navigate(PlayerRoute) { launchSingleTop = true } }
)
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug :feature:detail:testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add feature/detail/src/main/java/cn/debubu/tingbili/feature/detail/DetailScreen.kt app/src/main/java/cn/debubu/tingbili/navigation/AppNavHost.kt
git commit -m "feat(detail): navigate to player after play"
```

### Task 7: MainActivity 建连 + 通知权限

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`（加 `POST_NOTIFICATIONS`）
- Modify: `app/src/main/java/cn/debubu/tingbili/MainActivity.kt`（注入连接并建连，Android 13+ 按需申请通知权限）

**Interfaces:**
- Consumes: Task 1 的 `connect()`
- Produces: 冷启动即建连的 Controller——后台与通知的前置条件。

- [ ] **Step 1: Manifest 加权限**

在 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 之后插入：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: MainActivity 建连与权限申请**

类内追加：

```kotlin
@Inject lateinit var playbackConnection: PlaybackConnection

private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    playbackConnection.connect()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    enableEdgeToEdge()
    setContent { TingBiliTheme { AdaptiveMainScaffold() } }
}
```

追加 import：`android.Manifest`、`android.content.pm.PackageManager`、`android.os.Build`、`androidx.activity.result.contract.ActivityResultContracts`、`androidx.core.content.ContextCompat`、`cn.debubu.tingbili.core.media.PlaybackConnection`、`javax.inject.Inject`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/cn/debubu/tingbili/MainActivity.kt
git commit -m "feat(app): connect media session at startup, request notification permission"
```

### Task 8: 全量验证与收尾

**Files:** 无新增，仅验证与修补。

- [ ] **Step 1: 全模块单测**

Run: `./gradlew testDebugUnitTest 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL，无失败用例

- [ ] **Step 2: 装机包构建**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 装机到已连设备并验收**

Run: `./gradlew installDebug 2>&1 | tail -5` 后按序验证：搜索→详情→播放全部（应自动进播放页且状态即时变化）→切后台/锁屏（通知栏可暂停）→拔耳机停播→杀进程无崩溃。任一失败回对应任务修补，不扩散改动。

- [ ] **Step 4: 最终 Commit（如有修补）**

```bash
git status -sb
git add -A  # 检查：gradle.properties 的本地 java.home 不得入内
git commit -m "fix(media): review fixes from device verification"
```

## Self-Review

- Spec 覆盖：后台保活→Task 3/5/7；通知/线控→Task 3/7（Media3 自动）；状态同步→Task 5/6；双 owner→Task 3/4；测试→Task 1/2/5/8。全覆盖。
- 占位扫描：无 TBD/TODO；所有步骤含完整代码与确切命令。
- 类型一致：`PlaybackConnection` 五个成员签名在 Task 1 冻结，Task 2/5 引用一致；`PlayerHandle` 接口全程未动；`PlayerManager` 新增的 `appContext` 位置在末尾，4 处调用点同步。
