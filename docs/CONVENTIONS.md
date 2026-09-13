# TingBili 团队约定（详细版）

根 `AGENTS.md` 是地图，本文件是细节。冲突时以根文件为准。
以下都是“默认去哪验证”，不是“每台机器必须一样”，动手前先对照仓库确认。

## 1. 工具链

- `gradle/libs.versions.toml`：Kotlin 2.4.20、AGP 9.4.0、Compose BOM 2026.08.00、Navigation 2.9.8、Hilt 2.60.1、Room 2.8.4、Media3 1.10.1、Paging 3.3.6。升版本只改这里。
- `gradle.properties`：`Xmx2g`、`parallel=false`、`configureondemand=true`。Agent 单次构建加 `--no-daemon`，避免僵尸 Daemon。
- `compileSdk 36 / minSdk 31 / targetSdk 36`，`jvmTarget 17`。改这些是正式 PR，不是修构建的手段。

## 2. 模块

`app` 聚合一切。`feature:*` 只能依赖 `core:*` + `data:bilibili`。`core` / `data:bilibili` 不依赖 `feature` / `app`。
新增 `feature:xxx` 四件套：`settings.gradle.kts` 注册 + `feature/xxx/build.gradle.kts` + `app` 依赖 + 路由注册。

## 3. 架构

`ViewModel(@HiltViewModel) → Repository/Dao → Result(Success/Error) → StateFlow → Compose`。
加注入前先看 `AppDataModule / MediaModule / ServicePlayerModule` 有没有现成绑定。JSON 只用 kotlinx-serialization。

## 4. 导航（B 方案）

Tab（`MainTabsRoute`：首页/听单/历史/设置，带底栏）与全屏页（搜索/播放/详情，无底栏）分离。
路由唯一在 `Routes.kt`（全 `@Serializable`）；宿主唯一 `AppRootNavHost`；`isMainTabRoute()` 控制底栏；手机 `BottomNavWithCenterPlayer`、平板 `MainTabsScaffold`（NavigationSuite）。旧 `AppNavHost(innerPadding)` 已废弃。

## 5. 播放 / 数据

- 唯一真相源 `PlayerManager.state: StateFlow<PlaybackState>`，UI 不碰 ExoPlayer。经 `PlaybackConnection（MediaController → 播放 Service）` 间接驱动，ExoPlayer 归 Service 所有。历史键 `bvid+cid`、约 1s 节流；冷启动只恢复、不自动播。
- `Track` 去重键 `bvid+cid`；`BV → List<Track>` 走 `ViewDto.toTracks()`。Room `exportSchema=false`，升级手写 `Migration`。DataStore（`PreferencesRepository`）新增键要读写成对、给默认、做钳制。字幕只走 `SubtitleParser + LyricState`。
- 网络出口统一 `BiliRepository`，`feature` 不直调 `BiliApi`。MVP 无登录。不打 buvid / wbi 日志。

## 6. 界面 / 测试 / Git

- 主题只用 `TingBiliTheme(dynamicColor)`；自适应走 `NavigationSuiteScaffold`；`enableEdgeToEdge() + innerPadding` 必处理。分页抄 `HomeViewModel` 模板（pageSize 20、`cachedIn` + `WhileSubscribed(5000)`）。
- 测试在各模块 `src/test`（`*Test`）；时间逻辑用虚拟时间。跑受影响的层，不必每次全量。
- 分支 `codex/<简称>`，提交 `feat/fix/docs/test/refactor(范围): 简述`，禁直推 master。PR 前：门禁绿 + 相关单测绿 + `git status` 干净。大重构在 `docs/` 下留设计说明（历史 superpowers 目录已删除，不再新增）。
