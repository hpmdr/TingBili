# TingBili 修复计划（F1–F6 · 按批执行）

> 输入：`docs/REVIEW_REPORT.md`（100 个检查点 / 56 条问题 / P0 1 · P1 11 · P2 29 · P3 15）
> 口径：用户已确认「数据库允许破坏性更新」；性能只做回归修复，不重开 `PERF_AUDIT` 诊断。
> 约定：一批一提交（`fix/chore/docs(范围): 简述`）；每批开工前 `git rev-parse --short HEAD` 记锚点；每批结束必须过**门禁三件套**：
>
> ```bash
> ./gradlew assembleDebug --no-daemon --stacktrace
> ./gradlew testDebugUnitTest --no-daemon --continue
> ./gradlew lintDebug --no-daemon
> ```
>
> 涉及 UI 的批次另加真机回归（`uiautomator dump` 读控件树，见 `docs/CONVENTIONS.md` §7）。

---

## 0. 批次总览

| 批次 | 主题 | 解决问题 | 改动面 | 预估 | 前置 |
|---|---|---|---|---|---|
| **F0** | 基线记录 | 记录当前状态（APK/测试/lint 数字），避免"改完不知道好了多少" | 无代码 | 0.2d | — |
| **F1** | 门禁与工具链 | I-01 / I-02 / J-04 / J-10 / J-11 / A-01 + lint 105 条 | CI、gradle.properties、proguard、docs | 0.5d | F0 |
| **F2** | 稳定性与崩溃面 | D-08 / B-02 / G-12 / G-13 / G-09 | app、core:media、core:data | 1d | F1 |
| **F3** | 合规与安全 | F-07 / F-06 / H-01 / H-02 / A-02+J-08 / J-09 | manifest、feature:mine、feature:player、app | 1d | F1 |
| **F4** | 性能回归 | B-03 / C-14 / C-13 / E-07 / B-04 / C-01 / C-04 / C-02 | feature:player、feature:detail、app、core:data | 1.5d | F1 |
| **F5** | 架构与 UI 规范 | D-01 / D-07 / C-09 / C-12 / H-03 / H-05 / J-05 / C-10 / C-11 | core:data、全部 feature | 2d | F2、F4 |
| **F6** | 测试与文档收口 | I-03 / I-04 / I-05 / H-04 / H-06 / B-07 / J-03 / 文档漂移 | src/test、docs | 1d | F1–F5 |

**执行原则**
1. **先门禁后修复**：F1 必须先做，否则后面每一批都无法自动验证「没改坏」。
2. **每批独立可回滚**：一批一个提交；批内若超过 300 行改动，拆子提交（如 F1a 门禁 / F1b 规则）。
3. **P0/P1 先于一切**：F2 与 F3 里的 P1 若与 F4 冲突（同一文件），以 P1 为先，F4 顺延。
4. **不改需求**：任何涉及产品行为（方向锁、通知不可滑掉、i18n）的改动，先按 §5 的默认方案做，做完给用户看效果。

---

## F0 基线记录（0.2d）

**做**：把当前可量化状态写进 `docs/REVIEW_REPORT.md` 附录 A（已有 lint 数字），并记录：

```bash
Get-Item app/build/outputs/apk/debug/app-debug.apk, app/build/outputs/apk/perfDebug/app-perfDebug.apk
./gradlew testDebugUnitTest --no-daemon --continue   # 记录实际通过/总数
```

**验收**：基线四项都有数字：debug APK 体积 / perfDebug 体积 / 346 用例实际执行数 / lint 105 条（P0-78 @UnstableApi）。

---

## F1 门禁与工具链（0.5d，最高性价比）

**目标**：让「改坏了」在 CI 上自动变红。

### F1a 修 CI 测试回退（I-01）

- 改 `.github/workflows/ci.yml:62-63`：删掉 `A B || 全量`，改为
  `./gradlew testDebugUnitTest --no-daemon --continue`（全 13 模块，346 用例）。
- `timeout-minutes: 30 → 45`；失败时上传测试报告（`reports/tests/**`）。
- 风险：全量 Robolectric 可能 OOM/超时 → 用 `--continue` 保证跑完，必要时加 `maxParallelForks=1`（已在 5 处配 `maxHeapSize`）。
- 验收：CI 日志出现 13 个模块的 `testDebugUnitTest`；故意改坏一条断言能变红。

### F1b lint 门禁 + 清 78 条 @UnstableApi（I-02）

1. 在 `core:media`、`app` 中给使用 media3 非稳定 API 的类/文件加 `@OptIn(UnstableApi::class)`（Kotlin 用 `androidx.annotation.OptIn`）；**机械改动，改完必须重跑 lint 确认 78 → 0**。
2. 剩余 27 条逐条处理：`UseTomlInstead 7`（补 catalog 别名）、`DefaultLocale 4` / `NonObservableLocale 2`（补 Locale / `LocalLocale`）、`ModifierParameter 3`（补 `modifier`）、`ObsoleteSdkInt 2`（删）、`UseKtx 2`（换）、`NewerVersionAvailable 4`（记录不修）、`SetJavaScriptEnabled/DiscouragedApi/LockedOrientationActivity`（在 F3 一并处理，此处先进 baseline）。
3. 各模块 `android { lint { baseline = file("lint-baseline.xml") } }`（用 `updateLintBaseline` 生成，只保留确实要延后的项）；**新增代码不得新增告警**。
4. CI 加 `./gradlew lintDebug --no-daemon`（放在 assembleDebug 之后）。

- 验收：`lintDebug` 退出码 0；再故意写一处 `String.format("%d", n)` 能变红。

### F1c 构建配置（J-04 / J-10）

| 改动 | 位置 | 说明 |
|---|---|---|
| 删 `android.nonTransitiveRResources`（无效属性）、删 `android.suppressUnsupportedCompileSdk=37`（AGP 9.4.1 已支持 37） | `gradle.properties:7-8` | 死配置误导后人 |
| 加 `org.gradle.caching=true`、`org.gradle.parallel=true` | `gradle.properties` | 13 模块收益明显；开完跑一次全量确认无并发问题 |
| `daemon.idletimeout` 30s → 3h | 同上 | 与 `AGENTS.md`「日常保留 daemon」一致 |
| proguard 加 `-keepattributes SourceFile,LineNumberTable` | `app/proguard-rules.pro` | release 崩溃可定位，体积几乎不变 |
| release 上传 `mapping.txt` 为 artifact | `.github/workflows/release.yml` | 崩溃反混淆必需 |
| 暂不动 configuration cache | — | 需先删 `configureondemand`，且 AGP9+KSP 兼容性未验证，单独立项 |

- 验收：`assemblePerfDebug` 成功且 `configuration.txt` 命中 `SourceFile`；`./gradlew --status` 无异常。

### F1d 文档止血（J-11）

改 `docs/CONVENTIONS.md`（§1 版本表改「见 libs.versions.toml」、SDK 37、路由函数名、历史键语义、MVP 无登录、破坏性迁移新决策）、`docs/DEVELOPMENT.md`（模块清单补 detail/bili-ui）、`docs/ARCH_REVIEW.md`（文末追加现状，不改正文）。

- 验收：三份文档不再出现与代码冲突的硬编码版本/模块名。

---

## F2 稳定性与崩溃面（1d）

### F2a 三处主线程 `runBlocking`（D-08 / B-01，P0）

**方案**：预热 + 兜底，不改变「配置在构造期固定」的既有语义。
1. `TingBiliApp.onCreate`（或 `Application` 的注入完成后）起后台协程 `prefs.prime()`：内部 `data.first()`，把 DataStore 文件读+解析放到 IO 线程。
2. 三个调用点保留同步取值，但改成 `withTimeoutOrNull(50) { prefs.xxx.first() } ?: 默认值`（`runBlocking` 包 timeout），确保极端情况下也不长时间卡主线程。
3. 在 `PreferencesRepository` 给 `cacheMaxMb`/`audioOffload` 加 `@Volatile` 内存镜像，`prime()` 后由 collect 持续更新；DI 优先读镜像。

- 验收：Perfetto/Profiler 冷启动 trace 中 `main` 线程无 `DataStoreImpl.readDataOrAwait`；`am start -W` 冷启动 TotalTime 不劣化。
- 风险：镜像与真实值短暂不一致 → 只在构造期读一次，影响面可控，写进 KDoc。

### F2b 启动首帧门禁（B-02）

- `MainViewModel`：`startTab` 改为读 DataStore 新增键 `hasFavoriteEver: Boolean`（默认 false），不再首帧查 Room。
- `FavoritesRepository`：收藏写入成功时置位该键；首次进入收藏页若 DB 非空但键为 false，补写一次。
- 保留 `withTimeoutOrNull(3_000)` 兜底：偏好读失败仍落到默认 Tab。

- 验收：`am start -W` 首装/覆盖安装各 10 次，TotalTime 与「收藏 Tab 命中率」均正常。

### F2c FGS 启动时序（G-12，P1）

1. 先读 `PlayerManager.kt:640-780` 确认三处调用语义，再决定：
   - **首选**：删掉手动 `startForegroundService`（Media3 在 user-engaged 时自行起服务并 startForeground）；
   - 或：起服务后立刻 `startForeground`（带「准备中」占位通知），`awaitReady` 失败时 `stopSelf`。
2. `ContextCompat.startForegroundService` 全部包 `runCatching`，捕获 `ForegroundServiceStartNotAllowedException` 时降级为「前台可见时再起播」并提示用户。
3. 删 `PlaybackConnection.ensureServiceStarted`（死代码）。

- 验收：后台（App 不在前台）触发播放 10 次无崩溃/无进程被杀；logcat 无 `did not then call Service.startForeground`。
- 风险：Media3 自动起服务的行为依赖 `MediaController` 已连接 → 必须真机验。

### F2d MediaSession 补齐（G-13 / G-09）

- `TingBiliPlaybackService`：`MediaSession.Builder(...).setSessionActivity(PendingIntent.getActivity(...MainActivity, FLAG_IMMUTABLE))`。
- `PlaybackConnection`：`MediaController.Builder(...).buildAsync()` 后加 `MediaController.Listener.onDisconnected` → 置空 controller 并允许重建；`future.get()` 包 `runCatching`。

- 验收：播放中点通知/锁屏卡片能回到 App；`adb shell am force-stop` 后重进能重新播放（不需杀进程）。

---

## F3 合规与安全（1d）

### F3a 极验 WebView 加固（F-07，P1）

- `GeetestDialog.kt:83` `MIXED_CONTENT_ALWAYS_ALLOW` → `MIXED_CONTENT_COMPATIBILITY_MODE`（或 `NEVER_ALLOW`，先验证极验页是否依赖 http 子资源）。
- 加 `shouldOverrideUrlLoading` 白名单：仅 `passport.bilibili.com` / `*.bilibili.com` 允许，其余 `return true` 拦截。
- JS bridge：`addJavascriptInterface` 对象内对消息做「来源域 + 结构」校验；`:145-154` 的 success 回调加一个由 Kotlin 侧生成的一次性 token。
- `webView.destroy()` 收尾（避免泄漏）。

- 验收：**必须真机跑一次完整登录**（扫码/短信任选），成功且失败路径都能回落到「未登录」；期间无 crash。
- 风险：极验页面可能依赖混合内容或特定跳转 → 若登录失败，回退为「保留 MIXED_CONTENT_COMPATIBILITY_MODE + 白名单，仅加 token」，并在报告里记录取舍。

### F3b 备份策略（F-06，P2）

- `app/src/main/AndroidManifest.xml`：`android:allowBackup="false"`（凭证/DB 都可重建，且 Keystore 不随备份迁移）。
- 若想保留「换机迁移收藏」：改为 `dataExtractionRules` 排除 `sharedpref/tingbili_auth*` 与 `db`，只留偏好。**默认先全关，最简单**。

- 验收：`adb shell bmgr backupnow cn.debubu.tingbili` 提示无可备份数据 / 或规则文件生效。

### F3c 无障碍触控与语义（H-01 / H-02，P1）

- 播放进度条：外层 `.height(48.dp)` 承载 `pointerInput`，视觉线仍 3dp（`PlayerScreen.kt:1009-1012`）。
- `MeScreen:387` 34dp 明暗按钮 → 48dp 并加 `contentDescription`；`HomeScreen:534-536` 28dp、`MiniPlayerBar:288-291` 40dp 同理。
- 收藏按钮重复语义（H-04）：`onClickLabel` 保留、`Icon.contentDescription` 置 null。

- 验收：TalkBack 走查播放页/我的页；Layout Inspector 量触控区 ≥48dp。

### F3d 方向锁与大屏（A-02 / J-08，P2，**需用户确认**）

默认方案（二选一，等用户拍板）：
- **A（推荐）**：删除 `screenOrientation="portrait"`，补 `MainTabsScaffold` 手机横屏回归 + `LoginScreen`/`PlayerScreen` 横屏检查。符合 `docs/design/UI_DESIGN_REQUIREMENTS.md`「允许旋转」。
- **B**：保留 portrait，但在文档写明「API 36+ 在 sw≥600dp 会被系统忽略，届时走 Rail 分支」，并补平板回归。

同时补 `android:enableOnBackInvokedCallback="true"`（预测性返回）。

- 验收：横屏（开发者选项强制旋转）逐页无裁切/无黑条；返回手势动画正常。

### F3e 沉浸式 API 收敛（J-09，P2，**谨慎**）

- 删 `MainActivity.kt:121-129` 的 `systemUiVisibility/statusBarColor/navigationBarColor`，只保留 `enableEdgeToEdge()`。
- 风险：历史上 HyperOS 有黑条问题 → 改完必须真机逐页看状态栏/导航栏区域。
- 若复现黑条：保留代码但加注释说明「HyperOS workaround」，并在报告里标注为已知技术债。

---

## F4 性能回归（1.5d）

### F4a 播放页/平板壳重组（B-03 / C-14，P1）

- `PlayerScreen.kt:160-162`：`progUi`/`lyricUi` 下沉到 `PlaybackProgressBar`、`PlayerAuxBar`、`MiniLyricCard` 内部各自 `collectAsStateWithLifecycle`；顶层只留低频（track 信息）。
- 封面 URL 加 `remember(track.cover, imageFormat)`；`sleepLabel` 加 `remember(progUi.sleepRemainSec)`。
- `MainTabsScaffold.kt:159-186`：把 `player.state` 读取移到独立 `MiniPlayerSlot`（手机分支现成实现可直接复用）。
- `DetailScreen.kt:104` / `BvFavoriteScreen.kt:87` / `FavoriteFolderScreen.kt:77`：`playingTrack` 下沉到行组件，VM 侧 expose `focus`（已有）而非整表。

- 验收：Layout Inspector 重组计数——播放中 `PlayerScreen` ≈0/s、平板壳 ≈0/s；`dumpsys gfxinfo` janky frame 下降。

### F4b 索引与查询（E-07 / B-04，P2 但收益大）

- 因为允许破坏性更新：直接改 `@Entity` 加 `indices = [Index("bvid"), Index("updatedAt")]`（History）、`Index("bvid","cid")`（FavoriteTrack），DB version +1，走 `fallbackToDestructiveMigration` 自动重建，**不需要手写迁移**。
- `HistoryViewModel`：`SharingStarted.Eagerly → WhileSubscribed(5_000)`。
- `FavoriteDao.observeFolderSummaries`：把 JOIN History 的两列拆成懒查（或物化 `lastPlayedAt` 列）。

- 验收：Room `QueryCallback` 计时，播放期间每 10s 的查询条数与耗时下降；历史页/收藏页滚动无 10s 周期卡顿。

### F4c Compose 稳定性与派生（C-13 / C-04 / C-01 / C-02）

- 给 UI 状态类加 `@Immutable`（`Track`、`HistoryEntity`、`FavoriteTrackEntity`、`PlaybackState`、`TrackUiState` 等）。
- 开 `composeCompiler { reportsDestination = ... }` 生成一次报告，确认 skippable 比例并留档。
- `derivedStateOf`：`inMainTabs/showMiniPlayer`、`MainTabsScaffold.trackKey`、`MiniPlayerBar.dismissThreshold`。
- `SimpleDateFormat` 4 处 → `remember` 或静态 `DateTimeFormatter`。
- 网格 key 去 index：默认 `bvid:cid`，仅在同页重复时缀 index。

- 验收：编译器报告里 target 文件可跳过；播放中滚动帧率不劣化。

---

## F5 架构与 UI 规范（2d，可拆两次）

| 子批 | 内容 | 风险 |
|---|---|---|
| F5a | **仓储层**：`core:data` 新增 `HistoryRepository`/`HomeSnapshotRepository`/`FavoriteTrackRepository`；6 个 feature 改依赖仓储；`BiliHistoryViewModel` 改走 `BiliRepository` | 中（触面广，单测要跟着改） |
| F5b | Dispatcher 注入（21 处 → `@Named("io")`）；`HomeViewModel` 删 `launch(Dispatchers.IO)` | 低 |
| F5c | 登录页 8 处状态改 `rememberSaveable`；日期格式统一带 Locale | 低 |
| F5d | 硬编码颜色 49 行 + 字号 11 处 → M3 token / 品牌色进 `core:ui`；对比度阈值 0.5→0.4（H-03） | 中（需逐页视觉复核） |
| F5e | 固定高度 60/68dp → `heightIn(min=…)`（H-05）；字体 200% 回归 | 低 |
| F5f | 54 处硬编码依赖坐标 → 补 catalog 别名（J-05）；`core:ui` 的 `api(core:data)` 改 `implementation` | 低 |

- 验收：每子批 `assembleDebug` + 相关模块测试绿；F5a 后 `git grep "Dao" -- feature/*/src/main` 应为 0（ViewModel 层）。

---

## F6 测试与文档收口（1d）

- **I-03**：给 `DetailViewModel`、`SpaceViewModel`、`BvFavoriteViewModel` 各补 1–2 个纯逻辑用例（三态 + 错误映射）。
- **I-04**：`NavigationTest` 增加「`Routes.kt` 路由集 ⊇ 测试覆盖集」断言，防止新路由漏测。
- **I-05**：补 `feature/player/src/test/resources/robolectric.properties`；统一 7 个模块的 `sdk` 与 targetSdk 对齐（先确认 Robolectric 4.17 支持上限，不行就钉 36 并注释）。
- **H-06**：`docs` 写「中文单语」决策；4 处 `String.format` 补 Locale。
- **B-07 / J-03**：`:benchmark` + Baseline Profile 立独立任务（本计划不实施），先在文档留任务卡。
- **文档**：回写 `AGENTS.md`（新增规则：新实体必须带索引、新增 Screen 禁顶层 collect 高频流、release 日志需门控）、`CONVENTIONS.md` 完整更新。

- 验收：全量测试绿；`git status` 干净；文档与代码无已知冲突。

---

## 4. 全局验收与回滚

| 检查 | 命令/方式 | 通过标准 |
|---|---|---|
| 构建 | `./gradlew assembleDebug --no-daemon` | SUCCESS |
| 单测 | `./gradlew testDebugUnitTest --no-daemon --continue` | 全绿，且实际执行 346 用例（不再是 137） |
| lint | `./gradlew lintDebug --no-daemon` | 退出码 0（baseline 只允许存量） |
| 体积 | perfDebug APK | 不超过当前 6.53 MB + 5% |
| 真机 | 播放/暂停/切歌/锁屏/通知/断网/登录 | 无崩溃，行为与修复前一致或更好 |
| 数据 | 升级安装（旧版本→新版本） | 允许清库（用户已确认），但不得崩溃 |

**回滚**：每批一个提交，出问题 `git revert <commit>`；DB schema 变更走破坏性重建，回滚后需卸载重装（已接受）。

---

## 5. 需要你拍板的 3 件事（其余按默认方案执行）

| # | 事项 | 默认方案 | 备选 |
|---|---|---|---|
| 1 | **方向锁** | 删除 `screenOrientation="portrait"`，补横屏/大屏回归（符合现有设计文档） | 保留锁 + 文档说明 API 36+ 行为 |
| 2 | **极验 WebView 加固强度** | 混合内容降级 + 域名白名单 + bridge token（改完需你真机登录验证一次） | 只加白名单，不动混合内容 |
| 3 | **通知是否"不可滑掉"** | 维持框架默认（可滑掉），记为已知行为 | 自定义 `MediaNotification.Provider` 置 ongoing |

> 其余 53 条按批次直接执行；执行中若发现与报告不符的现状（代码已变），以代码为准并在批次日志里记录偏差。

---

## 6. 执行记录（随做随记）

| 批次 | 提交 | 状态 | 验收结果 |
|---|---|---|---|
| F0 | `bf2918f` | ✅ | 基线：debug APK 85.0 MB / perfDebug 6.53 MB / 活跃模块 352 个用例 / lint 105 条（80 Error，其中 78 条 media3 `@UnstableApi`） |
| F1 | `2a714cc` | ✅ | 见 §6.1 |
| F2 | `5f7e281` / `a1732f4` | ✅ | F2a/F2b/F2c/F2d 全部完成，见 §6.2；真机项待验 |
| F3 | `9cee8c6` / `63a04e6` | 🔄 部分完成 | F3a/F3b/F3c 完成（§6.3）；F3d 方向锁（待拍板）、F3e 沉浸式收敛待做 |
| F4 | `4d48250` | 🔄 部分完成 | F4b 索引与订阅完成（§6.4）；F4a 重组下沉、F4c 稳定性/派生状态待做 |
| F5 | — | ⏳ | |
| F6 | — | ⏳ | |

### 6.1 F1 实测结果（2026-11-01）

| 验收项 | 修前 | 修后 | 证据 |
|---|---|---|---|
| `./gradlew lintDebug` | **105 条**（80 Error + 25 Warning），且中断构建 | **0 条**，`LASTEXITCODE=0` | 13 个模块 lint 报告聚合为 0 |
| media3 `@UnstableApi` | 78 条 Error | 0（项目级 opt-in，官方支持写法） | `lint.xml` |
| 真违规 | UseTomlInstead 6 / DefaultLocale 4 / NonObservableLocale 2 / ModifierParameter 3 / ObsoleteSdkInt 2 / UseKtx 2 | 全部按规范修掉（不是压掉） | `git show 2a714cc` |
| 需保留的两处 | `SetJavaScriptEnabled`、`LockedOrientationActivity` + `DiscouragedApi` | 显式 `@SuppressLint` / `tools:ignore`，注释指向 F3 | `GeetestDialog.kt`、`AndroidManifest.xml` |
| 单测 | CI 只跑 19/50 文件、137/346 用例 | 13 模块全跑：**352 用例 0 失败** | `build/test-results/**/TEST-*.xml` 聚合 |
| CI 门禁 | 无 lint；测试回退语义永不触发 | 加 `testDebugUnitTest --continue` + `lintDebug` + 失败上传报告；超时 30→45min | `.github/workflows/ci.yml` |
| 构建 | `parallel=false`、无缓存、daemon 30s 自杀、无效属性 | `parallel=true` + `caching=true` + daemon 3h；删 `nonTransitiveRResources` 与 `suppressUnsupportedCompileSdk` | `gradle.properties`；本次构建 340 tasks / 38 from cache |
| R8 | 无行号、mapping 不归档 | `-keepattributes SourceFile,LineNumberTable`；release 上传 `mapping.txt`（90 天） | `proguard-rules.pro`、`release.yml` |
| 文档 | 三份文档三套版本口径、与代码冲突 | CONVENTIONS / DEVELOPMENT / AGENTS 已止血（版本以 toml 为准、SDK 37、历史模型、登录现状、破坏性迁移新决策） | `git show 2a714cc -- docs AGENTS.md` |

> 遗留（不在 F1 范围）：`feature:search` / `feature:playlist` 的本地 `build/` 残留会让测试计数多出 5 个旧用例，已删除本地目录（build/ 不入库）。

### 6.2 F2 实测结果（2026-11-01，部分完成）

| 子项 | 改法 | 验收 |
|---|---|---|
| F2a 三处主线程 `runBlocking`（P0 / D-08） | `PreferencesRepository` 增加 `@Volatile` 内存镜像 + `prime()` 预热；`TingBiliApp.onCreate` 在 IO 线程预热；`AudioCacheModule`/`ServicePlayerModule`/`Coil ImageLoader` 改读 `cacheMaxMbNow()` / `audioOffloadNow()`（镜像缺失才 80ms 有界兜底，读失败落默认） | 构建绿、lint 0、`PlayerManagerTest` 等 352 用例全绿；构造期不再无条件读盘 |
| F2c 前台服务时序（G-12） | 删除 3 处手动 `startForegroundService`（`play`/`rebuildTransport`/`PlaybackConnection.ensureServiceStarted` 死方法），改由 Media3 在真正起播时自行拉起并 `startForeground`；`PlayerManagerTest` 的旧断言改成锁新契约 | 单测锁定「play() 不再手动起服务」；真机后台起播待验证 |
| F2d MediaSession（G-13 / G-09） | `setSessionActivity(getLaunchIntentForPackage)`（点通知回 App，且不让 core:media 反向依赖 app）；`MediaController.Listener.onDisconnected` 清空引用并允许重连；`future.get()` 包 `runCatching` | 单测绿；点通知回 App、服务被杀后重连待真机 |
| F2b 启动首帧门禁（B-02） ✅ | 新增 DataStore `has_favorite_ever` 标记 + `FavoriteEverRecorder` 接口（仓储写路径置位；单测走两参次构造，8 处测试零改动）；`MainViewModel.resolveStartTab` 先读标记（300ms 预算），只有覆盖安装后首启无标记时回填一次（800ms，超时则不写避免写死 false） | 单测绿；首启仍有一次性回填，之后启动不再碰 Room |

### 6.3 F3 实测结果（2026-11-01，部分完成）

| 子项 | 改法 | 验收 |
|---|---|---|
| F3a 极验 WebView（F-07 / P1） | `MIXED_CONTENT_ALWAYS_ALLOW` → `COMPATIBILITY_MODE`；`allowFileAccess/allowContentAccess=false`；`shouldOverrideUrlLoading` 只放行 `*.bilibili.com`；JS 桥改为一次性消费（成功后丢弃后续消息） | 构建绿、lint 0；**真机登录待验**（极验流程对 UA/混合内容敏感，必须实测扫码/短信登录一次） |
| F3b 备份策略（F-06 / P2） | `allowBackup=false` + `fullBackupContent=false` + 新增 `res/xml/data_extraction_rules.xml`（云备份与 D2D 全量排除） | lint 的 `DataExtractionRules` 告警消除；`adb shell bmgr` 无可备份数据 |
| F3c 触控目标（H-02 / H-01） ✅ | 播放页进度条触控区 22dp → **48dp**（线仍画在顶部 5dp，视觉不变，多出高度由下方歌词 weight 吸收）；Me 页明暗切换 34dp → **48dp** 点击区（视觉圆仍 34dp）+ 补 `contentDescription`；mini 播放键 40 → 48dp；首页搜索历史删除键 28 → 48dp | 构建/lint/单测绿；**触控与视觉需真机复核** |
| F3d 方向锁（A-02） | **待你拍板**（删 portrait vs 保留 + 文档说明） | — |
| F3e 沉浸式 API 收敛（J-09） | **未做**（HyperOS 黑条有前科，改完必须真机逐页看） | — |

### 6.4 F4 部分结果（2026-11-01）

| 子项 | 改法 | 验收 |
|---|---|---|
| F4b 索引与订阅（E-07 / B-04 / P2） ✅ | `HistoryEntity` 补 `Index("bvid")`、`Index("updatedAt")`；`FavoriteTrackEntity` 补 `Index(value=["bvid","cid"])`；DB 15 → **16**（测试期允许破坏性升级，不写迁移）；`HistoryViewModel` 的 history/folderIds 从 `Eagerly` 改 `WhileSubscribed(5_000)` | 全量测试绿（含改过的 `HistoryViewModelTest`：WhileSubscribed 下首订阅会先拿初值，断言改 `first { it.size == 2 }`）；真机查询耗时待验 |
| F4a 播放页/平板壳重组（B-03 / C-14 / P1） ✅ | `PlayerScreen` 顶层去掉 `progUi`（1Hz）；新增 `ProgressBarSection` / `PlayerAuxBarSection` / `SleepTimerSheetSection` 三个叶子各自订阅；歌词跳转改 `vm.currentPositionMs()` 读瞬时值；`MainTabsScaffold` 把 2Hz `player.state` 下沉到 `TabletMiniPlayer` 子组件 | 构建/lint/352 单测绿；**重组计数需真机 Layout Inspector 复核** |
| F4c 稳定性与派生（C-13/C-04/C-01/C-02） 🔄 | 日期格式化 4 处 `SimpleDateFormat` → 静态 `DateTimeFormatter`（线程安全、无每次构造开销）；`MainActivity` 的 `inMainTabs`/`showMiniPlayer` 改 `remember(destination)`（原来每次重组都遍历 hierarchy + hasRoute） | 剩 `@Immutable`/稳定性配置与编译器报告、网格 key 去 index 未做 |

> 真机待验（设备已连接但处于锁屏/Doze，未擅自唤醒）：后台起播不被杀、点通知回到 App、`am force-stop` 后重进可重连。
