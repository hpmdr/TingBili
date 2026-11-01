# TingBili 规范与质量审查报告

> 基线：`master @ 2900f56`（2026-11-01）｜审查日期：2026-11-01｜审查方式：静态复核 + lint 全量扫描（未跑真机）
> 依据：`docs/CODE_REVIEW_PLAN.md` §4 的 100 个检查点（官方规范 88 + 本项目补充 12）+ 团队约定
> 材料：4 名核验员按类别逐条复核（`temp/review/SEC-*.md`，不入库），Lead 汇总去重、交叉核对矛盾结论
> 口径：用户已确认「数据库允许破坏性更新」，迁移类问题不作为缺陷；性能只做回归核对，不重开 `PERF_AUDIT` 诊断

---

## 0. 结论摘要

**100 个检查点结论**：合规 **36** ｜ 部分合规 **34** ｜ 违规 **23** ｜ 不适用 **1** ｜ 待真机验证 **6**（合计 **100**）

**问题清单**：**56** 条 —— P0 **1** ｜ P1 **11** ｜ P2 **29** ｜ P3 **15**。合并同源项后 P0/P1 共 **11 条**（§0.1）。

**结论分布的含义**：36 条完全合规说明「基本盘没问题」（Media3 后台播放与音频焦点、组件导出、协程测试确定性、版本目录无死条目、feature 互不依赖、R8 规则、16KB page 等）；34 条部分合规 + 23 条违规集中在**门禁、性能回归、架构分层、Compose 稳定性**四处；安全面只有 1 条 P1（WebView）。

### 0.1 必须先修的 11 条（P0/P1）

| # | 级别 | 问题 | 位置 | 成本 |
|---|---|---|---|---|
| 1 | **P0** | 三处 `runBlocking` 读 DataStore 首值，落在主线程/DI 构造路径（首图、SimpleCache、ExoPlayer） | `app/TingBiliApp.kt:30`、`core/media/AudioCacheModule.kt:38`、`core/media/ServicePlayerModule.kt:82` | M |
| 2 | **P1** | CI 成功路径只执行 19/50 个测试文件（137/346 用例），`||` 回退没有覆盖语义 | `.github/workflows/ci.yml:62-63` | S |
| 3 | **P1** | 无 lint/detekt/ktlint 门禁；lint 首次全量跑出 **105 条**且直接中断构建 | `ci.yml` + 根 `build.gradle.kts` | S |
| 4 | **P1** | 播放页 1Hz、平板壳 2Hz 整页/整壳重组，10-23「拆三层＋槽位隔离」在生产路径被抵消 | `feature/player/PlayerScreen.kt:160-162`、`app/navigation/MainTabsScaffold.kt:159-162` | S |
| 5 | **P1** | 启动首帧门禁从「读一个偏好」变成「Room 首查 + 加密 Prefs 同步读」，首装/升级最坏等 3s | `app/navigation/MainViewModel.kt:51-57`、`MainActivity.kt:102-105` | S |
| 6 | **P1** | 6 个 feature ViewModel 直连 Room DAO、1 个直连 `BiliApi`，仓储层只有 2 个 | `DetailViewModel:62`、`HomeViewModel:38`、`BiliHistoryScreen:35-37` 等 | M |
| 7 | **P1** | 全仓 0 处 `@Immutable/@Stable`、0 处 `derivedStateOf`、无稳定性配置/编译器报告 | 全仓（14 处 List/Map 状态字段） | S |
| 8 | **P1** | 先 `startForegroundService` 后起播：5s 内没 `startForeground` 会被系统杀进程，且 1 处裸调用 | `core/media/PlayerManager.kt:665,743,749-752` | S |
| 9 | **P1** | 极验 WebView 放行混合内容 + JS 桥无域名校验 | `feature/mine/GeetestDialog.kt:83,101-106` | M |
| 10 | **P1** | 触控目标 <48dp：播放进度条触控高 22dp，另有 28/34/40dp 小控件 | `PlayerScreen.kt:1009-1012`、`MeScreen:387`、`HomeScreen:534-536`、`MiniPlayerBar:288-291` | S |
| 11 | **P2→** | 历史表改复合主键后 bvid/updatedAt 索引丢失，收藏表缺 (bvid,cid)，播放中每 10s 全表重查 | `core/data/db/HistoryDao.kt:20`、`FavoriteEntities.kt:33` | M |

> 第 11 条虽标 P2，但它是「播放中周期性卡顿」的根因之一，建议与 P1 同批处理。

### 0.2 五条系统性结论（比单点 bug 更重要）

1. **规范体检从未跑过**：lint 一上手就是 105 条（80 Error），78 条是 media3 `@UnstableApi` 未标注，且 `lintDebug` 会在 `core:media` 直接中断构建。说明不是「配置缺失」，是「没人跑」。
2. **门禁是半失效的**：CI 的 `A B || 全量` 回退永不触发，31 个测试文件（含 `NavigationTest` 17 例）从不执行；`lint` 只在打 release tag 时隐式跑 `lintVitalRelease`。测试「绿」不代表全绿。
3. **10-23 那轮性能整改在生产路径上被两处改回去了**：播放页把三条流又提到顶层 collect；平板分支留在同一 restartable 作用域里读 2Hz tick。加 `@Immutable` 缺失与 `derivedStateOf` 0 处，构成「父级 tick 放大器」。
4. **架构债集中在「没建仓储层」**：`core:data` 只有 `FavoritesRepository`/`PreferencesRepository` 两个仓储，于是 6 个 feature 直接注 DAO、1 个 feature 直接注 `BiliApi`，统一出口（412 降级、错误映射）被绕过。
5. **合规面整体不错，但平台行为变更漏了两处**：Media3 后台播放/音频焦点/notify 释放基本合规；漏的是 `startForeground` 时序（FGS 5s 规则）与 API 36+ 大屏忽略方向锁（`screenOrientation="portrait"` 与设计文档「允许旋转」直接冲突）。

### 0.3 三处需要你知道的「结论修正」（核验时推翻了侦察推断）

| 侦察阶段说法 | 核验结论 | 证据 |
|---|---|---|
| `compileSdk 37` 超出 AGP 9.4.1 支持面 | **不成立**：AGP 9.4.1 内 `ToolsRevisionUtils` 为 `AndroidVersion(37,2)`，37 受支持；`suppressUnsupportedCompileSdk` 只是冗余 | 反编译 AGP jar |
| 「4 处 `startForegroundService` 无 try/catch」 | **不准确**：共 3 处调用，`:743` 被上层 catch、`PlaybackConnection:47` 是死代码；真风险是「先起服务后起播」的 5s 时序 + `:665` 裸调用 | `PlayerManager.kt:657-702` |
| 「小控件用 480 档图浪费 10 倍内存」 | **前提不成立**：Coil3 按布局约束解码，内存不浪费；浪费在下载量与磁盘缓存 | `ImageUrl.kt` + Coil3 行为 |

---

## 1. 审查范围与方法

| 项 | 说明 |
|---|---|
| 覆盖 | 100 个检查点（A 功能 9 / B 性能 10 / C Compose 14 / D 架构 10 / E 数据 8 / F 安全 8 / G 媒体 13 / H 无障碍 8 / I 测试 9 / J 构建 11） |
| 代码范围 | 13 个模块、151 个主源码文件 / 21,983 行（`app` + `core:*` + `data:bilibili` + `feature:*`） |
| 静态手段 | `git ls-files` / `git grep` / 精读关键文件 / 反编译 AGP jar 取版本支持面 / `aapt2 dump` 核对合并 manifest |
| 动态手段 | `./gradlew lintDebug --continue`（全 13 模块，105 条）；未跑单测、未装机（真机项见 §6） |
| 未覆盖 | 真机行为（冷启动耗时、后台 FGS、TalkBack、字体 200%、平板）、编译器重组报告、跨度量化的 SQL 计时 |

---

## 2. 严重度与可信度

| 级别 | 定义 | 本轮条数 |
|---|---|---|
| P0 | 崩溃 / 数据丢失 / 凭据泄露 / 必然 ANR | **1** |
| P1 | 明确缺陷、门禁失效、平台合规红线 | **11** |
| P2 | 规范偏离、可维护性、性能浪费（非用户可感知） | **29** |
| P3 | 风格、建议、文档 | **15** |

**可信度分级**（每条问题在 §4 明细里都有证据，未标注默认「静态确认」）：

- **静态确认**：代码/配置/产物直接可见，无需运行。
- **计算确认**：对比度等由色值算出（未上机渲染）。
- **待真机**：需设备或工具才能定论，统一收在 §6，不进结论表。
- **框架默认**：由 Media3/Compose 框架保证的行为（如 `onTaskRemoved` 默认语义、`AsyncImage` 尺寸解析），不作为缺陷。

---

## 3. 关键问题（P0/P1）定位索引

见 §0.1。逐条证据与修法在 §4 的 `D-08 / B-01 / B-02 / B-03 / C-13 / C-14 / D-01 / F-07 / G-12 / H-02 / I-01 / I-02` 条目。

> 合并说明：`D-08`（Compose/架构组）与 `B-01`（性能组）是同一问题，以 `D-08` 为准；`C-14`（4 个 Screen 顶层 collect）与 `B-03`（播放页/平板壳重组）同源，以 `B-03` 为主、`C-14` 补其余 3 个 Screen。

---

## 4. 问题明细（按严重度排序）

> 同源重复项：`D-08`≡`B-01`（同一处三行代码，两个组各报一次）、`C-14`⊂`B-03`（播放页同一处，`C-14` 额外覆盖 3 个二级页）。为便于按类别检索，两条都保留。

### [P0][D-08] 主线程 runBlocking 读 DataStore 首值（3 处，启动/首播路径）
位置：app/src/main/java/cn/debubu/tingbili/TingBiliApp.kt:30；core/media/src/main/java/cn/debubu/tingbili/core/media/AudioCacheModule.kt:38；core/media/src/main/java/cn/debubu/tingbili/core/media/ServicePlayerModule.kt:82
证据：`runBlocking { prefs.cacheMaxMb.first() }` ×2、`runBlocking { prefs.audioOffload.first() }` ×1；前者在 Coil `newImageLoader`（主线程首图回调）、后者在 ServiceComponent 的 SimpleCache/ExoPlayer 供给链（Service.onCreate 主线程）
规范：D8 / S7（main-safe）＋ B4 基线 P0
影响：冷启动首图、首次起播各多一次主线程磁盘读（弱机数十 ms）；偶发首帧掉帧/启动 ANR 风险，是调度抖动最敏感的位置
建议：启动时后台协程读一次写入内存缓存，三处改用该缓存 + 缺省值兜底，禁止构造期同步取首值
成本：M

### [P1][B-01] 三处 runBlocking 在读 DataStore 首值，落在主线程/DI 构造路径
位置：app/src/main/java/cn/debubu/tingbili/TingBiliApp.kt:30；core/media/src/main/java/cn/debubu/tingbili/core/media/AudioCacheModule.kt:38；core/media/src/main/java/cn/debubu/tingbili/core/media/ServicePlayerModule.kt:82
证据：三处 `runBlocking { prefs.xxx.first() }`；ServicePlayerModule:81 注释自认「DataStore 首读走磁盘（约数十毫秒）」；冷启动 `MainActivity.kt:105` connect() → 绑定 Service → 构造 ExoPlayer 即命中该行
规范：S1 Stability_ANR、S7 main-safe（B4）
影响：首图（TingBiliApp）/起播（AudioCache、ServicePlayer）各一次主线程阻塞，慢存储或与 DB 首开并发时逼近 ANR；实测耗时代验证
建议：给 cacheMaxMb/audioOffload 维护 @Volatile 内存镜像，DI 读镜像不读盘
成本：M

### [P1][B-02] 启动首帧门禁从「偏好读」变成「Room 首查」，且 create 链本身有主线程 IO
位置：app/src/main/java/cn/debubu/tingbili/navigation/MainViewModel.kt:51-57；app/src/main/java/cn/debubu/tingbili/MainActivity.kt:102-105；data/bilibili/src/main/java/cn/debubu/tingbili/data/bilibili/auth/AuthRepository.kt:55
证据：`withTimeoutOrNull(3_000){ favoritesRepository.hasAnyFavorite() }`；`setKeepOnScreenCondition { startTab.value == null }`；首装/升级需 openDatabase（v15），最坏等 3s；`store.load()` 在 VM 构造期同步读 EncryptedSharedPreferences（Keystore 首读）
规范：S1 App_Startup_Time（B1）
影响：冷启动首帧延后、首装/大版本升级最明显；TotalTime 待 T-B1
建议：改用「上次是否有收藏」偏好布尔（写收藏时置位），首帧不再等 DB
成本：S

### [P1][B-03] 播放页与平板壳仍按 1~2Hz 整页/整壳重组，10-23「拆三层」在生产路径被抵消
位置：feature/player/src/main/java/cn/debubu/tingbili/feature/player/PlayerScreen.kt:160-162；app/src/main/java/cn/debubu/tingbili/navigation/MainTabsScaffold.kt:159-162,186
证据：progressUi 1Hz（`PlayerViewModel.kt:321-338`）在 PlayerScreen 顶层 collect；平板分支 state 读取与 `AppRootNavHost` 同一 restartable 作用域，tick 源 `HistoryTracker.kt:59` 500ms（播放中最高 2Hz）；手机分支已隔离（`BottomNavWithCenterPlayer.kt:129-161`）
规范：S4（B9）；`docs/PERF_AUDIT.md:463-464` 已销账「拆三层/槽位隔离」
影响：播放中播放页每秒整页 body 重跑（封面 URL 重拼、歌词卡、辅助栏、进度条），平板/≥600dp 每秒 2 次整树失效；低端机掉帧/耗电，属回归
建议：progUi 下沉到进度条/辅助栏各自 collect；平板分支复用 MiniPlayerOverlay
成本：S

### [P1][I-01] CI 成功路径只执行 19/50 个测试文件（137/346 用例），`||` 回退无覆盖语义
位置：.github/workflows/ci.yml:62-63
证据：`./gradlew :core:data:testDebugUnitTest :core:media:testDebugUnitTest --no-daemon --stacktrace || ./gradlew testDebugUnitTest ...`；前两条全绿时 `||` 不执行；失败时回退也在同一模块失败 → 红仍是红；app/data:bilibili/core:ui/feature:* 共 31 文件/209 用例永不在绿路径跑
规范：S12（I3）
影响：门禁「绿」不代表全绿，31 个测试文件（含 NavigationTest 17 例）从不执行；成本 S
建议：改成 `./gradlew testDebugUnitTest --continue`（或按模块矩阵并行），保留 `compileDebugUnitTestKotlin` 门
成本：S

### [P1][I-02] 无 lint/detekt/ktlint 门禁（I4 的 lint 缺失同根）
位置：.github/workflows/ci.yml:55-63；build.gradle.kts（根，无 lint/detekt 配置）
证据：`git grep -n "lint" -- "*.kts" ".github/workflows/*.yml"` 0 命中；仓库无 detekt.yml/.editorconfig/lint-baseline；`lintVitalRelease` 只在打 release 时隐式跑
规范：S18/S20（I9）、S12（I4）
影响：DefaultLocale、`!!`、targetSdk 37 行为、Manifest 权限等规范类问题无自动拦截，整改后会重新腐化；成本 S
建议：CI 加 `./gradlew lint` 并导入 baseline（先用 Lead 本轮 lint 结果）
成本：S

### [P1][D-01] feature 层 6 个 ViewModel 直连 Room DAO、1 个直连 BiliApi
位置：DetailViewModel.kt:62、BvFavoriteViewModel.kt:46、FavoriteFolderViewModel.kt:43、HistoryViewModel.kt:29、HomeViewModel.kt:38、MeViewModel.kt:30、BiliHistoryScreen.kt:35-37
证据：构造参数直接是 `HistoryDao`/`HomeSnapshotDao`/`BiliApi`；core:data 只有 FavoritesRepository/PreferencesRepository 两个仓储
规范：D1 / S6（Layered architecture）＋ CONVENTIONS.md:31「网络出口统一 BiliRepository」
影响：DAO 列名、查询语义、B 站 412 风控降级（只在 BiliRepository/AuthRepository 内实现）会绕过统一出口直抵 UI 层；换存储成本扩散到 6 个 feature
建议：core:data 补 HistoryRepository/HomeSnapshotRepository；BiliHistoryViewModel 改调 BiliRepository
成本：M

### [P1][C-14] 4 个 Screen 在顶层 collect 高频流，播放中整页 body 反复重跑
位置：feature/player/.../PlayerScreen.kt:161；feature/detail/.../DetailScreen.kt:104；feature/favorites/.../BvFavoriteScreen.kt:87；feature/favorites/.../FavoriteFolderScreen.kt:77；app/.../MainTabsScaffold.kt:162
证据：源头 2Hz（HistoryTracker.kt:59/110 500ms → PlayerManager.kt:157-166 onTick），PlayerScreen 消费的 progressUi 经 `sample(1_000)`；DetailScreen:104 的 playingTrack 实际只用在 :429 的 item 内
规范：C14 / S4
影响：播放中详情/收藏夹/播放页每秒全页重组一次（平板壳 2 次），叠加 C-01 的 SDF 与 C-13 的 List 参数，是滚动掉帧与耗电的放大器
建议：把 playingTrack/进度下沉到行/进度条子组件内 collect；VM 侧只 expose「当前行 key」（`focus` 已是该语义，直接复用）
成本：M

### [P1][C-13] 全仓 0 处 @Immutable/@Stable、0 处 stability 配置、无编译器报告
位置：全仓（`@Immutable|@Stable` 0 命中）；core/media/.../PlaybackState.kt:11；feature/player/.../PlayerViewModel.kt（9 个 List/Map 状态字段）
证据：`git grep -c "@Immutable|@Stable"` = 0；stabilityConfigurationFile = 0；reportsDestination = 0；List/Map/Set 字段 14 处 / 5 文件
规范：C13 / S4（稳定性与可跳过性）
影响：带 List 的状态类（queue/lyrics/tracks）被 Compose 判为不稳定，每次 combine 生成新实例即 equals 不等 → 跳过失败；且无显式契约，重构时稳定性会无声回退
建议：UI 状态类加 `@Immutable`；开 `composeCompiler { reportsDestination }` 生成基线，再决定是否配 stabilityConfigurationFile 把 List/Map 声明稳定
成本：S

### [P1][F-07] 极验 WebView 放行混合内容、JS 桥无域名白名单
位置：`feature/mine/src/main/java/cn/debubu/tingbili/feature/mine/GeetestDialog.kt:83,101-107,145-154`
证据：`:83` `settings.mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW`；`:101-106` `addJavascriptInterface(GeeBridge, "Android")`；全仓 `shouldOverrideUrlLoading` 0 命中；`:107` `loadDataWithBaseURL("https://passport.bilibili.com", ...)`；桥只按字符串前缀分发（`:145-154`）
规范：S8（WebView 安全：限制加载域、不滥用 JS 桥、禁止混合内容）
影响：登录验证页可加载 HTTP 子资源 → 中间人注入 JS 后可直接调 `Android.onMessage("success:{...}")` 伪造验证结果，劫持/阻断登录流程；桥面暴露给一切被导航到的页面
建议：改 `MIXED_CONTENT_NEVER_ALLOW`，并在 WebViewClient 里对 `shouldOverrideUrlLoading` 做 `passport.bilibili.com / static.geetest.com` 白名单（非白名单 return true 拦截）
成本：S

### [P1][G-12] 先 startForegroundService 后起播：5s startForeground 期限杀进程，且 1 处裸调用
位置：`core/media/src/main/java/cn/debubu/tingbili/core/media/PlayerManager.kt:665`、`:743`、`:749-752`、`:669`；`PlaybackConnection.kt:47`
证据：play() 在 :743 起前台服务，真正 startForeground 由 Media3 在"user-engaged"后才做（`MediaNotificationManager#isAnySessionUserEngaged`：playWhenReady && READY/BUFFERING）。中间夹着 :749 awaitReady（自身 5s 超时就 return 错误）、:784 resolveCid 网络调用、:788 DataStore 读。rebuildTransport :665 的调用点无 try/catch（:657-702 整段不包异常）。
规范：S15（FGS 类型与启动限制）、S13
影响：awaitReady 超时或网络卡住时，服务已起但永不 startForeground→系统 "did not then call Service.startForeground" 杀进程（后台路径另可能抛 ForegroundServiceStartNotAllowedException 直接崩）。用户可感知的偶发闪退，难定位。
建议：删掉手动 startForegroundService（Media3 起播时会自行 startForegroundService+startForeground，删 `PlaybackConnection.ensureServiceStarted` 这个死方法），失败路径别把服务留在"已起未前台"；否则至少给 :665 包 try/catch 并在失败时 stopSelf。
成本：S

### [P1][H-02] 播放页进度条触控区 22dp，另有 28/34/40dp 小控件
位置：`feature/player/.../PlayerScreen.kt:1009-1012`；`feature/mine/.../MeScreen.kt:387`；`feature/home/.../HomeScreen.kt:534-536`；`app/.../navigation/MiniPlayerBar.kt:288-291`
证据：进度条 `.height(22.dp).pointerInput{ awaitEachGesture ... }`（注释自认"用 22dp 触控区"）；MeScreen `.size(34.dp).clickable`；HomeScreen `IconButton(modifier = Modifier.size(28.dp))`；MiniPlayerBar `FilledTonalIconButton(modifier = Modifier.size(40.dp))`。
规范：S10（触控目标 ≥48dp）、S1 Touch_Target_Size
影响：进度条是拖拽/点按定位的高频操作，22dp 高在单手/走动场景很难命中（TalkBack 可调但触屏不可）；小按钮误触率上升。无障碍红线。
建议：进度条外层改 `.height(48.dp)` 并把 3dp 线画在顶部（视觉不变）；4 个小按钮统一 48dp 或把 `minimumInteractiveComponentSize()` 放到入参 modifier 之前。
成本：S

### [P2][B-04] 历史/收藏查询索引退化，且失效范围大于查询所需
位置：core/data/src/main/java/cn/debubu/tingbili/core/data/db/HistoryDao.kt:20,52-54,63-68,71-74；core/data/src/main/java/cn/debubu/tingbili/core/data/db/FavoriteEntities.kt:33；core/data/src/main/java/cn/debubu/tingbili/core/data/db/FavoriteDao.kt:33-61,92-96；feature/history/src/main/java/cn/debubu/tingbili/feature/history/HistoryViewModel.kt:36-42
证据：HistoryEntity PK 仅 (sourceType,sourceId)，按 bvid/updatedAt 的过滤与 ORDER BY 无索引；FavoriteTrackEntity 只有 folderId 索引，`WHERE bvid=? AND cid=?` 全表扫；播放中每 10s 写历史（`HistoryTracker.kt:148`）→ observeAll/observeFolderSummaries（JOIN HistoryEntity）全表重查；HistoryViewModel 用 `SharingStarted.Eagerly`
规范：S21（B10）；`docs/PERF_AUDIT.md:370,462` 声称「DAO 按需化」已销账
影响：历史/收藏行数涨到数百后，播放期间每 10s 各跑全表扫描 + 主线程物化 List；实际耗时待 T-B10
建议：HistoryEntity 加 Index("bvid")/Index("updatedAt")、FavoriteTrackEntity 加 Index("bvid","cid")；Eagerly→WhileSubscribed(5000)
成本：M

### [P2][I-03] 两个 feature 模块与 core:bili-ui 零单测，favorites VM 无测试
位置：feature/detail（2 源/0 测试）、feature/space（4/0）、core:bili-ui（1/0）；feature/favorites/src/test/.../BvRefreshFailureTest.kt（仅错误映射）
证据：`git ls-files "*/src/test/*.kt"` 50 个文件，模块分布见文首；DetailUiState/RelatedUiState（`DetailViewModel.kt:29,38`）与 SpaceViewModel 状态机无回归网
规范：S12（I1）
影响：三态/错误映射/分页逻辑改动零保护；成本 S
建议：给 DetailViewModel/SpaceViewModel 各补 1 个纯逻辑用例（三态+错误映射）
成本：S

### [P2][I-04] NavigationTest 只覆盖 11/21 路由，且自建 NavHost 不测真实图
位置：app/src/test/java/cn/debubu/tingbili/navigation/NavigationTest.kt:245-339；app/src/main/java/cn/debubu/tingbili/navigation/Routes.kt（21 个 @Serializable）
证据：每个路由用例都在测试内 `NavHost(startDestination=...)` 重新声明 composable，未引用 `AppRootNavHost`；未覆盖 MainTabs/Home/Favorites/History/Appearance/Player/UpSpace/VideoDetail/FavoriteFolder/BvFavorite
规范：S12（I5）、AGENTS「改路由必须同步改 NavigationTest」
影响：AppNavHost 漏注册/写错路由不会变红，「改路由同步改测试」只靠编译期类引用兜 11 条；成本 M
建议：冒烟测试改为导航真实 `AppRootNavHost`，或加「Routes.kt 路由集 ⊇ 测试覆盖集」断言
成本：M

### [P2][I-05] Robolectric 配置不一致：1 模块缺 properties、sdk=36 落后 targetSdk 37
位置：feature/player/src/test（无 robolectric.properties）；6 份 properties 均 `sdk=36`；app/src/test/.../NavigationTest.kt:31、LoginScreenTest.kt:23
证据：`git ls-files "*robolectric.properties*"` = 6（app/core:data/core:media/feature:{history,home,mine}）；`PlayerViewModelTest.kt:54,57` 用 RobolectricTestRunner 但无该文件 → 回落 targetSdk(value 37)；2 处 @Config(sdk=[34])
规范：S12（I8）
影响：新系统行为零覆盖；未配模块可能静默跑错 SDK 或直接失败；Robolectric 4.17 对 37 的支持待 T-I8
建议：补 feature/player/robolectric.properties，并把 7 处统一到一个受支持 sdk 与 targetSdk 对齐策略
成本：S

### [P2][A-01] compileSdk 37 未获 AGP 官方支持，靠 suppressUnsupportedCompileSdk 压制
位置：app/build.gradle.kts:25,93；gradle.properties:8
证据：`android.suppressUnsupportedCompileSdk=37`；AGP 9.4.1 未声明支持 compileSdk 37；CI `ci.yml:54` 手动安装 platforms;android-37.0
规范：S1 Target_SDK_Version（A6）
影响：构建/工具链对 37 的支持不受官方保证，升级 AGP 或新增 lint 检查时缺 API 37 数据；成本 S
建议：保留 suppress 但在 gradle.properties 加注释写明原因与移除条件
成本：S

### [P2][A-02] 锁 portrait 与产品设计文档冲突，API 36+ 大屏下方向锁失效
位置：app/src/main/AndroidManifest.xml:19；docs/design/UI_DESIGN_REQUIREMENTS.md:34-37；app/src/main/java/cn/debubu/tingbili/MainActivity.kt:181-187
证据：设计文档「应用允许旋转（未锁方向）…横屏约 2712×1220 必须可用」；manifest 锁 portrait；targetSdk 37 在 sw≥600dp 忽略方向限制，Rail 分支只在大屏可达
规范：S1 Display_State_Parity（A9）
影响：手机横屏/折叠屏体验与设计不符；LoginScreen(949 行)/PlayerScreen(1978 行) 的大屏布局未验证；布局实况待 T-A9
建议：放开方向并补大屏回归，或在文档写明「锁竖屏」的产品理由与 API 36+ 行为预期
成本：M

### [P2][A-03] 通知渠道由 Media3 默认决定，通知权限被拒后无降级/引导
位置：core/media/src/main/java/cn/debubu/tingbili/core/media/TingBiliPlaybackService.kt:96；app/src/main/java/cn/debubu/tingbili/MainActivity.kt:88-89,108-117；app/src/main/AndroidManifest.xml:8
证据：全仓 0 处 NotificationChannel/MediaNotificationProvider/notify()；`registerForActivityResult(RequestPermission()){ }` 空实现，拒绝后无提示也无二次引导
规范：S1 Notification_Quality（A8）
影响：渠道名/重要级不可控；拒绝通知后媒体通知与锁屏/耳机控制静默消失，用户会当成播放坏了；常驻/锁屏可控待 T-A8
建议：用 setMediaNotificationProvider 显式定义渠道名与重要级；权限拒绝时给一次性提示
成本：S

### [P2][A-04] 唯一预发布依赖 security-crypto 1.1.0-alpha06，无升级/淘汰路径
位置：gradle/libs.versions.toml:28；app/src/main/java/cn/debubu/tingbili/di/EncryptedAuthStore.kt:24-32
证据：全库仅此一处 alpha/beta/rc；注释只说明「MasterKey 单数 API 需 1.1.0+」；docs 无该决策与迁移计划
规范：S1 SDK_Maintenance（A7）
影响：长期 alpha（上游 EncryptedSharedPreferences 维护状态未核实）；换签名/换机恢复时静默登出且无提示；成本 M
建议：把「保持 alpha06 + 读失败即未登录」写进文档，并给出迁移到 Keystore 自管理的触发条件
成本：S

### [P2][B-05] 图片档位与请求未按控件尺寸约束，小控件吃大图
位置：data/bilibili/src/main/java/cn/debubu/tingbili/data/bilibili/dto/ImageUrl.kt:8-15；app/src/main/java/cn/debubu/tingbili/navigation/MiniPlayerBar.kt:135-137,241；history 行 `HistoryScreen.kt:155-157`
证据：仅 ListThumb(480×270)/LargeCover(960×540)/Avatar(96×96) 三档；42dp mini 条与 96dp 历史行都用 ListThumb；16 处 AsyncImage 无 ImageRequest/size/placeholder/crossfade
规范：S2 内存章节（B5）
影响：Coil3 按布局约束解码 → 内存不 OOM；真实浪费在下载字节与磁盘缓存（同一图按档各存一份），弱网/首屏更明显；流量对比待 T-B5
建议：mini/历史行改用 Avatar(96) 或新增 232×144 档
成本：S

### [P2][B-06] StrictMode 未启用，主线程 IO 无红框可抓
位置：全仓 0 命中（app/src/main/java/cn/debubu/tingbili/TingBiliApp.kt、MainActivity.kt 均未配置）
证据：`git grep -n "StrictMode" -- "*.kt"` 空
规范：S1 StrictMode_Compliance（B3）
影响：B-01/B-02 与其它主线程 IO 无法在 debug 下自动暴露，只能靠 Perfetto 逐个抓；成本 S
建议：debug 变体 Application.onCreate 开 enableDefaults + penaltyLog
成本：S

### [P2][C-02] 17 处 items 全部有 key，但 5 处 key 里拼了 index
位置：feature/home/.../HomeScreen.kt:336、:570-571；feature/space/.../SpaceScreen.kt:203；feature/player/.../PlayerScreen.kt:1350、:1503
证据：`key = { index, track -> "${track.bvid}:${track.cid}#$index" }`；`key = { idx, line -> "$idx-${line.timeMs}" }`
规范：C2 / S4 lazy keys、S27（key 需可 Bundle 保存）
影响：刷新/翻页插入时 key 全体位移 → item 状态与复用失效（首帧布局成本、展开/动画状态丢失）；注释显示是为「页内重复 BV」兜底，属有意识取舍但代价未测
建议：默认用 bvid:cid，仅在「该页确有重复」时缀 index（先用分组判断重复集合）
成本：S

### [P2][C-09] 登录页 8 处输入/选择状态只用了 remember，转屏即丢
位置：feature/mine/.../LoginScreen.kt:127、:667-669、:791-793、:860；feature/mine/.../GeneralSettingsScreen.kt:187
证据：`rememberSaveable` 12 处 vs `remember { mutable*StateOf }` 55 处；上述全部是 `remember`
规范：C9 / S26、S27
影响：转屏/折叠屏展开/进程重建后已输入的手机号、验证码、密码、Cookie 全清空，用户需重输（登录路径最伤）
建议：输入类与 tab 状态统一改 `rememberSaveable`（HomeScreen:110 的搜索词已是正确示范）
成本：S

### [P2][C-01] 4 处 SimpleDateFormat 在组合体内每帧 new
位置：feature/favorites/.../FavoriteCards.kt:335-339（调用 :131、:223）、BvFavoriteScreen.kt:477（:376）、feature/detail/.../DetailScreen.kt:673、feature/space/.../SpaceScreen.kt:660（:609）
证据：`SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))` 每次调用新建；HistoryScreen.kt:336 已用 remember 的正确写法
规范：C1 / S4（remember keys）
影响：单次 ~10-20µs，但与 C-14 的 1Hz 整页 ×20 卡片、Space 列表滚动帧叠加后变成每帧固定开销
建议：提成 `remember(key){ … }` 或换线程安全的 `DateTimeFormatter` 静态实例
成本：S

### [P2][C-04] derivedStateOf 全仓 0 处，壳层派生值每次重组重算
位置：MainActivity.kt:190-195、app/.../MainTabsScaffold.kt:164、feature/home/.../HomeScreen.kt:146、app/.../MiniPlayerBar.kt:177
证据：`hierarchy.any{ hasRoute }` 每次重组分配；`"${it.bvid}:${it.cid}"` 每 tick 拼串；`max(72f, barHeightPx*…)` 每 tick 算
规范：C4 / S4
影响：单独看是 μs 级，但壳层位于所有页面上方，是 C-14 的放大器；缺位会让后续新增派生逻辑直接写进组合体
建议：至少给 `inMainTabs/showMiniPlayer` 与 `trackKey` 加 `derivedStateOf`
成本：S

### [P2][C-12] feature 层 49 行硬编码颜色 + 11 处数值字号，绕过 M3 token
位置：feature/mine/.../MeScreen.kt（24 行）、LoginScreen.kt（11 行）、feature/detail/.../DetailScreen.kt（10 行：`Color.Gray`×8、`Color.White`×1）、HomeScreen.kt/SpaceScreen.kt 各 2；字号：MeScreen 6、SpaceScreen 3、QualityScreen 1、PlayerScreen 1
证据：主源 `Color(0x)` 49 行 + `Color.Black/White/Gray` 32 行；`fontSize = N.sp` 11 处
规范：C12 / S19、S1（Theme Support）
影响：`Color.Gray` 与字面色在深色/AMOLED 下不随主题（对比度风险，牵连 H3）；登录页错误色与 M3 error 双轨
建议：feature 层统一换 `MaterialTheme.colorScheme.*`/typography；确需特殊色（品牌粉）进 core:ui token
成本：M

### [P2][D-07] 21 处硬编码 Dispatcher，仅 1 处可注入
位置：data/bilibili/.../auth/AuthRepository.kt（10 处）、app/.../cache/AppCacheManager.kt（5 处）、core/media/.../PlayerManager.kt:80、TingBiliPlaybackService.kt:32、feature/home/.../HomeViewModel.kt:117/179
证据：`Dispatchers.` 共 23 行命中，其中 21 行不可注入；可注入仅 PlayerViewModel.kt:92 + PlayerModule.kt:20（`@Named("subtitleIo")`）
规范：D7 / S7（注入 Dispatcher）
影响：仓储/VM 单测无法切 TestDispatcher，只能靠真实 IO 或 sleep；HomeViewModel 在 VM 内 `launch(IO)` 破坏了 main-safe 约定
建议：沿用 `@Named("ioDispatcher")` 先例在数据层构造注入；VM 里去掉 `launch(Dispatchers.IO)`
成本：M

### [P2][F-06] 无备份策略：收藏/历史与加密凭证默认进云备份
位置：`app/src/main/AndroidManifest.xml:10-15`；`app/build/intermediates/merged_manifests/perfDebug/processPerfDebugManifest/AndroidManifest.xml:24-31`
证据：两个 manifest 均无 `android:allowBackup`/`dataExtractionRules`/`fullBackupContent`；`git ls-files '*/src/main/res/xml/*'` 为空 → 默认 allowBackup=true；`EncryptedAuthStore.kt:28` 的 MasterKey 在 Android Keystore（不随备份迁移）
规范：S8（敏感数据备份策略）
影响：收藏/历史/搜索记录随云备份离开设备；换机恢复后凭证文件解不开 → 静默登出（`EncryptedAuthStore.kt:49-51`），用户无提示
建议：manifest 显式 `android:allowBackup="false"`，或用 dataExtractionRules 排除 shared_prefs 与 databases
成本：S

### [P2][E-07] 历史表改复合主键后 bvid/updatedAt 索引丢失，收藏表缺 (bvid,cid)
位置：`core/data/src/main/java/cn/debubu/tingbili/core/data/db/HistoryDao.kt:20,52,56-57,63-68,71-72`；`FavoriteEntities.kt:24,33`；`FavoriteDao.kt:92`
证据：`:20` `@Entity(primaryKeys = ["sourceType","sourceId"])` 无 indices；`:63-68` 注释仍写「只收本单相关 BV，避免全表扫描」，但 bvid 已无索引；`git show 9ec0990:HistoryDao.kt:16` 旧版 `primaryKeys = ["bvid"]` 自带索引；`FavoriteDao.kt:92` `WHERE bvid=:bvid AND cid=:cid` 与 PK(folderId,bvid,cid) 前缀不匹配
规范：S21（索引与查询匹配）
影响：历史按 BV 订阅/详情页续播、收藏跨夹判定由索引查找退化为全表扫；叠加 Room 表级失效，播放中每 10s 落一条历史会让在订阅的 Flow 反复重查
建议：`HistoryEntity` 加 `indices = [Index("bvid","cid"), Index("updatedAt")]`、`FavoriteTrackEntity` 加 `Index("bvid","cid")`（新增 15→16 迁移）
成本：S

### [P2][J-04] gradle.properties 含无效属性，并行/缓存/daemon 配置反优化
位置：`gradle.properties:1,3,4,5,7,8`
证据：解包 AGP `gradle-9.4.1.jar` 6134 个 class：`nonTransitiveRResources` 0 命中、`nonTransitiveRClass` 9 命中（含 TaskManager/GenerateLibraryRFileTask）；`parallel=false` + `configureondemand=true` + 无 `org.gradle.caching` → 13 模块串行、无构建缓存；daemon 30s 自杀；suppressUnsupportedCompileSdk=37 亦无必要
规范：S18（构建速度优化）
影响：本地迭代与 CI 时间被配置拖慢；无效属性制造「已开非传递 R」的错觉
建议：删 `:7`（或改正确名并注明默认已开）、删 `:8`、parallel=true、`org.gradle.caching=true`、daemon.idletimeout 调 1-3h
成本：S

### [P2][J-05] 54 处依赖坐标绕过版本目录
位置：`app/build.gradle.kts:145-146,165-169`；`core/data/build.gradle.kts:29-30,35,37`；`core/media/build.gradle.kts:38,41,45`；`data/bilibili/build.gradle.kts:29-30,35,37-38`；`feature/{detail,favorites,history,home,mine,player,space}/build.gradle.kts`
证据：全仓 54 命中（含 testImplementation），坐标字面量散在 13 个 build 文件，版本号靠 `${libs.versions.x.get()}` 拼接
规范：S18 + AGENTS.md「版本号统一在 libs.versions.toml 维护，别处不要硬编码」
影响：升 coroutines/serialization/lifecycle/paging 时改一处漏一处；同类依赖两套写法并存
建议：给重复出现的 12 个库补 catalog 别名（serialization-json、coroutines-core/test、lifecycle-*-compose、media3-datasource、paging-runtime、room-testing、mockwebserver、navigation-testing、ui-test-*）
成本：M

### [P2][J-10] R8 未保留行号，mapping 未归档
位置：`app/proguard-rules.pro:5`；`app/build/outputs/mapping/perfDebug/configuration.txt`；`.github/workflows/release.yml:86-95`
证据：合并配置 1358 行中 `-keepattributes` 只有 Annotation/Signature/InnerClasses/EnclosingMethod，`SourceFile`/`LineNumberTable` 0 命中；mapping.txt 76,462,139B 仅在 build 目录，release 工作流只做 `sha256sum` 并上传 apk
规范：S3（release 可诊断性）
影响：release/perfDebug 崩溃堆栈无行号，且 mapping 随 build 清理永久丢失 → 线上问题无法反混淆定位
建议：proguard 加 `-keepattributes SourceFile,LineNumberTable`；release.yml 把 mapping.txt 作为 artifact/Release 附件上传
成本：S

### [P2][J-11] 三份文档三套版本口径，CI 无漂移校验
位置：`docs/CONVENTIONS.md:8,10`；`docs/DEVELOPMENT.md:5`；`docs/PERF_AUDIT.md:431`
证据：CONVENTIONS 写 AGP 9.4.0/BOM 2026.08.00/Nav 2.9.8/Room 2.8.4/Media3 1.10.1/Paging 3.3.6 与 compile/targetSdk 36；实际 `libs.versions.toml:5-16` 为 9.4.1/2026.09.00/2.10.2/2.8.5/1.11.1/3.5.1，SDK 37/37（`app/build.gradle.kts:25,93`）；DEVELOPMENT 写 37 却声称「文档里不再重复版本」
规范：团队约定（单一事实源）
影响：新人照 CONVENTIONS 会把 compileSdk 改回 36，或按旧清单排查；审查反复产生假缺陷
建议：CONVENTIONS §1 改为「见 libs.versions.toml」并删硬编码清单；CI 加 toml↔文档一致性断言
成本：S

### [P2][J-08] targetSdk 36/37 行为变更无逐条处置：方向锁失效、predictive back 未声明
位置：`app/src/main/AndroidManifest.xml:19`；`app/src/main/java/cn/debubu/tingbili/navigation/AppNavHost.kt:52-53`
证据：manifest 全文无 `enableOnBackInvokedCallback`（API 33/34 默认 false → 已写的 predictivePop 转场在 13/14 设备不生效）；`screenOrientation="portrait"` 在 targetSdk≥36 + sw≥600dp 被系统忽略；正面对证：APK 内 2 个 .so 的 LOAD `p_align=0x4000`（16KB 页合规）
规范：S23/S24（平台行为变更）
影响：平板/折叠屏展开后登录页（949 行）与播放页（1978 行）未按大屏验证，可能布局错乱；13/14 设备返回动画与声明不符
建议：manifest 补 `android:enableOnBackInvokedCallback="true"`；把「API 36 大屏忽略方向锁」的处置写进文档并验证登录/播放页
成本：M

### [P2][J-09] enableEdgeToEdge 与已废弃窗口 API 并存
位置：`app/src/main/java/cn/debubu/tingbili/MainActivity.kt:118-129`
证据：`:118` `enableEdgeToEdge()` 之后 `:122-129` 又设 `systemUiVisibility = LAYOUT_STABLE|LAYOUT_FULLSCREEN|LAYOUT_HIDE_NAVIGATION` 与 `statusBarColor/navigationBarColor = TRANSPARENT`（@Suppress("DEPRECATION")）
规范：S23/S1（edge-to-edge 单链路）
影响：API 35+ 这些 API 已被忽略/无效果，注释所述 HyperOS 兜底在新系统上不成立；两套机制谁生效随 ROM 变化，回归难判断
建议：删 `:121-129` 旧 API，仅保留 enableEdgeToEdge；确需兜底则改 WindowInsetsController
成本：S

### [P2][G-13] 未设 sessionActivity：点通知/锁屏不回 App，也无断连回调
位置：`core/media/.../TingBiliPlaybackService.kt:96`；`core/media/.../PlaybackConnection.kt:51-63`
证据：`MediaSession.Builder(this, player).build()` 无 `setSessionActivity`（全仓 grep 0）；默认 Provider 用 `.setContentIntent(mediaSession.getSessionActivity())`，未设置即 null；PlaybackConnection 无 `MediaController.Listener`。
规范：S13（background playback：session activity / 控制器生命周期）
影响：播放中点通知或锁屏媒体卡片的"应用"区域无反应（用户以为卡死）；系统媒体恢复器不能续播；服务侧会话与 App 侧连接没有断连感知。
建议：Builder 链上加 `.setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), FLAG_IMMUTABLE))`（与 G-09 的 onDisconnected 一起改）。
成本：S

### [P2][G-09] Controller 只连不重建：断连后成为死实例，播放按钮静默失效
位置：`core/media/.../PlaybackConnection.kt:51-63,71-76,57`；`core/media/.../MediaControllerHandle.kt:61`
证据：connect() 只注册 future 完成回调（:55-62），无 `MediaController.Listener`；release() 全仓仅测试调用（PlaybackConnectionTest:22,31）；`future.get()`(:57) 未捕获 ExecutionException；MediaController 源码注释：断连后命令输出 "The controller is not connected. Ignoring ..."，且 Listener.onDisconnected 明确"controller becomes unavailable afterwards"。
规范：S13
影响：服务在暂停态被系统/任务移除回收后，回到 App 点播放仍走已断开的 controller：表现为"播放服务未就绪，请重试"或点击无反应，只能杀进程；连接失败时还会在主线程崩。
建议：`MediaController.Builder(...).buildAsync()` 后加 Listener：onDisconnected 时把 `_controller` 置 null 并允许重新 connect；`future.get()` 包 runCatching。
成本：M

### [P2][H-01] Me 页明暗切换按钮无可读标签
位置：`feature/mine/.../MeScreen.kt:384-394`（调用点 :289）
证据：`.size(34.dp).clip(...).background(...).clickable(onClick)` 内 `Icon(icon, contentDescription = null)`；节点上无 Text/语义，TalkBack 读作无名"按钮"。
规范：S9（Compose 无障碍：可交互元素需语义）、S1 Content_Description
影响：视障用户无法知道该按钮作用（切换深/浅色）；也是 H2 的同一控件，可一并修。
建议：给 Box 加 `.semantics { contentDescription = if (darkTheme) "切换到浅色" else "切换到深色" }`，Icon 保持 null。
成本：S

### [P2][H-03] 品牌主色上的白字对比度 2.76:1（深色 2.48:1）
位置：`core/ui/.../theme/Theme.kt:56-57`、`:109-111`；`core/data/.../PreferencesRepository.kt:308`；示例 `feature/mine/.../SettingsScreen.kt:252`
证据：DEFAULT_THEME_COLOR=#FF6699，相对亮度 0.3306；`contentColor()` 以 luminance>0.5 选黑/白→选白，实算对比度 2.76:1（深色 primary #FF78A5 白字 2.48:1）。
规范：S10（对比度 4.5:1/3:1）、S1 Visual_Contrast
影响：默认粉色主题下所有 filled Button 标签（"应用""清理"等）对比度不足，低视力/强光下难读；不是纯理论值，是默认观感。
建议：把阈值从 0.5 降到 0.4 左右改配深色文本（黑字在同色 7.61:1），或对 primary 用 0.3 白混合再选色；改完用 Accessibility Scanner 复核。
成本：S

### [P2][H-05] 固定高度容器在 200% 字体下会挤爆两行文本
位置：`app/.../navigation/MiniPlayerBar.kt:232`；`feature/detail/.../DetailScreen.kt:632-639`；`feature/mine/.../LoginScreen.kt:397,648`；`feature/space/.../SpaceScreen.kt:591`
证据：mini 条 Row `.height(60.dp)` 内放标题(1 行)+副标题+时间；详情行 `Column(...height(68.dp))` 内是 maxLines=2 的 bodyMedium 标题 + 副标题（2.0x 时单行约 40dp，两行 80dp）。
规范：S9（支持用户缩放）、S10
影响：系统字体最大时 mini 条/详情行文本被裁切或与副标题挤压重叠，播放页之外的主要列表都受影响。
建议：固定 height 改 `heightIn(min = ...)`（允许长高），行内文本用 maxLines 兜底即可；顺带确认底栏 Tab 文案。
成本：M

### [P3][B-07] 无 Baseline Profile，首启无 AOT 覆盖
位置：app/build.gradle.kts:121-171（deps 无 profileinstaller）；`git ls-files "*baseline*" "*profileinstaller*"` 空
证据：无 :benchmark 模块、无 baseline-prof*.txt；perfDebug 变体（`app/build.gradle.kts:54-65`）已存在是补 BP 的前提
规范：S17（B8）
影响：首启 ART 解释执行比例高（官方称 BP 约省 30% 启动）；已列入 PERF_AUDIT 遗留项；成本 L
建议：单独立项 macrobenchmark 生成并随包发布
成本：L

### [P3][A-05] 播放错误态只有文字，无显式重试入口
位置：feature/player/src/main/java/cn/debubu/tingbili/feature/player/PlayerScreen.kt:279-287
证据：`trackUi.errorMessage?.let { Text(...) }` 后无按钮/无动作；用户只能再点播放（隐式重试），弱网下不知该做什么
规范：S1（A1）
影响：错误可感知但不可操作，易被误判为卡死；其余异步页重试齐全（Home/Detail/Space/Bili* 5 页）
建议：错误文本旁加重试按钮，直接重放当前曲目
成本：S

### [P3][I-06] 0 个 instrumented 测试（已知缺口，按计划不判违规）
位置：`git ls-files "*src/androidTest*"` 空；app/build.gradle.kts:98 testInstrumentationRunner
证据：346 个 @Test 全为 JVM/Robolectric，无设备级 UI/媒体/FGS 回归
规范：S12（I7，记为缺口）
影响：A2/A3/A8 类只能人工回归；成本 L
建议：维持「真机清单 + Robolectric」组合，单独排期再评估
成本：L

### [P3][C-03] 收藏页混排无 contentType
位置：feature/favorites/.../FavoritesScreen.kt:152-167（folder 卡 / BV 卡两个 Composable）
证据：`git grep contentType` → 0 处；`when { folder != null -> FavoriteFolderCard; bv != null -> BvFavoriteCard }`
规范：C3 / S4（Always specify contentType）
影响：混排滚动时槽位复用率低、首帧布局成本略高；无正确性影响
建议：`items(unified, key = { it.key }, contentType = { if (it.folder != null) "folder" else "bv" })`
成本：S

### [P3][C-11] core:ui 基础组件吃业务模型
位置：core/ui/src/main/java/cn/debubu/tingbili/core/ui/component/FavoriteFolderPicker.kt:29、:45；core/ui/.../theme/Theme.kt:18
证据：picker 参数 `tracks: List<Track>` 直接用 `core.data.model.Track`；Theme 直接依赖 `PreferencesRepository`
规范：C11 / S19（slot 化、组件不吃业务模型）
影响：core:ui 无法脱离数据层复用；Track 字段变更会牵动基础组件
建议：picker 改收本模块定义的 `PickableTrack`（key/label），映射留在 feature
成本：M

### [P3][C-10] 三个通用设置行组件缺 modifier 参数
位置：core/ui/src/main/java/cn/debubu/tingbili/core/ui/component/SettingGroup.kt:79-88、:139-146、:186-192
证据：`fun SettingRow(icon, accent, title, …)` 无 `modifier: Modifier = Modifier`；同文件 SettingsGroup:57-60 有
规范：C10 / S19（Modifier 作为首个可选参数）
影响：调用方无法加 padding/testTag/语义，只能套外层容器
建议：三个组件补 `modifier` 参数并透传到根 Row
成本：S

### [P3][D-09] PlayerManager 自定义 scope 无取消路径
位置：core/media/src/main/java/cn/debubu/tingbili/core/media/PlayerManager.kt:80、:1059-1064
证据：`private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`；`release()` 只 release 子组件，全仓 `scope.cancel` 仅 TingBiliPlaybackService.kt:130
规范：D9 / S7、S13
影响：app 级单例使实际泄漏≈0；但 release() 语义残缺，测试注入 testScope 后旧 Main scope 仍存活（:85-89 注释自认）
建议：release() 内补 `scope.cancel()`，或删除 release() 并在 KDoc 明确「进程级单例、不释放」
成本：S

### [P3][E-06] DataStore 4 个键读侧不钳制
位置：`core/data/src/main/java/cn/debubu/tingbili/core/data/datastore/PreferencesRepository.kt:42,43,48,56`；`core/media/src/main/java/cn/debubu/tingbili/core/media/PlayerManager.kt:992`
证据：`val stepSec: Flow<Int> = ds.data.map { it[STEP_SEC] ?: 15 }`（无 coerceIn）；`PlayerManager.kt:992` 直接 `prefs.stepSec.first()` 乘进 seek；同文件 `:50` subtitleScale、`:93` cacheMaxMb 都有 coerceIn
规范：CONVENTIONS.md:30（新增键默认值 + 钳制）
影响：偏好被写坏（0/超大值）时表现为「快进无效」或「一按跳整集」，无崩溃但难排查
建议：stepSec 补 `coerceIn(5, 60)`、repeatMode 补 0..2、audioQuality 回落 `AudioQuality.fromCode`
成本：S

### [P3][F-08] release 无日志门控，WebView 页面 URL 进 logcat
位置：`feature/mine/src/main/java/cn/debubu/tingbili/feature/mine/GeetestDialog.kt:86,96,144`；`app/proguard-rules.pro:1-11`
证据：全仓 `BuildConfig` 引用 0 处、proguard 无 Log 剥离；主源码 13 处 Log，其中 `:96` 打 `request.url`（登录链路 URL 可能带查询参数）、`:86` 打 WebView console
规范：S8 + 团队约定（不打敏感日志）
影响：release 用户 logcat/bugreport 可见登录相关 URL；目前未打凭据，风险低
建议：`:86/:96` 用 `BuildConfig.DEBUG` 包裹或删掉 URL 部分；release 加 `-assumenosideeffects class android.util.Log`
成本：S

### [P3][J-03] 只有依赖库自带 baseline profile，无自家 profile 与度量
位置：`app/build/outputs/apk/perfDebug/app-perfDebug.apk!assets/dexopt/baseline.prof`
证据：APK 内 baseline.prof 11,299B + baseline.profm 1,407B（AGP 合并依赖 profile）；`benchmark|baselineprofile` 在 settings.gradle.kts / toml / 各 build 文件 0 命中
规范：S17
影响：启动无自家 AOT 覆盖，冷启动优化不可量化、不可回归
建议：单独立项加 `:benchmark` + baselineprofile 生成器；在此之前在文档记录「已知缺口」
成本：L

### [P3][J-07] 体积基线过期且无回归手段
位置：`app/build.gradle.kts:68`（注释「61M→6M」）
证据：实测 debug 85,007,434B（81.1MiB）、perfDebug 6,527,465B（6.22MiB）；61M 无任何产物/文档出处；CI 无体积断言
规范：S3/S18
影响：注释误导后续优化判断；体积回归只能靠人肉
建议：注释改为实测值 + 测量命令，CI 加 perfDebug APK 大小阈值（超 7MiB 失败）
成本：S

### [P3][G-06] 媒体通知可被滑掉，滑掉后暂停态不再出现（框架默认，自选是否加固）
位置：无自定义 Provider（`git grep setMediaNotificationProvider` 0）；框架：media3 1.11.1 `DefaultMediaNotificationProvider` `.setOngoing(false)`
证据：默认构建链 .setStyle(MediaStyle)/.setVisibility(PUBLIC)/.setContentIntent(sessionActivity)/.setOngoing(false)；滑掉经 deleteIntent 置 wasNotificationDismissed，暂停态不再显示，播放态需下一条 player 事件才回来。
规范：S13、S1 Audio_Notification_Style
影响：用户误滑后丢失通知栏/锁屏控制入口（声音继续播），体验断档；MediaStyle/状态更新/锁屏控制本身已满足。
建议：如需"不可滑掉"，自定义 `MediaNotification.Provider` 或包装 DefaultMediaNotificationProvider 把 notification 置 ongoing；否则在文档里记为已知行为。
成本：S

### [P3][H-04] 收藏按钮同一节点重复语义（contentDescription + onClickLabel）
位置：`feature/player/.../PlayerScreen.kt:934-954`
证据：combinedClickable(onClickLabel = if (isFavorite) "取消收藏到我的最爱" else "收藏到我的最爱", onLongClickLabel = "选择收藏夹")，内部 Icon contentDescription = if (isFavorite) "已收藏到我的最爱" else "收藏到我的最爱"。
规范：S9（语义合并/清除、避免重复朗读）
影响：TalkBack 对同一个按钮播报两段近似文案，长按提示夹在中间，理解成本增加。
建议：二选一——保留 onClickLabel 就把 Icon 的 contentDescription 置 null；或反过来并在 Box 上统一 `semantics(mergeDescendants = true)`。
成本：S

### [P3][H-06] 文案未资源化 + 部分格式化缺 Locale（决策项，仅记录）
位置：`app/src/main/res/values/strings.xml:3`；`MeScreen.kt:499-500`、`StorageSettingsScreen.kt:324`、`PlayerScreen.kt:1095,1097,1104`、`AppearanceScreen.kt:285`、`WbiSigner.kt:139,147`
证据：strings.xml 仅 app_name；中文字面量 792 处/138 文件；stringResource/R.string 0 处；上述 String.format/.format 未带 Locale。
规范：S11（文案进资源）、lint DefaultLocale
影响：单语下用户无感；但文案无法统一校对、无法被 lint 检查，未来本地化成本是 L；数字格式在阿拉伯语等区域可能被本地化。
建议：按已拍板"中文单语"写进 docs 并关掉这条预期；同时给 9 处格式化补 Locale（数值用 getDefault，协议/十六进制用 ROOT）。
成本：S

### [P3][H-08] 无自动化无障碍检查
位置：`app/build.gradle.kts:165`（已有 ui-test-junit4）；全仓 0 处 AccessibilityChecks
证据：`git grep -n 'AccessibilityChecks\|enableAccessibilityChecks'` 0 命中；无 androidTest 目录。
规范：S9（Compose 无障碍测试 / ATF）
影响：H1/H2 这类问题只能靠人工发现，整改后无门禁防回归；与 I9（无 lint 门禁）同源。
建议：在 Compose 测试 setup 里 `AccessibilityChecks.enable()`（Compose 1.8+），至少盖 NavigationTest 已渲染的页面。
成本：S

---

## 5. 检查点逐条结论（100 条）

### A 应用质量 / B 性能与流畅度 / I 测试与门禁

| 编号 | 结论 | 证据（file:line 或命令输出片段） | 备注/影响 |
|---|---|---|---|
| A1 | 部分合规 | 播放错误只有文本：`PlayerScreen.kt:279-287`；loading 转圈 `:392-398`；其余异步页三态齐全：`HomeScreen.kt:218,615`、`DetailScreen.kt:151-159`、`SpaceScreen.kt:167-183`、`BiliFavoritesScreen.kt:139`、`BiliHistoryScreen.kt:131-137` | 唯一缺口见 A-05（P3）；本地 DB 页无需三态可接受 |
| A2 | 待验证 | 「准备中」静态成立：`PlayerManager.kt:741` isLoading=true → `PlayerScreen.kt:392` 转圈；1s 出声需真机（T-A2） | 静态无法证「1s 内」 |
| A3 | 待验证 | 恢复点 `MainActivity.kt:95-98` onStart→`onUiVisible()`，但一致性只能真机看（T-A3） | 锁屏/后台/解锁三组回归 |
| A4 | 待验证 | 队列恢复已有：`PlayerManager.kt:240-243,263-284`；nav 栈靠 `rememberNavController`，滚动位置 `rememberSaveable` 仅 12 处 | 「不保留活动」实测（T-A4） |
| A5 | 合规 | 仅 2 处 BackHandler 且均为标准用法：`HomeScreen.kt:139`（清搜索焦点）、`PlayerScreen.kt:182`（退出全屏歌词）；0 处 onBackPressed，无自绘返回提示 | predictive back 未声明归 J8，不属 A5 |
| A6 | 部分合规 | `app/build.gradle.kts:25`(compileSdk 37)/`:93`(targetSdk 37) 已最新；但 `gradle.properties:8` `android.suppressUnsupportedCompileSdk=37` 说明 AGP 9.4.1 未声明支持 compileSdk 37（需压制警告） | 见 A-01（P2） |
| A7 | 部分合规 | `gradle/libs.versions.toml:28` security-crypto=1.1.0-alpha06，全库唯一预发布依赖；`EncryptedAuthStore.kt:24-32` | 见 A-04（P2） |
| A8 | 部分合规 | 全仓 0 处 NotificationChannel/MediaNotificationProvider/自定义通知构建（唯一 notifyChanged 是队列回调），通知由 Media3 默认提供（`TingBiliPlaybackService.kt:96` 仅 Builder().build()）；权限回调空实现 `MainActivity.kt:88-89` | 见 A-03（P2）；常驻/锁屏可控待 T-A8 |
| A9 | 部分合规 | `AndroidManifest.xml:19` screenOrientation="portrait"，但 `docs/design/UI_DESIGN_REQUIREMENTS.md:34-37` 要求「允许旋转、横屏必须可用」；targetSdk 37 在 sw≥600dp 忽略方向锁 | 见 A-02（P2）；大屏布局待 T-A9 |
| B1 | 部分合规 | 启动门禁=`MainViewModel.kt:51-57` Room 查询 + `MainActivity.kt:102-104` splash keep；`MainActivity.kt:105` connect 触发 Service/ExoPlayer 构造（内含 `ServicePlayerModule.kt:82` runBlocking）；`AuthRepository.kt:55` store.load() 同步读加密盘 | 见 B-01/B-02（P1）；2s 指标待 T-B1 |
| B2 | 待验证 | 帧预算只能 gfxinfo/Perfetto 定量（T-B2）；静态回归面见 B-03 | 不新诊断，只核对回归 |
| B3 | 违规 | `git grep -n "StrictMode" -- "*.kt"` 0 命中，debug/主流程均未开启 | 见 B-06（P2），诊断能力缺失 |
| B4 | 违规 | 主源码 3 处主线程 runBlocking：`TingBiliApp.kt:30`、`AudioCacheModule.kt:38`、`ServicePlayerModule.kt:82`（`AudioUrlResolver.kt:35-41` 在 ExoPlayer 加载线程，不计） | 见 B-01（P1），实测耗时代验 |
| B5 | 部分合规 | `ImageUrl.kt:8-15` 仅 3 档；`MiniPlayerBar.kt:135-137,241` 42dp 用 480×270；16 处 AsyncImage 全部裸 model（0 处 ImageRequest/size/placeholder/crossfade） | 见 B-05（P2）；Coil3 按控件尺寸解码，OOM 风险不成立 |
| B6 | 合规 | 唯一常驻 Service=`TingBiliPlaybackService`；`WorkManager|AlarmManager|setRepeating` 0 命中；轮询只在播放中（`HistoryTracker.kt:110` while 活跃） | 无常驻额外组件 |
| B7 | 合规 | `app/build.gradle.kts:54-65` perfDebug（debug 签名+minify+shrink+debuddable=false）、`:67-80` release（minify+shrink） | perf 包可测 |
| B8 | 违规 | `git ls-files "*baseline*" "*profileinstaller*"` 空；deps 无 profileinstaller；无 :benchmark | 见 B-07（P3，已知债） |
| B9 | 违规 | `PlayerScreen.kt:160-162` 顶层 collect progUi（`PlayerViewModel.kt:321-338` 1Hz）；`MainTabsScaffold.kt:159-162,186` 平板分支与 AppRootNavHost 同作用域收 player.state（`HistoryTracker.kt:59` 500ms） | 见 B-03（P1），与 PERF_AUDIT:463-464 已销账结论不符 |
| B10 | 违规 | `HistoryDao.kt:20` 无非主键索引（查询 `:52-54,63-68,71-74`）；`FavoriteEntities.kt:33` 仅 Index(folderId)，`FavoriteDao.kt:92-96` 按 bvid+cid 全表扫；`FavoriteDao.kt:33-61` JOIN HistoryEntity 被 10s 写失效；`HistoryViewModel.kt:36-42` Eagerly | 见 B-04（P2） |
| I1 | 部分合规 | 50 测试文件分布见文首；feature:detail(2 源/0 测试)、feature:space(4/0)、core:bili-ui(1/0)；feature:favorites 仅 `BvRefreshFailureTest.kt`（错误映射，非 VM） | 见 I-03（P2） |
| I2 | 合规 | 真实网络 0：`data:bilibili` 用 MockWebServer（`AuthRepositoryTest.kt:19` 等 3 文件）+ fake AuthStore（`:21-26`）；0 处 `api.bilibili.com` 字面量；Robolectric 7 模块 / 纯 JUnit 其余 | 无真机/真网依赖 |
| I3 | 违规 | `ci.yml:63` 只跑 `:core:data`+`:core:media`（19 文件/137 用例）；`|| ./gradlew testDebugUnitTest` 仅在前两者失败时执行，且同一失败会再次失败 → 无法把红转绿；成功路径另有 31 文件/209 用例不执行 | 见 I-01（P1） |
| I4 | 部分合规 | `ci.yml:55-63` 有 assembleDebug+编译测试+单测；无 lint 步骤 | 与 I-02 同根 |
| I5 | 部分合规 | `NavigationTest.kt` 17 个 @Test，路由解析用例各自新建 NavHost，覆盖 11/21 路由（`Routes.kt` 21 个 @Serializable）；未覆盖 MainTabs/Home/Favorites/History/Appearance/Player/UpSpace/VideoDetail/FavoriteFolder/BvFavorite | 见 I-04（P2） |
| I6 | 合规 | 0 处 Thread.sleep；26 个文件用 runTest（含 `advanceTimeBy/runCurrent`）；10 个文件注入 StandardTestDispatcher/UnconfinedTestDispatcher | 确定性成立 |
| I7 | 部分合规 | `git ls-files "*src/androidTest*"` 空；`app/build.gradle.kts:98` testInstrumentationRunner 为死配置 | 按计划记为已知缺口，不判违规（I-06，P3） |
| I8 | 违规 | 7 模块用 RobolectricTestRunner，仅 6 份 robolectric.properties（缺 feature:player，`PlayerViewModelTest.kt:54,57`）；6 份均 sdk=36 而 targetSdk=37；另有 2 处 `@Config(sdk=[34])`（`NavigationTest.kt:31`、`LoginScreenTest.kt:23`） | 见 I-05（P2） |
| I9 | 违规 | `git grep -n "lint" -- "*.kts" ".github/workflows/*.yml"` 0 命中；`git ls-files` 无 detekt.yml/ktlint/.editorconfig/lint-baseline | 见 I-02（P1） |

### C Compose 专项 / D 架构与状态管理

| 编号 | 结论 | 证据（file:line 或命令输出片段） | 备注/影响 |
|---|---|---|---|
| C-01 | 部分合规 | `SimpleDateFormat` 每次调用 new：FavoriteCards.kt:335-339（调用 :131、:223）、BvFavoriteScreen.kt:477（:376）、DetailScreen.kt:673、SpaceScreen.kt:660（:609）；对照 HistoryScreen.kt:336 已用 remember | 组合体排序/分组类昂贵计算总体控制良好（FavoritesScreen.kt:64 用 `remember(keys)`）；漏点集中在日期格式化 |
| C-02 | 部分合规 | Lazy 容器实测 **16** 个（feature 12 / core:ui 3 / core:bili-ui 1），`items(/itemsIndexed(` 共 **17** 处，key 覆盖 **17/17**；含 index 的 key **5** 处：HomeScreen.kt:336、:570-571，SpaceScreen.kt:203，PlayerScreen.kt:1350、:1503 | 16 容器清单：BiliAccountUi:146、FavoriteFolderPicker:92/145/237、DetailScreen:273、BiliFavorites:144、BvFavorite:180、FavoriteFolder:148、Favorites:125、History:99、Home:325/558、Player:1345/1493/1919、Space:120。缺 key 为 0（比侦察材料更准确：无 key 违规，只有 index 型 key）；另 DetailScreen:533 `key={it.bvid}` 有重复风险 |
| C-03 | 部分合规 | `git grep contentType` → **0 处**；真异构只有 FavoritesScreen.kt:152-167（`when` 出 FavoriteFolderCard / BvFavoriteCard 两种卡） | 侦察材料列的 HistoryScreen:104、DetailScreen:426 是同一 Composable 带参数变体（同类型），不需要 contentType；影响面比材料说的小 |
| C-04 | 部分合规 | `derivedStateOf\|computedStateOf` → **0 处**；候选：MainActivity.kt:190-195（`hierarchy.any{}` 每次分配）、MainTabsScaffold.kt:164、HomeScreen.kt:146、MiniPlayerBar.kt:177 | 逐点微成本（μs），真正价值是被 C-13/C-14 放大后的乘数；与材料一致 |
| C-05 | 合规 | `LocalContext.current` 仅 4 处：Theme.kt:34、AppearanceScreen.kt:158、GeneralSettingsScreen.kt:262、LoginScreen.kt:862；列表 item 内 0 处 CompositionLocal 读取/0 处 collect | item 内只读 `LocalImageFormat`（如 HistoryScreen.kt:139），语义正确（换图档需重建） |
| C-06 | 合规 | 16 个 Lazy 容器均是真变长列表；设置页/短列表全用 `SettingsGroup{Column}`；唯一灰区 FavoriteFolderPicker.kt:92/145/237 三个弹层内 LazyColumn（带 `heightIn(max=…)` 滚动兜底） | — |
| C-07 | 合规 | 主源 `indexOf(` → **0 处**；`indexOfFirst` 5 处全在 ViewModel（DetailViewModel:118、BvFavoriteViewModel:103、FavoriteFolderViewModel:98、HistoryViewModel:126/153），不在 items 内 | 材料说的 `indexOfFirst` 属 VM 内 O(N) 扫描，与 C7 的 items 内 O(N²) 不是一件事 |
| C-08 | 合规 | 抽查 20 个 Screen/组件：状态集中在 Screen 根或 VM，子组件参数为「值 + 回调」；FavoriteFolderPicker.kt:55/61 的草稿选择状态属弹层内部 UI 状态 | 无「子组件私藏业务状态」实例 |
| C-09 | 部分合规 | `rememberSaveable` **12 处**（6 文件）vs `remember { mutable*StateOf }` **55 处**；未 saveable 的关键输入：LoginScreen.kt:127（tab）、:667-669（zone/tel/code）、:791-793（username/password）、:860（Cookie 文本）、GeneralSettingsScreen.kt:187（newKeyword） | 转屏/分屏/进程重建后登录页已输入的手机号/验证码/密码全丢；其余 remember 多为 sheet 开关（可接受） |
| C-10 | 部分合规 | 无 @Composable 返回非 Unit；命名与「事件 lambda 后置」合规；但 SettingGroup.kt:79-88 / :139-146 / :186-192 三个组件缺 `modifier: Modifier = Modifier` | 调用方无法给行加 padding/testTag/语义，只能外包 Box |
| C-11 | 违规 | FavoriteFolderPicker.kt:29 import + :45 参数使用 `core.data.model.Track`；Theme.kt:18 import `PreferencesRepository` | core:ui/component 唯一吃业务模型处；FavoriteFolderOption（:32）已在本模块定义，说明只要把 Track 也做成本模块视图模型即可 |
| C-12 | 部分合规 | 主源 `Color(0x…)` **49 行** + `Color.Black/White/Gray` **32 行**；`fontSize = N.sp` **11 处**。feature 层硬编码色 49 行：MeScreen.kt 24、LoginScreen.kt 11、DetailScreen.kt 10（`Color.Gray`×8、`Color.White`×1）、Home/Space 各 2 | 32 行在 core:ui 的 Theme/SettingAccent 属调色板定义可接受；feature 层 49 行是真绕过主题（深色/AMOLED 对比度不随主题走） |
| C-13 | 部分合规 | `@Immutable\|@Stable` → **0 处**；`stabilityConfigurationFile` → **0 处**；无 `composeCompiler{}/reportsDestination`（0 命中）；含 List/Map/Set 字段的状态类 **14 处/5 文件**（PlaybackState.kt:11 `queue: List<Track>`、PlayerViewModel 9 处） | **对计划的纠正**：Kotlin≥2.0.20 起 Compose 默认 strong skipping，不稳定参数 equals 相等仍可跳过，故「0 处注解 ⇒ 不可跳过」不成立；真实缺口是「List/Map 新实例使 equals 不等」+ 无显式契约/无报告可测 |
| C-14 | 违规 | PlayerScreen.kt:161 收 `progressUi`（PlayerViewModel.kt:336 `sample(1_000)`）且 :331/:362-364/:446-447/:488-493 在 body 直接消费；DetailScreen.kt:104、BvFavoriteScreen.kt:87、FavoriteFolderScreen.kt:77 收 `playingTrack`（`player.state.map{…positionMs/1000*1000}`）却在 item 内用（:429 / :284 / :221）；MainTabsScaffold.kt:162 平板分支收 `player.state` | `player.state` 源头 2Hz：HistoryTracker.kt:59/110 `POSITION_POLL_INTERVAL_MS=500` → PlayerManager.kt:157-166 onTick 每次 `_state.update`。即播放中三个二级页整页 body 1Hz、平板壳 2Hz 重跑 |
| D-01 | 违规 | feature VM 直注 DAO：DetailViewModel.kt:62、BvFavoriteViewModel.kt:46、FavoriteFolderViewModel.kt:43、HistoryViewModel.kt:29、HomeViewModel.kt:38、MeViewModel.kt:30；直注 BiliApi：BiliHistoryScreen.kt:35-37（`BiliHistoryViewModel`）。core:data 仅 `FavoritesRepository`+`PreferencesRepository` 两个仓储 | feature 本就 `implementation(project(":core:data"))`（29 条 feature 依赖实测），所以这不是「看得见但用不了」，而是纯粹没建仓储层 |
| D-02 | 合规 | `Regex(/Pattern.compile` 在 feature 主源 **0 处**；业务操作（续播回退、收藏落库、错误映射）均在 VM（HistoryViewModel:117-155、DetailViewModel:191-238）；Screen 只做展示派生（HistoryScreen:107-110 folderExists） | — |
| D-03 | 合规 | Activity 子类仅 `MainActivity:80`；`Routes.kt` 21 个 `@Serializable`，navigation 目录下 Routes.kt 以外 `@Serializable` **0 处** | — |
| D-04 | 合规 | 状态向下 `StateFlow` + 事件向上 `onXxx()`；跨层回调只出现在 core:media 内部 Listener（HistoryTracker.Listener 等），UI 层 0 处 | — |
| D-05 | 合规 | 裸 `collectAsState(` → **0 处**；`collectAsStateWithLifecycle` → **156 次/24 文件** | 与约定/材料一致，唯一完全干净的条目 |
| D-06 | 合规 | `project(":feature` 在 feature/*/build.gradle.kts 与 app 里 **0 处**；29 条依赖全部指向 `core:*`/`data:bilibili` | 附带风险：core/ui/build.gradle.kts:26 `api(project(":core:data"))` 把数据层透传给所有 feature（不是 D6 违规，但降低了分层摩擦力） |
| D-07 | 部分合规 | `Dispatchers.` 命中 23 行：硬编码 **21**（withContext(IO) 17：AuthRepository 10、AppCacheManager 5、BiliRepository 1、AudioDecoderInspector 1；Dispatchers.Main.immediate 2：PlayerManager.kt:80、TingBiliPlaybackService.kt:32；VM 内 `launch(Dispatchers.IO)` 2：HomeViewModel.kt:117/179）；可注入仅 **1** 处：PlayerViewModel.kt:92 + PlayerModule.kt:20 | VM 里 `launch(Dispatchers.IO)` 同时违反「suspend 必须 main-safe」的精神，建议 D-08 一起收 |
| D-08 | 违规 | 主线程 `runBlocking` 读 DataStore 首值 3 处：TingBiliApp.kt:30（`newImageLoader` 首次取图）、AudioCacheModule.kt:38（@Singleton SimpleCache，Service 首次创建时由 ServicePlayerModule.kt:41 注入）、ServicePlayerModule.kt:82（Service.onCreate，ServicePlayerModule 装 ServiceComponent）；豁免：AudioUrlResolver.kt:27/30/33（ExoPlayer 加载线程，注释自证） | 与计划 §9-#1 三处定位一致，本轮确认调用链：SimpleCache→providePlaybackPlayer→Service.onCreate 均在主线程 |
| D-09 | 部分合规 | `CoroutineScope(` 自定义 2 处：PlayerManager.kt:80（**无任何 cancel 路径**，release():1059-1064 只 release 子组件且全仓 0 调用者）、TingBiliPlaybackService.kt:32（:130 `serviceScope.cancel()` ✓）；其余 12 处为 `rememberCoroutineScope()`（生命周期托管 ✓） | 因 PlayerManager 是 app 级单例，"泄漏面≈0"，但 release() 语义残缺、注入 testScope 后旧 scope 仍存活（PlayerManager.kt:85-89 注释自认） |
| D-10 | 合规 | 全仓 Channel/SharedFlow **0 处**；一次性提示统一 `MutableStateFlow<String?>` + `consumeMessage()`：DetailViewModel:79、BvFavoriteViewModel:73、FavoriteFolderViewModel:73、FavoritesViewModel:40、HistoryViewModel:64、PlayerViewModel:501、StorageSettingsViewModel:55，全部「先 consume 再 showSnackbar」 | 无重组复放；但「先 consume 再 show」可能取消正在显示的 snackbar（见 PART3） |

### E 数据层 / F 网络与安全 / J 构建与发布

| 编号 | 结论 | 证据（file:line 或命令输出片段） | 备注/影响 |
|---|---|---|---|
| E1 | 合规 | `TingBiliDatabase.kt:18-19`（version=15, exportSchema=false）；`DatabaseModule.kt:89` 仅 12→13/13→14/14→15 三个手写 Migration；`:93` `fallbackToDestructiveMigration(true)` | **决策允许**（用户已确认数据库可破坏性更新）。与 `docs/CONVENTIONS.md:30`「禁止破坏性迁移」冲突 → **改文档，不改代码** |
| E2 | 合规 | `HistoryDao.kt:41-82`、`FavoriteDao.kt:16-138`、`PlayQueue.kt:41-47`、`HomeSnapshot.kt:28-37`、`PlayUrlCache.kt:32-52` 全为 suspend/Flow；主源码 `allowMainThreadQueries` 0 命中（6 处均在测试） | Room 查询走自身 executor；全仓仅 1 处 `flowOn`（`PlayerViewModel.kt:208`），Room Flow 不需要业务层调度 |
| E3 | 合规 | `rawQuery` 0 命中；`execSQL` 7 处全在迁移内（`DatabaseModule.kt:35,42,45,57,72,80,81`） | 迁移必须裸 SQL，属规范允许面；业务查询 100% 走 @Query 编译期校验 |
| E4 | 合规 | 9 实体全 data class + 显式主键：`FavoriteEntities.kt:15,24,50,63`、`HistoryDao.kt:20`、`PlayQueue.kt:22`、`PlayUrlCache.kt:17`、`SubtitleDiskCache.kt:24`、`HomeSnapshot.kt:17`；无跨类型字段 → 无需 TypeConverter | 3 表把子结构塞 JSON 字符串（`HomeSnapshot.kt:19-22`、`SubtitleDiskCache.kt:25,27`、`PlayQueue.kt:23`）属设计取舍，不判违规 |
| E5 | 合规 | 多表写全包事务：`FavoritesRepository.kt:108,127,139,163,179,193,208`；DB `@Singleton`（`DatabaseModule.kt:86-88`） | DAO provider 未标 @Singleton，但只是无状态包装，可接受 |
| E6 | 部分合规 | 键均为小标量且有默认值；但 stepSec/repeatMode/speed/audioQuality 读侧不钳制（`PreferencesRepository.kt:42,43,48,56`），`PlayerManager.kt:992` 直接 `prefs.stepSec.first()` 参与 seek | 与 `CONVENTIONS.md:30`「新增键读写成对/默认/钳制」偏离；历史大 JSON 键已只剩一次性 drain（`:326,330`） |
| E7 | 违规 | `HistoryDao.kt:20` `@Entity(primaryKeys=["sourceType","sourceId"])` **无 indices**；按 bvid（`:52,63,67`）与 updatedAt（`:56,59,71`）查询/排序全表扫；重构前 `@Entity(primaryKeys=["bvid"])`（`git show 9ec0990:HistoryDao.kt:16`）自带索引 → **索引回退** | `FavoriteEntities.kt:24,33` 复合 PK + 冗余 `Index("folderId")`，缺 (bvid,cid)；`FavoriteDao.kt:92` 跨夹查询扫全表 |
| E8 | 不适用 | 理由：破坏性更新策略下不要求迁移保数据、无迁移测试需求；且 `TingBiliDatabase.kt:19` exportSchema=false 使 MigrationTestHelper 无法工作；`room-testing` 在 3 个模块声明但 `MigrationTestHelper` 0 命中（`core/data:35`、`favorites:67`、`history:66`） | 若将来改回「保数据迁移」，需同时开 exportSchema 与迁移测试；当前应记为死依赖 |
| F1 | 合规 | 唯一 baseUrl 为 https（`NetworkModule.kt:63`）；两处 manifest 均无 `networkSecurityConfig`/`usesCleartextTraffic`，targetSdk 37 默认禁明文；API 下发的 http 资源统一升 https（`ImageUrl.kt:20`、`SubtitleDto.kt:81`、`BiliRepository.kt:151`） | playurl 直链若某天返回 http 会被系统拦（播放失败而非降级），属健壮性风险，见 PART3 |
| F2 | 合规 | 主源码 `X509TrustManager/checkServerTrusted/HostnameVerifier/sslSocketFactory/certificatePinner` 全 0 命中；仅测试 MockWebServer 使用（`BiliRepositoryTest.kt:40,44`） | 无「信任所有证书」面 |
| F3 | 合规 | `EncryptedAuthStore.kt:24-32` EncryptedSharedPreferences + MasterKey(AES256_GCM) + SIV/GCM；唯一落盘实现（`AppDataModule.kt:29`）；SESSDATA/bili_jct 0 处明文与日志 | security-crypto 仅 1.1.0-alpha06（A7 记）；换签名/恢复备份解不开时静默登出（`:49-51`） |
| F4 | 合规 | 全仓唯一硬编码口令是 `app/build.gradle.kts:32,34` 的 debug.keystore android/android（**已批准例外**，`:27-28` 注释）；release 走 secrets（`release.yml:74-84`）；SESSDATA/bili_jct 仅出现在键名常量（`EncryptedAuthStore.kt:74-75`）与测试样例 | WbiSigner 的 mixin 表是公开反混淆常量，非密钥 |
| F5 | 合规 | 合并 manifest（`app/build/intermediates/merged_manifests/perfDebug/processPerfDebugManifest/AndroidManifest.xml`）：MainActivity exported=true（:34，唯一 launcher）；PlaybackService :45 exported=false；InitializationProvider :81、Room MultiInstanceInvalidationService :99 exported=false | 库注入的 BluetoothValidationActivity（:54，受 BLUETOOTH_PRIVILEGED）与 ProfileInstallReceiver（:105，受 DUMP）虽 exported=true，均有系统权限保护 |
| F6 | 违规 | 合并 manifest `:24-31` 无 `allowBackup`/`dataExtractionRules`/`fullBackupContent`；`git ls-files "*/res/xml/*"` 为空 → 默认 allowBackup=true 全量备份 | 收藏/历史 DB、DataStore、加密凭证文件随云备份；Keystore 密钥不迁移 → 换机恢复后 `load()` 静默 null（`EncryptedAuthStore.kt:49-51`） |
| F7 | 违规 | `GeetestDialog.kt:83` `MIXED_CONTENT_ALWAYS_ALLOW`；`:101-106` `addJavascriptInterface("Android")` 无来源校验；全仓 `shouldOverrideUrlLoading` 0 命中 → 无域名白名单；`:107` loadDataWithBaseURL 把 passport 源交给本地 HTML；`:145-154` 桥消息可被任意页面伪造 success | 登录验证链路可被中间人注入/篡改；服务端仍校验 seccode，故不直接泄露凭据 |
| F8 | 部分合规 | BODY 级 HTTP 日志不存在：`HttpLoggingInterceptor` 0 命中、catalog 无 logging-interceptor；但 release 仍输出 13 处 Log（`BuildConfig` 引用 0 处、proguard 无 Log 剥离），含 WebView 页面 URL（`GeetestDialog.kt:96`）与 console（`:86`） | 与「release 关闭日志」的守则偏离；内容目前无凭据，属 P3 |
| J1 | 合规 | 13 模块 compileSdk=37 / minSdk=31（各 13 处）、source-target 17 与 jvmTarget JVM_17（各 13 处）全一致；`java -version` → 25.0.4.1；AGP 9.4.1 `ToolsRevisionUtils.<clinit>` = AndroidVersion(37,2) → compileSdk 37 在支持面内；debug/perfDebug APK 均在 | 无 `jvmToolchain` 钉死 JDK（换机/换 JDK 才暴露）；`gradle.properties:8` 的 suppress 项已无必要 |
| J2 | 合规 | `app/build.gradle.kts:56-58`（perfDebug）与 `:70-71`（release）均 minify+shrink；`proguard-rules.pro` 11 行 4 条；合并配置 1358 行 / 170 条 `-keep` / 38 条 `-dontwarn` 全部来自库 consumer rules | 缺行号保留（J10）、缺 Log 剥离（F8） |
| J3 | 部分合规 | APK 内 `assets/dexopt/baseline.prof` 11,299B + `baseline.profm`（依赖库 profile 合并）；`benchmark`/`baselineprofile` 在 settings/toml/各 build 文件 0 命中 → 无 `:benchmark`、无自家 profile 与度量 | 只有「库自带」profile，无 measure 环节 |
| J4 | 违规 | `gradle.properties:7` `android.nonTransitiveRResources` 在 gradle-9.4.1.jar 的 6134 个 class 中 **0 命中**，正确名 `nonTransitiveRClass` 9 命中 → 死配置；`:3` daemon 30s 自杀、`:4` parallel=false、`:5` configureondemand=true、无 `org.gradle.caching`/配置缓存、`:1` Xmx2g | 13 模块串行 + 每次冷配置；`:8` suppressUnsupportedCompileSdk=37 亦无必要（J1 已证 37 受支持） |
| J5 | 违规 | `Select-String implementation("` 在 13 个 build 文件计 **54 处**硬编码坐标：`app:145-146,165-169`、`core/data:29-30,35,37`、`core/media:38,41,45`、`data/bilibili:29-30,35,37-38`、`feature/*` 各若干 | 版本号仍取自 `libs.versions.*.get()`，但坐标散落 13 文件，升库必漏；违背 AGENTS.md「版本统一在 libs.versions.toml」 |
| J6 | 合规 | `release.yml:74-77` 缺 `RELEASE_KEYSTORE_BASE64` 直接 `exit 1`；签名值全走 secrets（`:78-84`）；`app/build.gradle.kts:36-43` release 无硬编码；`keystore.properties` 已 gitignore | debug.keystore 入库为批准例外 |
| J7 | 部分合规 | 实测 `app-debug.apk` 85,007,434B / `app-perfDebug.apk` 6,527,465B；perfDebug 变体本身可作回归手段；但注释仍写「61M→6M」（`app/build.gradle.kts:68`），全仓无体积门禁 | 基线数字过期、无自动回归 |
| J8 | 部分合规 | 16KB 页：APK 内 2 个 .so 的 LOAD `p_align=0x4000`（arm64 实测）；edge-to-edge 走 `enableEdgeToEdge`（`MainActivity.kt:118`）但叠加废弃窗口 API（`:121-129`）；predictive back 已有 NavHost 转场（`AppNavHost.kt:52-53`）但 manifest 未声明 `enableOnBackInvokedCallback`（API 33/34 上不生效）；`AndroidManifest.xml:19` portrait 在 API 36+ sw≥600dp 被忽略 | 行为变更无逐条留档；大屏方向锁依赖系统忽略，见 PART3 |
| J9 | 部分合规 | `MainActivity.kt:118` enableEdgeToEdge 后 `:122-129` 又设 `systemUiVisibility/statusBarColor/navigationBarColor`（@Suppress DEPRECATION）——API 35+ 这些 API 已废弃并忽略，「HyperOS 兜底」在 35+ 不成立 | innerPadding 链路正常（`:229-233`、平板 `MainTabsScaffold`）；两套沉浸式并存属技术债 |
| J10 | 违规 | 合并 R8 配置 `app/build/outputs/mapping/perfDebug/configuration.txt`（1358 行）：`SourceFile\|LineNumberTable` **0 命中**，`-keepattributes` 仅 Annotation/Signature/InnerClasses/EnclosingMethod；mapping.txt（76MB）只在本地 build 目录，`release.yml:86-95` 只上传 apk+sha256 | release 崩溃无行号且 mapping 不归档 → 线上问题基本不可定位 |
| J11 | 违规 | `CONVENTIONS.md:8`（AGP 9.4.0/BOM 2026.08.00/Nav 2.9.8/Room 2.8.4/Media3 1.10.1/Paging 3.3.6）与 `:10`（compile/targetSdk 36）全部过期；`DEVELOPMENT.md:5` 写 37（正确）；`PERF_AUDIT.md:431` 仍称「无混用，好」；CI 无版本漂移校验 | 三份文档三套口径，新人按 CONVENTIONS 会改错 SDK |

### G 媒体与后台 / H 无障碍与国际化

| 编号 | 结论 | 证据（file:line 或命令输出片段） | 备注/影响 |
|---|---|---|---|
| G1 | 合规 | `TingBiliPlaybackService.kt:30-33` 注入 @ServiceScoped Player；`ServicePlayerModule.kt:102` 全仓唯一 ExoPlayer.Builder（`git grep` 仅 1 命中）；`MediaModule.kt:15-16` 对外只暴露 PlayerHandle（MediaController 代理） | Player/MediaSession 确在 Service；VM 侧只拿到 handle，未在 Activity/VM 持有 ExoPlayer。`MediaControllerHandle.kt:61` release 为空实现属有意的单一释放权设计 |
| G2 | 合规 | `TingBiliPlaybackService.kt:35-97` onCreate 建 session（:96）；`:127-132` onDestroy 依次 release session→player→cancel scope→super | ExoPlayer 由 Hilt ServiceComponent 提供（`ServicePlayerModule.kt:37-118`），释放点唯一。备注：服务创建期 `runBlocking` 读 DataStore（`ServicePlayerModule.kt:82`）属 B 类主线程问题，不计入 G2 |
| G3 | 合规 | `TingBiliPlaybackService.kt:125` `onGetSession(...) = mediaSession`（字段，onCreate 单次创建） | 多控制器共享同一实例，未在 onGetSession 内新建 |
| G4 | 合规 | 全仓 `git grep onTaskRemoved` 0 命中；Media3 1.11.1 `MediaSessionService#onTaskRemoved` 默认：`if (!isPlaybackOngoing() \|\| !isAnySessionPlaying()) pauseAllPlayersAndStopSelf()` | "播放中保留、否则停止"正是检查点要求，属框架默认能力，无需覆写。与 G9 叠加：暂停态划掉任务→stopSelf→控制器断连且不重建 |
| G5 | 合规 | `core/media/src/main/AndroidManifest.xml:6-9`（foregroundServiceType=mediaPlayback，exported=false）；`app/src/main/AndroidManifest.xml:6-7` 双权限；`aapt2 dump permissions perfDebug.apk` 输出 FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PLAYBACK；xmltree `foregroundServiceType=0x2` | 已在合并产物层核对（APK 时间戳早于 HEAD 一日，两处源 manifest 与产物一致）。targetSdk 37≥34 时 FGS 类型必填，已满足 |
| G6 | 部分合规 | Media3 默认 `DefaultMediaNotificationProvider`：`.setStyle(MediaStyle)`、`.setVisibility(PUBLIC)`、由 `MediaNotificationManager.onEvents` 随播放态更新；但 `.setOngoing(false)`。全仓 `setMediaNotificationProvider`/`NotificationChannel` 0 命中 | MediaStyle/状态更新/锁屏控制=框架已满足（注明来源：media3 1.11.1 源码）；"不可滑掉"框架默认不满足，我们也没自定义 Provider。另：媒体通知不受 POST_NOTIFICATIONS 限制（media3 源码注释），拒通知权限不算 G6 缺陷 |
| G7 | 合规 | `ServicePlayerModule.kt:108-116` setAudioAttributes(USAGE_MEDIA/AUDIO_CONTENT_TYPE_MUSIC, handleAudioFocus=true)+setHandleAudioBecomingNoisy(true)+setWakeMode(WAKE_MODE_NETWORK)；`TingBiliPlaybackService.kt:40-48` 幂等重复下发 | 请求/失焦暂停/压低音量/拔耳机暂停均由 ExoPlayer AudioFocusManager 默认接管（S16），未自行 requestAudioFocus 属正常用法 |
| G8 | 待验证 | 静态：默认 media3 通知未覆盖（grep 0）+ MediaSession 常驻；通知栏/锁屏能否实际操作需真机 | 见 PART3 |
| G9 | 部分合规 | `PlaybackConnection.kt:51-63` 只挂 future 完成回调，未注册 `MediaController.Listener.onDisconnected`；`:71-76` release 仅测试调用（`PlaybackConnectionTest.kt:22,31`）；`MainActivity.kt:105` 单次 connect；`:57` `future.get()` 无 try/catch | 断连后 Media3 命令变 no-op（MediaController 源码 "The controller is not connected. Ignoring ..."），_controller 仍留死实例→服务被杀后播放按钮静默失效，只能杀进程恢复；连接失败时 future.get 会在主线程抛 ExecutionException |
| G10 | 合规 | `AudioUrlResolver.kt:32-51`（90min 内复用、过期重解析、失败退旧 URL）；`PlayUrlCache.kt:21-26` TTL=90min；`PlaybackEventHandler.kt:96-111` 可恢复错误重试 2 次；`PlayerManager.kt:1135-1151` 删缓存档→惰性 item→prepare→seek→play，由 `ServicePlayerModule.kt:57-69` 的 ResolvingDataSource 现场重解析 | 惰性 URI + 90min TTL < B 站直链约 2h 有效期；冷恢复队列只存 bvid/cid，不存在长命 URL |
| G11 | 待验证 | 静态配置齐全（见 G7：focus/becoming-noisy/wake）；来电、蓝牙断开、他 App 抢焦点、DSP 恢复需真机 | 见 PART3 |
| G12 | 违规 | `PlayerManager.kt:743`（在 :741 try 内、:866 catch）+ `:665`（rebuildTransport 内无 try/catch）；`PlaybackConnection.kt:47` 死代码（全仓无调用方）。Media3 仅在 user-engaged（playWhenReady && READY/BUFFERING）才 startForeground（`MediaNotificationManager#isAnySessionUserEngaged`/`startForeground`） | 复核纠正：不是"4 处无 try/catch"——真实裸调用 1 处 + 1 处死代码 + 1 处已被上层 catch。真正的 P1 是"起了服务但没播放"的 5s startForeground 期限：`:749-752` 早退、`:669` 早退、`:784` cid 解析网络等待，系统会以 "did not then call Service.startForeground" 杀掉进程 |
| G13 | 违规 | `TingBiliPlaybackService.kt:96` 仅 `MediaSession.Builder(this, player).build()`；全仓 `setSessionActivity`/`MediaSession.Callback` 0 命中；`PlaybackConnection.kt` 0 处 onDisconnected；无 onPlaybackResumption | Media3 默认通知 `.setContentIntent(mediaSession.getSessionActivity())`，未设置时返回 null→点通知/锁屏无跳转；系统媒体恢复器也无法续播 |
| H1 | 部分合规 | 125 处 contentDescription（其中 68 处 = null 装饰图，合理）；`MeScreen.kt:384-394` HeaderIconButton：34dp clickable Box + Icon(contentDescription=null)，节点无任何标签 | 唯一确认的无标签可交互控件（Me 页明暗切换），TalkBack 读作无名"按钮"；其余 IconButton 均有 label |
| H2 | 违规 | `PlayerScreen.kt:1009-1012` 自绘进度条触控高 22dp（注释自认）；`MeScreen.kt:387` 34dp clickable；`HomeScreen.kt:534-536` IconButton size 28dp（搜索历史删除）；`MiniPlayerBar.kt:288-291` FilledTonalIconButton 40dp | 均 <48dp。M3 IconButton 的 minimumInteractiveComponentSize 被外层 size() 先行约束，兜不住。进度条是播放页最高频控件，影响最大 |
| H3 | 部分合规 | `Theme.kt:56-57,109-111`：onPrimary=白色判据 luminance≤0.5；默认种子 #FF6699（`PreferencesRepository.kt:308`）亮度 0.331→白字对比度 2.76:1；深色 primary 白字 2.48:1（本次按 WCAG 公式实算） | 低于 4.5:1（普通文本）且低于 3:1（大字/图形）；影响所有默认填充 Button（如 `SettingsScreen.kt:252`）。其余组合逐页扫描见 PART3 |
| H4 | 部分合规 | `PlayerScreen.kt:934-954`：Box combinedClickable 同时给 onClickLabel"收藏到我的最爱/取消收藏…"与 Icon contentDescription"已收藏到我的最爱"，同节点双语义；全仓 `mergeDescendants/clearAndSetSemantics` 0 处（框架 clickable 自动合并） | 会重复/冲突朗读；整页遍历顺序需真机（PART3） |
| H5 | 部分合规 | 固定 dp 高 + sp 文本：`MiniPlayerBar.kt:232` 行高 60dp 装标题+副标题+时间；`DetailScreen.kt:632-639` 68dp 高 Column + maxLines=2 标题；`LoginScreen.kt:397,648` 52dp 按钮；`SpaceScreen.kt:591` 72dp 行 | 2.0x 下两行 bodyMedium 约需 80dp（20sp 行高×2×2）→ 截断/挤压；播放页歌词已按 fontScale 调行数（`PlayerScreen.kt:1202-1209`），属局部达标 |
| H6 | 部分合规 | `app/src/main/res/values/strings.xml:3` 仅 app_name 1 条；主源码中文字面量 792 处 / 138 文件；`stringResource`/`R.string` 0 处；≥9 处 String.format 未带 Locale（MeScreen:499-500、StorageSettings:324、Player:1095/1097/1104、Appearance:285、WbiSigner:139/147） | 中文单语为已拍板决策（计划 §2.2 非目标），不计缺陷；残留是 DefaultLocale 隐患与文案不可校对。需把结论落文档并补 Locale |
| H7 | 合规 | `Theme.kt:36-45` 三模式分支；`:54-88` 品牌色板自带深浅；`:94-106` AMOLED 纯黑覆盖；动态取色仅 SYSTEM+SDK≥31，其余回退 brandedColorScheme | minSdk 31→dynamic 分支恒可达，无低版本回退缺口 |
| H8 | 违规 | `git grep -n 'AccessibilityChecks\|enableAccessibilityChecks'` 0 命中；`app/build.gradle.kts:165` 已有 compose ui-test-junit4 但未启用 ATF | 无自动无障碍门禁；Compose 1.8+ 可在测试中 `AccessibilityChecks.enable()` 低成本接入 |

---

## 6. 待真机 / 工具验证项

> 这些不是「问题」，是「静态无法定论、需要设备或工具」的观测项；修复计划里的验收会用到。

**A 应用质量 / B 性能与流畅度 / I 测试与门禁**

| 编号 | 为什么 | 怎么测 |
|---|---|---|
| T-A2 | 「1s 内出声」依赖网络解析与设备音频路径 | 真机点播 5 次，logcat 过滤 `TingBiliAudio` + 秒表/Perfetto 量 prepare→first frame；同时确认 `PlayerScreen.kt:392` 转圈出现 |
| T-A3 | 锁屏/切后台一致性是运行时行为 | 播放中锁屏→解锁→回桌面→再进；`adb shell uiautomator dump` 对 UI 与 `dumpsys media_session`/`dumpsys activity services` 状态比对 |
| T-A4 | 进程回收恢复需开发者选项 | 开「不保留活动」，播放中回桌面被回收后重进；核对 nav 栈（是否回二级页）、队列与进度（`PlayerManager.kt:240-243,263-284` 应恢复） |
| T-A8 | 通知常驻/不可滑掉/锁屏可控是系统行为 | 真机播放后下拉通知、锁屏、拒绝通知权限各验一次；记录通知渠道名与重要性 |
| T-A9 | 大屏旋转后的布局与状态保持 | 600dp 强制或平板：旋转后看 nav 选中态、播放进度是否连续，Login/Player 页是否硬拉伸 |
| T-B1 | 冷启动 2s 是量化指标 | `adb shell am start -W -n cn.debubu.tingbili/.MainActivity` ×10（首装/二次启动各一组）；Perfetto 比首帧与 `hasAnyFavorite` 完成时刻（`MainViewModel.kt:53`） |
| T-B2 | 帧预算需 gfxinfo/Perfetto | perfDebug 包 + `adb shell dumpsys gfxinfo cn.debubu.tingbili framestats`：首页滚动 30s、播放页 60s；janky>5% 才排期 |
| T-B5 | Coil3 实际解码尺寸与流量 | 冷装首屏 `dumpsys netstats detail` + `du -sh cacheDir/coil3_disk_cache`，改档前后各一组对比 |
| T-B10 | 索引收益需实测耗时 | 插 1000 行历史后 Room `QueryCallback` 计时或 Perfetto；单次 >3ms 或播放期每 10s 出现即成立 |
| T-I8 | Robolectric 4.17 是否支持 sdk 37、缺 properties 模块的实际行为 | 待 Lead 释放构建后跑 `./gradlew :feature:player:testDebugUnitTest`（本轮禁跑）；看是否报 unsupported SDK 或静默用 37 |
| T-I4/I9 | lint 首次全量告警规模 | 用 Lead 后台 lint 结果统计告警类别，判断 baseline 需导多少条、哪些是 A/B/I 类真问题 |

**C Compose 专项 / D 架构与状态管理**

| 编号 | 为什么 | 怎么测 |
|---|---|---|
| C-02 | 含 index 的 key 到底造成多少 item 状态/复用损失，与「页内重复 BV」收益谁大，静态无法权衡 | 真机：首页滚动中途下拉刷新 + 加载第 2 页，Layout Inspector 看 TrackGridCard 重建计数；去掉 index 后同场景对照 |
| C-13 | Kotlin≥2.0.20 默认 strong skipping，不稳定参数 equals 相等仍可跳过；实际有多少 composable 真的没跳过、List 参数多久变一次，需编译器报告 | 临时加 `composeCompiler { reportsDestination = project.layout.buildDirectory }` 出稳定性/report（不提交），或 Layout Inspector 打点计数 |
| C-01/C-04/C-14 | 1Hz/2Hz 重组是否真造成可感知掉帧（材料只推断，未测） | perfDebug 包播放中依次停在播放页/详情/收藏夹/我的，`dumpsys gfxinfo cn.debubu.tingbili framestats` 比 jank%；与暂停态对照 |
| D-10 | 「先 consumeMessage() 再 showSnackbar()」：key 变 null 会让 LaunchedEffect 重启，可能取消正在显示的 snackbar（潜在吞提示） | debug 包连续触发两次收藏提示，观察 snackbar 是否闪一下即消失；改「show 后 consume」或 Channel 对照 |
| D-07 | 硬编码 Dispatcher 是否真的妨碍现有测试（现有用例可能已用 runTest/真实 IO 绕过） | 读 core:data/data:bilibili 测试里 Dispatcher 相关用例；尝试给一个 Repository 注入 TestDispatcher 跑一次验证可行性（不提交） |

**E 数据层 / F 网络与安全 / J 构建与发布**

| 编号 | 为什么 | 怎么测 |
|---|---|---|
| E7 | 索引退化只证明 SQL 需扫表，真实耗时取决于历史行数与写入频率 | 真机开 Room `QueryCallback` 计时 + Perfetto 看播放中 10s 一次写历史触发的重查次数 |
| F6 | 备份默认策略是平台行为，静态只能证明「未声明」 | `adb shell bmgr backupnow cn.debubu.tingbili` + `dumpsys backup` 确认 databases/shared_prefs 是否入集；换机 `bmgr restore` 后看是否掉登录 |
| F7 | 混合内容是否实际可注入、桥是否被跨域页面拿到，取决于极验页真实子资源 | 真机 + mitmproxy 把子资源降 http 看是否加载；或用 `shouldInterceptRequest` 探针记录导航域 |
| F3 | alpha06 的 KeyStore/Tink 行为在具体 ROM、换签名、系统升级后的失败率 | 换签名覆盖安装 + 系统升级后启动，看 `EncryptedAuthStore.load()` 是否返回 null（埋点） |
| J1 | 是否有依赖的 `minCompileSdk` > 37（当前产物存在，但依赖升级后会变） | 跑 `./gradlew :app:dependencies`，或扫缓存 AAR 的 `aar-metadata.properties` 取 max minCompileSdk |
| J3 | 库自带 profile 对启动的实际收益 | `:benchmark` macrobenchmark 对比 install/clearProfile 的 startupTimingMetric |
| J8 | API 36+ 大屏忽略方向锁、predictive back、edge-to-edge 的真实表现 | 开发者选项强制 600dp+ 或平板真机跑登录/播放页；Android 16 设备验证返回手势动画 |
| J10 | 崩溃堆栈是否真的缺行号（取决于 R8 实际输出，而不只是合并配置） | 装 perfDebug 包触发一次 crash，看 `adb logcat` 堆栈与 mapping 反混淆结果 |

---
核验方法留痕（可复现，全部只读）：`git grep -nE …`、`git show 9ec0990:…HistoryDao.kt`、`Get-ChildItem app/build/outputs/mapping/perfDebug`、`Select-String configuration.txt -Pattern SourceFile`、解包 `app-perfDebug.apk` 读 ELF program header、解包 `gradle-9.4.1.jar` 搜属性名、`javap -c` 读 `ToolsRevisionUtils.<clinit>`。

**G 媒体与后台 / H 无障碍与国际化**

| 编号 | 为什么 | 怎么测 |
|---|---|---|
| G-08 | 通知栏/锁屏是否真的常驻可控制、后台 10min 后是否仍在，只能真机看 | 真机播放→锁屏/下拉通知→点按控制；`adb shell dumpsys notification --noredact` 查是否有 media style 与 session token；切后台 10min 再验 |
| G-11 | 来电/他 App 抢焦点/拔耳机/蓝牙断开后的暂停与恢复属运行时行为 | 真机：播放中来电、微信语音播报、拔耳机、关 BT；观察是否暂停/压低、结束后是否恢复；配合 `dumpsys audio` 看 focus 栈 |
| G-12 | 5s startForeground 期限的触发需要构造慢路径，静态只能证明窗口存在 | 飞行模式+搜索页起播（cid 解析走网络）、或注入 DataStore 慢读；`adb logcat -b crash` 搜 `did not then call Service.startForeground` / `ForegroundServiceDidNotStartInTimeException` |
| G-06 | 通知被滑掉后"暂停态不再出现/播放态是否复现"依赖系统通知与会话事件时序 | 真机：播放中滑掉通知看是否复现；暂停后滑掉再点播放看是否重新出现；`dumpsys notification` 对比 |
| G-09 | 服务被停止后 controller 是否永久失效、是否有系统级重启路径 | `adb shell am stopservice -n cn.debubu.tingbili/cn.debubu.tingbili.core.media.TingBiliPlaybackService` 后回 App 点播放；或暂停态划掉最近任务再回 App |
| H-03 | 逐页/逐控件的对比度组合太多，静态只算了品牌主色这一对 | Accessibility Scanner 扫播放页/首页/设置/登录；深浅 + 深灰 + AMOLED 三种主题各一轮 |
| H-04 | 语义合并后的实际朗读与遍历顺序只能 TalkBack 走查 | TalkBack 逐页：播放页（收藏/进度条/辅助栏）、列表行、底栏、mini 条；重点听有无重复与跳序 |
| H-05 | 200% 字体下各页实际截断/重叠需真机渲染 | 系统字体最大 + 显示大小最大，走查 mini 条、详情行、登录页、播放页辅助栏、底栏 4 Tab、设置页开关行 |


---

## 7. 用户决策项、不适用项与文档修正

### 7.1 你已拍板的决策（本轮按此执行）

| 决策 | 影响 | 落地动作 |
|---|---|---|
| **数据库允许破坏性更新**（只有一个测试用户） | E1 判「合规（决策允许）」、E8（迁移测试）判「不适用」 | 改 `docs/CONVENTIONS.md:30` 与 `docs/DEVELOPMENT.md:50` 的「禁止破坏性迁移」条款，改成「单人测试期允许 `fallbackToDestructiveMigration`；对外发版前再补迁移与测试」；顺手删掉 `core:data` 里 3 个模块声明但 0 使用的 `room-testing` 依赖 |
| 性能不重开诊断 | B 类只做回归核对 | 已按此执行：B-01/B-02/B-03/B-04 都是「新增或回归」，不是重复报告老问题 |

### 7.2 仍需你决定的两件事（不影响本轮报告，影响修复计划）

1. **国际化**：现状是 `strings.xml` 只有 1 条、主源码 792 处中文字面量、`stringResource` 0 处。建议**正式记录「中文单语」决策**（写进 `docs/CONVENTIONS.md`），只补 4 处 `String.format` 的 `Locale` 与 2 处 Compose `NonObservableLocale`，不做资源抽取。
2. **是否引入新工具链**：lint（建议必上）/ detekt（可选）/ kover 覆盖率（可选）/ `:benchmark` + Baseline Profile（建议单独立项）。这些会改构建文件与 CI。

### 7.3 不适用项（已核实，不必再查）

| 编号 | 结论 | 理由 |
|---|---|---|
| E8 | 不适用 | 破坏性更新策略下无迁移测试需求；`room-testing` 属死依赖 |
| A7 部分 | 记录 | `security-crypto 1.1.0-alpha06` 是唯一 alpha 依赖，但当前无可替代方案（`MasterKey` 单数 API 只有 1.1.0+） |
| G4 | 合规 | Media3 默认语义即「播放中保留、否则停止」，无需自写 `onTaskRemoved` |
| J8 16KB page | 合规 | APK 内 2 个 `.so` 实测 `LOAD p_align=0x4000` |
| F2 | 合规 | 0 处 TrustManager/主机名校验绕过 |
| F4 | 合规（例外） | `app/debug.keystore` 的 android/android 是已批准例外；release 密钥走 Secrets |
| F5 | 合规 | 合并 manifest 中业务组件仅 launcher 导出；media3/profileinstaller 的导出组件受系统权限保护 |

### 7.4 文档漂移修正清单（改代码不如改文档）

| 文档 | 现状 | 应改为 |
|---|---|---|
| `docs/CONVENTIONS.md:8-10` | AGP 9.4.0 / BOM 2026.08.00 / Nav 2.9.8 / Room 2.8.4 / Media3 1.10.1 / Paging 3.3.6 / SDK 36 | 以 `libs.versions.toml` 为唯一事实源，删掉硬编码版本表；SDK 写 37 |
| `docs/CONVENTIONS.md:25` | 「`isMainTabRoute()` 控制底栏」 | 实际是 `isMainTabDestination`；`isMainTabRoute/mainTabRouteOf` 是死代码 |
| `docs/CONVENTIONS.md:29` | 「历史键 bvid」 | 已改为一个播单一行、主键 (sourceType,sourceId) |
| `docs/CONVENTIONS.md:31` | 「MVP 无登录」 | 已实现登录（扫码/短信/密码/极验/Cookie） |
| `docs/CONVENTIONS.md:30` / `DEVELOPMENT.md:50` | 「禁止破坏性迁移」 | 改为「测试期允许，发版前补」 |
| `docs/DEVELOPMENT.md:31-44` | 模块清单缺 `feature:detail`、无 `core:bili-ui` | 与 `settings.gradle.kts` 对齐 |
| `docs/ARCH_REVIEW.md:9-13,39` | 依赖图含已删除的 playlist/search；「7 个 Migration」 | 现状 13 模块、3 个 Migration，追加落地说明 |
| `docs/PERF_AUDIT.md:431,433` | 「版本无混用」「release 未开 shrink」 | 追加文末进度，不改正文 |

---

## 8. 下一步：修复计划

本轮结论会进入 `docs/FIX_PLAN.md`，按「先门禁、再合规、后重构」排序，每批有明确文件范围与验收命令：

| 批次 | 主题 | 覆盖问题 | 预估 |
|---|---|---|---|
| F1 | 门禁与工具链（最高性价比） | I-01 / I-02 / J-04 / J-05 / J-10 / J-11 + lint 基线 | 0.5d |
| F2 | 稳定性与崩溃面 | D-08 / G-12 / G-13 / G-09 / B-02 | 1d |
| F3 | 合规与安全 | F-07 / F-06 / J-08 / J-09 / H-01 / H-02 | 1d |
| F4 | 性能回归 | B-03 / C-14 / C-13 / B-04 / E-07 / C-01 / C-04 | 1.5d |
| F5 | 架构与规范 | D-01 / D-07 / C-09 / C-12 / C-02 / H-05 / H-03 | 2d |
| F6 | 测试补强与文档 | I-03 / I-04 / I-05 / 文档漂移 | 1d |

详见 `docs/FIX_PLAN.md`（下一步产出）。

---

## 附录 A 本轮 lint 基线（`./gradlew lintDebug --continue`）

**105 条 = 80 Error + 25 Warning**；不加 `--continue` 时会在 `core:media` 中断构建。

| 规则 | 数量 | 严重度 | 处置建议 |
|---|---|---|---|
| `UnsafeOptInUsageError`（media3 `@UnstableApi`） | 78 | Error | 统一加 `@OptIn(UnstableApi::class)` 或改稳定 API；属工程纪律，不是逐条改逻辑 |
| `UseTomlInstead` | 7 | Warning | `app/build.gradle.kts` 硬编码坐标 → 补版本目录别名（对应 J-05） |
| `DefaultLocale` | 4 | Warning | `String.format` 补 `Locale` |
| `NewerVersionAvailable` | 4 | Warning | 信息项，升级时一并处理 |
| `ModifierParameter`（Compose） | 3 | Warning | 组件补 `modifier` 参数（对应 C-10） |
| `ObsoleteSdkInt` | 2 | Warning | 删无用 SDK 判断（minSdk 31 已覆盖） |
| `UseKtx` | 2 | Warning | 换 KTX 扩展 |
| `NonObservableLocale`（Compose） | 2 | Error | 用 `LocalLocale` 或传参（对应 C-01/H-06） |
| `SetJavaScriptEnabled` | 1 | Warning | WebView 场景必要，记录（对应 F-07） |
| `DiscouragedApi` / `LockedOrientationActivity` | 各 1 | Warning | 对应 A-02 / J-08 方向锁 |

模块分布：`core:media` 74、`app` 14、`feature:mine` 9、`feature:player` 3、`feature:home` 2、其余各 1。

## 附录 B 审查材料

| 文件 | 内容 |
|---|---|
| `docs/CODE_REVIEW_PLAN.md` | 审查计划与 100 个检查点定义 |
| `temp/review/SEC-ABI.md` | A/B/I 类核验（28 条检查点、18 条问题） |
| `temp/review/SEC-CD.md` | C/D 类核验（24 条、14 条问题） |
| `temp/review/SEC-EFJ.md` | E/F/J 类核验（27 条、13 条问题） |
| `temp/review/SEC-GH.md` | G/H 类核验（21 条、11 条问题） |
| `temp/review/lint-summary.md` | lint 基线原始统计 |
| `temp/recon/*.md` | 侦察阶段 4 份材料（含官方规范来源清单 30 项） |

> 所有 `temp/` 材料不入库；`git status` 只有 `docs/` 下两份文档。

---

## 9. 执行后复核（闭环，2026-11-01）

> 本节是报告出具后按 `docs/FIX_PLAN.md` 执行完 F1–F6 的复核结果；**原始结论保持为基线快照**，本节只记录「改了什么、还剩什么」。
> 基线：`2900f56` → 复核时 HEAD：`e2a421c`（26 个提交）。

### 9.1 指标对照

| 指标 | 审查时（基线） | 复核时 | 证据 |
|---|---|---|---|
| `lintDebug` | 105 条（80 Error），跑一次中断构建 | **0 条**（13 模块 errors=0 warnings=0），已进 CI | `lint.xml` + 各模块 0 命中 |
| CI 实际执行单测 | 19/50 文件、137/346 用例 | **360 用例全跑**（13 模块 `--continue`） | `testDebugUnitTest` |
| 主线程读 DataStore | 3 处 `runBlocking` | 0（预热 + 内存镜像 + 80ms 有界兜底） | `PreferencesRepository.prime/cacheMaxMbNow` |
| 播放页 1Hz 整页重组 | 顶层 collect `progUi` | 订阅下沉到 3 个叶子组件 | `ProgressBarSection`/`PlayerAuxBarSection`/`SleepTimerSheetSection` |
| 平板壳 2Hz 整树失效 | `MainTabsScaffold` 同作用域读 tick | 下沉到 `TabletMiniPlayer` | `MainTabsScaffold.kt` |
| 硬编码依赖坐标 | 47 处 | **0** | `Implementation("` 命中 0 |
| 硬编码 IO 调度器 | 19 处（可注入 0） | 0（4 类改为注入，余 4 处为语义必需） | `DispatcherModule` |
| 数据库索引 | 历史/收藏表无索引 | 3 个索引，DB v16 | `HistoryDao`/`FavoriteEntities`/`TingBiliDatabase` |
| 触控目标 <48dp | 4 处 | 0 | 播放进度条/Me/Home/mini |
| 零测试模块 | 3 个（detail/space/bili-ui） | 1 个（space 已补 6 例） | `SpaceViewModelTest` |
| 路由测试覆盖 | 11/21 且无守卫 | 21/21 + 元测试守卫 | `RouteCoverageTest` |

### 9.2 P0 / P1 复核

| 编号 | 结论 | 状态 | 提交 / 说明 |
|---|---|---|---|
| **D-08 / B-01**（P0）主线程 `runBlocking` 读 DataStore | 违规 | ✅ 已修 | `5f7e281`；预热 + 镜像 + 有界兜底 |
| **I-01** CI 测试回退永不触发 | 违规 | ✅ 已修 | `2a714cc`；改 `--continue` 全量，门禁真实覆盖 360 例 |
| **I-02** 无 lint/静态分析门禁 | 违规 | ✅ 已修 | `2a714cc` + `lint.xml`；lint 0 并进 CI |
| **B-03 / C-14** 播放页与平板壳 1–2Hz 重组 | 违规（回归） | ✅ 已修 | `e36502a`；真机重组计数待复核 |
| **B-02** 启动首帧门禁查 Room | 违规 | ✅ 已修 | `a1732f4`；偏好标记 + 一次性回填 |
| **G-12** 先起服务后起播（5s 被杀） | 违规 | ✅ 已修 | `5f7e281`；删除手动 `startForegroundService`，交给 Media3 |
| **F-07** WebView 混合内容 + 桥无校验 | 违规 | ✅ 已修 | `9cee8c6`；**真机登录待验** |
| **H-02** 触控目标 <48dp（4 处） | 违规 | ✅ 已修 | `63a04e6`；真机触控待验 |
| **C-13** 稳定性注解/编译器报告缺失 | 部分合规 | ⏸ 未做（有理由） | strong skipping 已兜底；编译器报告在 AGP9 内置 Kotlin 下未产出，盲加注解风险大于收益（详见 FIX_PLAN §6.4） |
| **D-01** feature VM 直连 DAO/`BiliApi` | 违规 | ⏸ 未做（等拍板） | 需改 7 个 VM + 10 多个测试，属结构性重构，F5a |
| **E-07** 历史/收藏索引缺失（P2 但影响 P1 现象） | 违规 | ✅ 已修 | `4d48250`；真机 SQL 耗时待测 |

### 9.3 仍然开放的事项（及原因）

| 事项 | 原因 | 解除条件 |
|---|---|---|
| 全部真机验证（8 批改动） | 测试设备锁屏/Doze，未擅自安装唤醒 | 用户解锁并授权装机 |
| F3d 方向锁（A-02 / J-08） | 产品取舍：删 `portrait` 还是保留 + 文档说明 | 用户拍板 |
| F3e 沉浸式 API 收敛（J-09） | 历史上 HyperOS 黑条，必须逐页真机看 | 有设备时执行 |
| F5a 仓储层（D-01） | 触碰面最大的一批（7 VM + 10 测试） | 用户确认后执行 |
| F5d 颜色 token（C-12，49 行硬编码色） | 视觉回归必须真机复核 | 有设备时执行 |
| F4c `@Immutable` | 缺少编译器报告做量化 | Compose 编译器报告可用后 |
| F6 `feature:detail` 补测 | 需搭 ~100 行 `PlayerManager`/`BiliRepository` 脚手架 | 可随时执行（纯工作量） |

### 9.4 复核结论

- **所有 P0/P1 中，除 D-01（结构性，待拍板）与 C-13（工具链受限）外，全部修复并进门禁**。
- **规范类问题已从「靠自觉」变成「靠门禁」**：lint 0 + 360 用例 + 路由守卫 + 破坏性升级/索引规则写进 `AGENTS.md`。
- **未上机验证是当前最大风险**：8 批改动涉及播放服务启动方式、启动落地页、登录链路、触控与字体适配，静态证据充分但缺真机回归。
