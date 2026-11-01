# TingBili 代码审查计划（v1 · 待拍板）

> 状态：**计划阶段，等你确认范围与取舍后再开工**。本文只定义「审什么、怎么审、分几批、产出什么」，不含最终结论。
> 基线：`master @ 2900f56`（feat: 收藏系统重构，播放与历史记录升级），2026-11-01。
> 依据来源：Android 官方质量/性能/架构/安全/无障碍/测试/媒体规范（30 个官方页面，见附录 A）+ 平台行为变更（API 36/37）+ 仓库自有约定（`AGENTS.md`、`docs/CONVENTIONS.md`）。
> 中间材料：`temp/recon/`（4 份侦察报告，不入库）；正式结论将写到 `docs/REVIEW_REPORT.md`。

---

## 0. 一页摘要

**这个项目现在是什么样**：13 个 Gradle 模块、151 个主源码文件 / 21,983 行 Kotlin、50 个测试文件 / 346 个 `@Test`、单 Activity 全 Compose、minSdk 31 / targetSdk 37、R8 后 6.2 MiB。已经做过三轮高质量的专项审计（性能、架构、播放核心）并封版落地，所以**本计划不重开那三轮**，只做两件事：

1. **规范符合性**：拿官方规范逐条比对代码（含团队约定、平台行为变更、安全隐私、无障碍、测试与质量门禁）；
2. **性能回归核对**：只验证「已修的没坏 + 重构引入的新债」，产出确认清单而不是新诊断。

**怎么审**：自动扫描（lint/探针）→ 人工走查（按模块 + 横切）→ 真机/运行时取证（只在需要定论处）→ 合规取证（manifest/aapt/备份/权限）。

**分几批**：6 批、约 7.5 人日（不含整改），每批有明确退出条件：

| 批次 | 主题 | 预估 | 关键产出 |
|---|---|---|---|
| B0 | 基线扫描 | 0.5d | 机器可复现的违规清单 |
| B1 | 质量门禁与构建 | 1d | lint/CI/R8/版本目录整改清单 |
| B2 | 平台合规与安全 | 1.5d | Manifest/FGS/备份/Media3/权限 |
| B3 | 架构与数据层 | 1.5d | 分层/Result/Room 迁移与索引 |
| B4 | UI 规范与无障碍 | 1.5d | Compose/a11y/i18n 决策/代码规范 |
| B5 | 性能回归核对 | 1d | 已修确认 + 回归项 |
| B6 | 汇总与规则回写 | 0.5d | `docs/REVIEW_REPORT.md` + 规范化 backlog |

**预审已经看到的 5 个高置信问题**（详见 §9，均为静态证据，正式结论待复核）：

1. **P1** CI 门禁半失效：`ci.yml:63` 的 `A B || 全量` 回退永不触发，56 个测试文件只有 21 个真正在 CI 执行。
2. **P1** 数据库破坏性兜底：DB 到 v15，但只有 12→15 三个迁移 + `fallbackToDestructiveMigration(true)`，与「禁止破坏性迁移」约定直接冲突。
3. **P0** 三处 `runBlocking` 在主线程读 DataStore 首值（首图/播放服务创建/ExoPlayer 创建）。
4. **P1** 播放入口崩溃面：4 处 `startForegroundService` 无 try/catch，后台路径可抛 `ForegroundServiceStartNotAllowedException`。
5. **P1** 全仓 0 处 `@Immutable/@Stable` + 0 处 `derivedStateOf`：2026-10-23 修好的「播放 tick 隔离」在平板分支与播放页顶层被重新抵消。

**需要你拍板的 8 件事见 §8**（尤其是「只出报告 vs 边审边修」「性能要不要重开」「是否引入 lint/detekt/baseline profile」）。

---

## 1. 项目画像（实测，不是描述）

### 1.1 规模与结构

| 项 | 实测值 | 证据 |
|---|---|---|
| Gradle 模块 | **13**：`app` + `core:ui/data/media/bili-ui` + `data:bilibili` + `feature:home/favorites/history/player/mine/detail/space` | `settings.gradle.kts:29` |
| 主源码 | 151 个 `.kt` / **21,983 行** | `git ls-files` 统计 |
| 测试 | 50 个 `.kt` / 6,970 行 / **346 个 @Test** | 同上 |
| 设备级测试 | **0**（无 `androidTest`，`testInstrumentationRunner` 是死配置） | `git ls-files '*src/androidTest*'` 空 |
| 最大文件 | `PlayerScreen.kt` 1978 行、`PlayerManager.kt` 1110、`LoginScreen.kt` 949、`HomeScreen.kt` 749 | 行数统计 |
| APK | debug **85.0 MB**（81.1 MiB）；perfDebug **6.53 MB**（6.22 MiB） | 构建产物实测 |

### 1.2 技术栈与版本（唯一事实源：`gradle/libs.versions.toml`）

Kotlin **2.4.20** · AGP **9.4.1** · Gradle **9.8.0** · KSP 2.3.12 · Compose BOM **2026.09.00** · Navigation 2.10.2 · Hilt 2.60.1 · Room 2.8.5 · Media3 1.11.1 · Retrofit 3.0.0 · OkHttp 5.5.0 · Coil 3.6.3 · Paging 3.5.1 · DataStore 1.2.1 · security-crypto **1.1.0-alpha06**。
构建 JDK **25**，字节码目标 **17**；`compileSdk/targetSdk = 37`、`minSdk = 31`。

> ⚠️ 三处口径已经不一致：`docs/CONVENTIONS.md:8-10` 写 AGP 9.4.0 / BOM 2026.08.00 / Room 2.8.4 / Media3 1.10.1 / Paging 3.3.6 / targetSdk 36；`docs/DEVELOPMENT.md:5` 写 targetSdk 37（正确）；`docs/PERF_AUDIT.md:431` 还称「版本无混用」。**这本身就是 B1 的整改项**。

### 1.3 依赖方向（约定 vs 实际）

约定：`feature:*` 只能依赖 `core:*` + `data:bilibili`，feature 之间禁止互赖。实测：**feature 之间 0 互赖（合规）**；但存在两处方向瑕疵——`core:ui` 用 `api` 把 `core:data` 透传给所有 feature（`core/ui/build.gradle.kts:26`），`data:bilibili` 反向依赖 `core:media` 实现其 resolver 接口（`data/bilibili/build.gradle.kts:25`）。

### 1.4 既有审计资产（决定「不重复审什么」）

| 文档 | 内容 | 状态 |
|---|---|---|
| `docs/PERF_AUDIT.md`（561 行） | 18 步全量性能审计 + 8 段落地进度 | **已封版**：正文冻结，Top10 10/10 销账；明确遗留「Baseline Profile 另开任务」 |
| `docs/ARCH_REVIEW.md` | 依赖图、分层倒置、God Module、双真相源、缓存四国杀 | 落地进度至 2026-10-24，Top5 实质收官 |
| `docs/PLAYBACK_CORE_REVIEW.md` | PlayerManager 六职责拆解、Media3 标杆对照、迁移步骤 | A 全拆已执行 |
| `docs/AUDIO_PIPELINE.md` / `AUDIO_QUALITY.md` / `HISTORY.md` / `PLAYBACK_URL_RESOLUTION.md` 等 | 领域设计文档 | 有效 |
| `docs/CONVENTIONS.md` / `docs/DEVELOPMENT.md` | 团队约定 / 开发指南 | 已有漂移（见 §1.2、§9） |

**结论：本计划把性能降级为「回归核对」，把规范符合性升为一等目标。** 三份老审计里已判定「明确不做」的 8 项（memoryCacheKey、歌词两步滚动、Home 双 Grid 等）**不再重开**，只核对它们是否仍成立。

---

## 2. 审查目标与非目标

### 2.1 目标（可验收）

1. 产出一张**逐条可判定的规范检查表**（§4，100 条），每条标注：是否合规、证据 file:line、规范出处、影响面、整改级别与成本。
2. 产出**分级整改 backlog**（P0→P3），并区分「必须改 / 建议改 / 记录为已知债」。
3. 把**已经过期的团队约定与文档**收敛回单一事实源（`libs.versions.toml` 为准）。
4. 给「规范类问题」装上至少**一条自动闸门**（lint 或等价探针进 CI），防止整改完成后重新腐化。

### 2.2 非目标（明确不做，避免跑偏）

| 不做 | 理由 |
|---|---|
| 重开 `PERF_AUDIT` 正文诊断 | 已封版且 10/10 销账，重开是浪费；只做回归核对 |
| 重写架构 / 拆 `PlayerManager` | `PLAYBACK_CORE_REVIEW` 已完成拆分，剩余是渐进式重构 |
| 多语言化（抽 792 处中文字面量） | 中文单语产品，属产品决策；本计划只要求「结论落文档 + 补 Locale」 |
| UI/视觉改版、交互重构 | 不属于「是否符合规范」 |
| 上架 Play 商店所需的行政项 | 本项目 APK 直发；对应条目降级为「本地等价手段」 |
| 逆向分析 B 站接口协议 | 只审「有没有按规范处理异常/风控/降级」 |

### 2.3 性能的特殊定位

`PERF_AUDIT` 的结论是「已修 + 已上机验证」，但 2026-11-01 的收藏系统重构（`2900f56`）改动了历史存储、收藏 DAO 与播放状态，**存在回归面**。因此 B 类检查点（§4-B）只回答三个问题：

1. 2026-10-23 修的那批（历史 10s 节流、播放页拆三流、槽位隔离、Paging、theme remember）**是否还在**？
2. `2900f56` 引入了哪些**新**的主线程阻塞 / 全表扫描 / 1Hz 重组？
3. 「明确不做」的 8 项，现在是否变成必须做（例如 Coil 档位结论被证伪）？

---

## 3. 审查依据（三层，缺一不可）

### 3.1 L1 平台规范（官方文档，附录 A 有 30 个 URL）

核心七份：**Core App Quality**（功能/UX/性能/稳定性的验收基线）、**Performance overview + Render vitals**（帧预算）、**Compose performance/best practices**（重组、稳定性、lazy）、**Architecture recommendations**（分层/UDF/lifecycle-aware 收集）、**Security best practices**（TLS、凭据、导出、WebView）、**Accessibility**（语义、触控目标、缩放）、**Media3 background playback + FGS types + audio focus**。

### 3.2 L2 仓库约定

`AGENTS.md`（命令、地图、环境、规则）、`docs/CONVENTIONS.md`（分层/导航/播放数据/界面测试/Git）。**约定与官方规范冲突时以约定为准**（更严格者优先），并把冲突点记进报告。

### 3.3 L3 平台行为变更（本项目最容易被忽略的一层）

`minSdk 31 → targetSdk 37` 跨了 7 个行为版本，重点：API 33 通知运行时权限、**API 34 前台服务类型与启动限制**、API 35 edge-to-edge 强制、**API 36 大屏忽略方向/尺寸限制 + predictive back 强制迁移**、API 37 定向变更。specs 的 J8 就是逐条比对这张表。

> **来源可达性说明**：本机网络下 `developer.android.com` 不可达，侦察阶段全部改用官方镜像 `developer.android.google.cn`（同 devsite、同路径、同正文）抓取，附录 A 已标注；另有 5 个页面 404（`media3/session` 总览、`app-optimization`、`app-baseline-profiles`、`paging/overview`、`api-guidelines/app-guidelines`），已用等价页替代或标记「待验证」。

---

## 4. 检查点总表（10 类 · 100 条）

读法：**编号**（AV/A…J）｜**检查点**（能一句话判定）｜**怎么验**（工具/命令/看哪里）｜**依据**（S# 对应附录 A）｜**级别**（违规时的默认严重度；现场可上调下调）。
带 ★ 的是侦察阶段补充、官方清单里没有但本项目必须查的条目。

### A 应用质量与功能（9 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| A1 | 每个异步界面有 loading/空/错误三态与重试入口，无白屏无死转圈 | 逐 Screen 看 UiState 分支 | S1 | P2 |
| A2 | 点播后 1s 内出声或给出「准备中」指示 | 真机 + PlayerManager 状态流转 | S1 | P1 |
| A3 | 锁屏/解锁/切后台后，播放态与界面一致（续播或明确暂停） | 真机回归 | S1 | P1 |
| A4 | 进程被回收后能回到离开时状态（导航栈/队列/滚动位置） | 开发者选项「不保留活动」 | S1 | P2 |
| A5 | 返回键/手势行为标准，无自绘返回提示 | grep `BackHandler` + 真机 | S1 | P2 |
| A6 | targetSdk/compileSdk 为最新且与依赖兼容 | `app/build.gradle.kts:25,93` | S1 | P1 |
| A7 | 无长期 alpha/失维护第三方依赖，升级路径明确 | `security-crypto 1.1.0-alpha06` | S1 | P2 |
| A8 | 通知分渠道、仅持续事件常驻；媒体通知常驻可控制 | 通知构建代码 + 真机 | S1 | P2 |
| A9 | 方向/尺寸变化不丢状态；锁 portrait 需有产品理由 | `AndroidManifest.xml:19` | S1 | P2 |

### B 性能与流畅度（8 条 · 只做回归核对）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| B1 | 冷启动 2s 内可交互，启动路径无主线程 IO/大初始化 | `am start -W` ×10 + Perfetto | S1/S2 | P1 |
| B2 | 长列表与播放页稳定在帧预算内（16.6/11/8ms） | gfxinfo / Perfetto | S25 | P2 |
| B3 | debug 下 StrictMode 无红框（主线程磁盘/网络） | 是否启用 StrictMode + 跑主流程 | S1 | P2 |
| B4 | 无 ANR 风险：主线程无 runBlocking/sleep/同步网络/大 JSON | grep `runBlocking|Thread.sleep|.join()` | S1 | **P0** |
| B5 | 图片加载有尺寸约束/采样/内存缓存，列表不 OOM | Coil 用法 + `du` 缓存 | S2 | P2 |
| B6 | 后台无非必要保活/轮询；无常驻额外 Service/定时器 | 组件清单 + 代码 | S1 | P2 |
| B7 | release 开 R8+资源收缩，且有可测的 perf 包 | `app/build.gradle.kts:54-80` | S3 | — |
| B8 | 启动/首屏有 Baseline Profile 覆盖并随包发布 | 无 `:benchmark`、无自家 profile | S17 | P3 |
| ★B9 | 1Hz 级 StateFlow 不驱动整页/整壳重组 | Layout Inspector 重组计数 | S4 | P1 |
| ★B10 | 数据变更的失效范围与查询成本匹配（关注索引与全表 Flow） | Room QueryCallback 计时 | S21 | P2 |

### C Compose 专项（12 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| C1 | 组合体内不做昂贵计算，昂贵结果 `remember(keys)` 或上移 | grep `sortedWith|groupBy|filter {` 于组合体 | S4 | P2 |
| C2 | Lazy `items` 带稳定唯一 key，禁 index/hashCode | grep `items(` 看 key | S4/S27 | P2 |
| C3 | 异构列表用 `contentType` 区分 | Lazy 多类型处 | S4 | P3 |
| C4 | 高频变化状态用 `derivedStateOf` 收敛重组 | grep `derivedStateOf` | S4 | P2 |
| C5 | CompositionLocal 与共享 Flow 在 Lazy 父层读一次再传值 | 列表 item 内是否读 LocalContext/collect | S4 | P2 |
| C6 | 1~10 项小列表用 Row/Column+forEach，不套 Lazy | 设置页/短列表 | S4 | P3 |
| C7 | items 内不用 `indexOf`（O(N²)），需要下标用 `itemsIndexed` | grep `indexOf(` | S4 | P2 |
| C8 | 状态提升正确：组件无状态，状态放最低共同父/最高可改层 | 组件签名与 remember 层级 | S26 | P2 |
| C9 | 跨配置变更/进程重建需保留的 UI 状态用 `rememberSaveable`+Saver | grep 两者比例 | S26/S27 | P2 |
| C10 | 命名与签名规范：UpperCamel/lowerCamel、@Composable 返回 Unit、modifier 前置、事件 lambda 后置 | 抽样签名 | S20/S19 | P3 |
| C11 | `core:ui` 基础组件不吃业务模型，slot 化 | `core/ui/component/*` 的 import | S19 | P3 |
| C12 | 颜色/字号/形状走 M3 theme token，不硬编码 `Color(0x…)`/数值 sp | grep `Color(0x|fontSize = [0-9]` | S19 | P2 |
| ★C13 | 全仓 `@Immutable/@Stable` 覆盖纯数据类（决定可跳过性） | grep + Compose 编译报告 | S4 | **P1** |
| ★C14 | 无「顶层 collect 高频流 → 整页 body 重跑」中间层 | 逐 Screen 看顶层 collect | S4 | P1 |

### D 架构与状态管理（10 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| D1 | UI 不直连数据源（Room/DataStore/Retrofit），一律经 Repository | grep feature 模块 import | S6 | P1 |
| D2 | 业务逻辑在 ViewModel/state holder，Screen 只渲染与派发 | 抽查 VM 与 Screen | S6 | P2 |
| D3 | 单 Activity + Compose 目的地，路由集中定义 | `MainActivity` + `Routes.kt` | S5 | — |
| D4 | UDF：状态向下、事件向上；跨层用 Flow 不用回调 | 抽查 | S6 | P2 |
| D5 | UI 状态用 `collectAsStateWithLifecycle`，禁裸 `collectAsState` | grep 精确匹配 | S6 | P2 |
| D6 | feature 互不依赖，公共能力下沉 core | grep `project(":feature` | S6/约定 | P1 |
| D7 | 不硬编码 Dispatcher，构造注入便于测试 | grep `Dispatchers.` | S7 | P2 |
| D8 | suspend 函数 main-safe：阻塞调用包 `withContext(io)` | 抽查 DAO/文件/网络调用点 | S7 | **P1** |
| D9 | 协程作用域与生命周期匹配，无泄漏（service/viewModel/自定义 scope 均取消） | grep `CoroutineScope(` | S7/S13 | P2 |
| D10 | 一次性事件（导航/Snackbar）有唯一消费语义，重组不复放 | grep Channel/SharedFlow 事件通道 | S6 | P2 |

### E 数据 / Room / DataStore（8 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| E1 | 升级有手写 Migration 或 AutoMigration，**禁止破坏性重建** | `TingBiliDatabase.kt` + `fallbackToDestructiveMigration` | S21 | **P0** |
| E2 | DAO 返回 suspend/Flow，读写不在主线程，Flow 有上游调度 | 抽查 `@Query` 返回类型与 flowOn | S21/S7 | P1 |
| E3 | SQL 走 Room 注解（编译期校验），不拼 rawQuery | grep `rawQuery|execSQL` | S21 | P2 |
| E4 | 实体不可变、主键/索引明确，跨类型字段有 TypeConverter | grep `@Entity|@PrimaryKey|@TypeConverter` | S21 | P2 |
| E5 | 多表写入用事务；Database/DAO 单例 | grep `@Transaction` | S21 | P2 |
| E6 | DataStore 只存小而简单的键值，不塞大 JSON；新增键读写成对/默认/钳制 | `PreferencesRepository.kt` | S22/约定 | P2 |
| ★E7 | 查询与索引用例匹配（bvid/updatedAt 等过滤与排序有索引支撑） | 迁移 SQL + `@Entity(indices)` | S21 | P2 |
| ★E8 | Room 迁移有自动化测试（`MigrationTestHelper`，需 `exportSchema=true`） | grep room-testing 使用 | S21 | P1 |

### F 网络与安全隐私（8 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| F1 | 全站 HTTPS；如需明文必须显式 network-security-config 且 release 禁止 cleartext | 无配置 + baseUrl 核对 | S8 | P1 |
| F2 | 无 TrustManager/主机名校验绕过 | grep `X509TrustManager|HostnameVerifier` | S8 | P0 |
| F3 | 登录态/令牌加密存储，不落明文与日志 | `EncryptedAuthStore.kt` + grep 日志 | S8 | **P1** |
| F4 | 代码/资源/构建脚本无硬编码密钥、Cookie、签名口令 | grep `SESSDATA|bili_jct|password|secret` | S8 | P0 |
| F5 | 组件导出最小化（仅 launcher exported=true） | 两个 AndroidManifest | S8 | P1 |
| F6 | 敏感数据不参与自动备份（`allowBackup`/`dataExtractionRules`） | manifest 是否声明 | S8 | P2 |
| F7 | 若有 WebView：域名白名单 + 不滥用 JS Bridge + 不放行混合内容 | grep `WebView|addJavascriptInterface|MIXED_CONTENT` | S8 | P1 |
| F8 | 网络日志仅 debug 开 BODY，release 关闭 | grep HttpLoggingInterceptor + BuildConfig.DEBUG | S8 | P2 |

### G 媒体与后台（11 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| G1 | Player 与 MediaSession 住在 Service，不在 Activity/VM 持有 | `TingBiliPlaybackService` + `ServicePlayerModule` | S13 | P1 |
| G2 | onCreate 建 Player/Session，onDestroy 释放（防泄漏） | 同上 | S13 | P1 |
| G3 | `onGetSession` 返回现有实例，不新建 | 同上 | S13 | P2 |
| G4 | `onTaskRemoved` 语义明确（默认保留 vs 停止） | grep `onTaskRemoved` | S13 | P2 |
| G5 | FGS 声明 `mediaPlayback` 类型 + 双权限 | 两个 manifest | S15 | P1 |
| G6 | 媒体通知为 MediaStyle、随状态更新、不可滑掉、可从锁屏控制 | 真机 + 通知构建 | S13/S1 | P1 |
| G7 | 音频焦点：播放前请求、失焦暂停/降音量 | grep `handleAudioFocus|setAudioAttributes` | S16 | P1 |
| G8 | 后台播放有常驻通知与锁屏控制 | 真机 | S1 | P1 |
| G9 | Controller 连接/释放成对；Service 被杀后能重建 | `PlaybackConnection.kt` | S13 | P2 |
| G10 | 播放 URL 过期能自动重解析，不长时间播不出 | `PlayUrlPipeline`/`PlayUrlCache` | S2 | P2 |
| G11 | 来电/抢焦点/拔耳机后行为正确并恢复 | 真机回归 | S16 | P1 |
| ★G12 | `startForegroundService` 有 try/catch，起服务后必调 `startForeground` | grep 调用点 | S15 | **P1** |
| ★G13 | `MediaSession` 设置 `sessionActivity`（点通知能回 App）+ 断连回调 | grep `setSessionActivity|onDisconnected` | S13 | P2 |

### H 无障碍与国际化（8 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| H1 | 非文本可交互元素有 `contentDescription`，装饰图为 null | grep 全量抽查 | S1/S9 | P2 |
| H2 | 触控目标 ≥48dp（含列表行内小控件） | Layout Inspector 量 | S1/S10 | **P1** |
| H3 | 文字对比度 4.5:1 / 3:1（含深色与 AMOLED 纯黑） | Accessibility Scanner | S1/S10 | P2 |
| H4 | 语义合并与遍历顺序合理，无重复朗读 | TalkBack 走查 | S9 | P2 |
| H5 | 文字用 sp，字体放大 200% 不截断不重叠 | 系统字体最大 + 走查 | S9/S10 | P2 |
| H6 | 用户可见文案走 resources（不硬编码），Locale 相关格式化带 Locale | grep 硬编码 `Text("` 与 `String.format` 的 Locale 参数 | S11 | P3（决策） |
| H7 | 深浅主题都可用，动态取色失败有回退 | 切主题走查 | S1 | P2 |
| H8 | 有自动化无障碍检查（ATF/Compose accessibility checks） | grep `AccessibilityChecks` | S9 | P3 |

### I 测试与质量门禁（7 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| I1 | 纯逻辑（VM/Repo/Parser/Policy）有同模块单测 | 测试分布统计 | S12 | P2 |
| I2 | 单测不依赖真机/真实网络（Robolectric/fake/TestDispatcher） | grep `RobolectricTestRunner|MockWebServer|Fake` | S12 | P1 |
| I3 | CI **编译并执行**全部模块测试源码 | `ci.yml:60-63`（回退失效） | S12 | **P1** |
| I4 | CI 门禁必过才能合并：assemble + 单测 + lint | `ci.yml` 步骤 | S12/约定 | P1 |
| I5 | 关键用户路径有 UI/导航测试；改路由同步改测试 | `NavigationTest.kt` 覆盖 9/21 路由 | S12 | P2 |
| I6 | 协程测试确定性：runTest + 注入 TestDispatcher，无 sleep | grep `Thread.sleep|runBlocking` in test | S7 | P2 |
| I7 | 缺 instrumented 测试（0 个）——记为缺口，不当作违规 | `git ls-files` | S12 | P2 |
| ★I8 | Robolectric 配置一致（`robolectric.properties` 的 sdk 与 targetSdk 匹配，无静默跑错 SDK） | 7 建 6 配 | — | P2 |
| ★I9 | 有 lint/detekt/ktlint 之一作为风格与 API 误用门禁 | 全仓 0 配置 | S18/S20 | P1 |

### J 构建与发布（9 条）

| 编号 | 检查点 | 怎么验 | 依据 | 级别 |
|---|---|---|---|---|
| J1 | compile/target/minSdk 与依赖、字节码版本自洽 | `app/build.gradle.kts` + toml | S1/S3 | P1 |
| J2 | release 与 perfDebug 均 minify+shrink，R8 规则最小化 | `proguard-rules.pro` + 合并配置 | S3/S18 | P2 |
| J3 | Baseline Profile 存在并被 measure | 无 `:benchmark`；仅有库自带 profile | S17 | P3 |
| J4 | 构建速度配置合理（daemon/并行/缓存） | `gradle.properties` | S18 | P1 |
| J5 | 依赖版本统一由版本目录维护，无模块内硬编码坐标 | grep 硬编码 `implementation("` | S18/约定 | P2 |
| J6 | release 密钥不入库；缺 Secrets 时 CI 明确失败 | `release.yml` | S8/约定 | P1 |
| J7 | 体积有可复现基线与回归手段 | 实测 85.0MB / 6.53MB | S3/S18 | P3 |
| J8 | targetSdk 36/37 行为变更逐条比对（edge-to-edge 强制、predictive back、16KB page） | S23/S24 变更表 | S23/S24 | **P1** |
| J9 | edge-to-edge 适配全链路，无两套沉浸式打架 | `MainActivity.kt` + 各页 innerPadding | S23/S1 | P2 |
| ★J10 | R8 崩溃可定位：保留 `SourceFile,LineNumberTable`，mapping 归档 | 合并配置 + `release.yml` | S3 | P2 |
| ★J11 | 版本目录/约定文档单一事实源，CI 能校验漂移 | 三份文档口径不一 | 约定 | P2 |

> 合计 **100 条**（官方 88 + 本项目补充 12）。级别列为基线值：现场若发现「已被用户感知 / 可崩溃 / 数据丢失」可上调为 P0。

---

## 5. 审查方法（四类取证，各自能证明什么）

### M1 自动扫描（先做，成本最低、覆盖最广）

| 手段 | 现在有吗 | 能证明 | 不能证明 |
|---|---|---|---|
| Android Lint（`./gradlew lint`） | ❌ 仓库 0 配置 | API 误用、Manifest/权限、targetSdk 行为、资源与 a11y 一部分、R8 提示 | 业务逻辑正确性 |
| detekt / ktlint | ❌ 无 | 复杂度、坏味道、风格 | 运行时问题 |
| 自定义 grep 探针（见附录 B） | — | `runBlocking`、`!!`、硬编码色值、`SimpleDateFormat`、裸 collect、`AsyncImage` 用法等**模式** | 是否真的造成卡顿/崩溃 |
| aapt2 dump badging/permissions | 可跑 | 权限、SDK 版本、组件导出 | 代码质量 |
| Room schema 导出比对 | 当前 `exportSchema=false` | **无法校验**迁移正确性 | — |

> 注：`lintVitalRelease` 只在打 tag 的 `assembleRelease` 时隐式跑，等于「发版前才发现」。B1 要把 lint 提前到 PR。

### M2 人工走查（不可替代）

按两条轴切：**模块轴**（13 个模块，附录 C 有矩阵）与**横切轴**（安全、无障碍、媒体、测试）。每类检查点指定唯一负责人，避免「都看了等于都没看」。

### M3 运行时与真机取证（只在需要定论时）

Perfetto / Studio Profiler / Layout Inspector（重组计数）/ `am start -W`（冷启动）/ `dumpsys meminfo|gfxinfo|netstats` / Battery Historian / Accessibility Scanner + TalkBack / 字体 200% / 平板（或开发者选项强制 600dp）/ 后台播放 + 来电 + 拔耳机。

### M4 合规取证（平台行为类，静态看不到）

`aapt2 dump` 看最终合并后的 manifest 与权限；验证 FGS 启动限制；验证通知渠道与拒绝权限后的降级；验证备份规则（`bmgr`）；验证预测性返回手势。

**证据标准（硬要求）**：每条结论必须给「位置（file:line）+ 复现方式（命令/步骤）+ 规范出处（S#）」三要素。**静态推断必须标「待验证」**，不得出现在 P0 结论里。

---

## 6. 执行批次（含退出条件）

### B0 基线扫描（0.5 人日）

- 跑 lint（首次会刷出大量告警，全部导入 `lint-baseline.xml` 再逐类清零）；跑 detekt/ktlint 一次（只取 top 告警，不做风格大改）。
- 跑附录 B 的探针脚本，把命中落成 `temp/review/scan-*.md`。
- **退出条件**：每一类都有「已扫描/无法扫描（原因）」的记录；`docs/REVIEW_REPORT.md` 骨架建立。

### B1 质量门禁与构建（1 人日 · 收益最高、风险最低）

覆盖：J1/J2/J4/J5/J6/J7/J10/J11 + I3/I4/I9 + A6/A7 + §1.2 的三处文档口径。
动作：修 CI 测试回退语义（让 35 个失联测试真正执行）；引入 lint 进 CI（先 baseline）；修 `gradle.properties` 的无效属性与缓存/并行配置；补 R8 行号与 mapping 归档；以 `libs.versions.toml` 为唯一事实源重写 `CONVENTIONS.md §1`。
**退出条件**：CI 能拒绝「测试挂掉 / lint 新增问题 / R8 报错」的提交，且门禁耗时可控。

### B2 平台合规与安全（1.5 人日）

覆盖：F1–F8 + G1–G13 + A8/A9 + J8/J9。
重点：`startForegroundService` 崩溃面、备份策略、方向锁与大屏、MediaSession `sessionActivity`/断连重建、WebView 混合内容与域名白名单、日志与凭据、通知权限被拒后的降级。
**退出条件**：所有 P0/P1 合规项有「已修 / 明确延期及理由」；真机回归清单（后台播放/来电/拔耳机/锁屏/通知）跑完。

### B3 架构与数据层（1.5 人日）

覆盖：D1–D10 + E1–E8。
重点：破坏性迁移与迁移测试、历史/收藏表索引、Result 双轨（`core.data.Result` vs `kotlin.Result`）、`BiliHistoryViewModel` 直连 `BiliApi` 的越界、DataStore 钳制缺口。
**退出条件**：迁移路径与「清库」策略经你确认；`exportSchema=true` + MigrationTestHelper 至少覆盖 12→15。

### B4 UI 规范与无障碍（1.5 人日）

覆盖：C1–C14 + H1–H8 + A1–A5。
重点：`@Immutable`/稳定性、顶层 collect 下移、`contentType`、22dp 触控区、200% 字体、深色对比度、`!!` 与重复实现（`formatDuration` 3 份）。
**退出条件**：TalkBack + 字体放大 + 深色三组走查有记录；i18n 决策落文档。

### B5 性能回归核对（1 人日 · 只核对不下新诊断）

覆盖：B1–B10 + §2.3 的三个问题。
动作：对照 `PERF_AUDIT` 的 10 条销账项逐项复验；用 Layout Inspector 量「播放中 1Hz」与「平板 2Hz」；`am start -W` 冷启动对比；确认 8 项「明确不做」是否仍然成立。
**退出条件**：产出「已修确认 / 回归 / 新增 / 结论被推翻」四栏表，每条带量化数据。

### B6 汇总与规则回写（0.5 人日）

动作：`docs/REVIEW_REPORT.md` 定稿（分级 backlog + 整改顺序）；把可长期执行的规则回写进 `AGENTS.md`/`docs/CONVENTIONS.md`（如「新增实体必须带索引」「新增 Screen 禁顶层 collect 高频流」）；给出下一轮回归方式。
**退出条件**：任何新贡献者只看文档就知道规范边界；`git status` 干净。

**总预估**：7.5 人日（不含整改）。若只做一批，**优先 B1**——它不依赖真机、收益最大、且决定后续能否持续。

---

## 7. 严重度与结论模板

| 级别 | 定义 | 处理节奏 |
|---|---|---|
| **P0** | 崩溃 / 数据丢失 / 账号凭据泄露 / 主线程 ANR | 立即修，阻断发版 |
| **P1** | 明确功能缺陷或安全缺陷、门禁失效、平台合规红线（FGS/权限/迁移） | 本迭代修 |
| **P2** | 规范偏离、可维护性、性能浪费（非用户可感知） | 排期修或记录为已知债 |
| **P3** | 风格、建议、文档 | 随手修 / 记录 |

每条结论统一模板：

```
[编号/P级] 一句话结论
位置：path:line（必要时多个）
证据：命令输出 / 复现步骤 / 数据
规范：S# 或 团队约定条款
影响：用户可感知什么 / 数据风险 / 维护成本
建议：最小修法（一句话）；成本 S/M/L
状态：待修 / 已修(commit) / 明确不做(理由)
```

---

## 8. 需要你拍板的 8 个问题（含我的建议）

1. **产出到哪一步？** 只出审查报告，还是「边审边修」？
   *建议*：B0/B5 只出报告；B1/B2 的 P0/P1 直接修（收益/风险比最高），其余进 backlog。
2. **性能要不要重开？** *建议*：不重开诊断，只做 B5 回归核对——`PERF_AUDIT` 已封版且 10/10 销账，重开是重复劳动。
3. **是否引入新工具链**（lint 基线 / detekt / kover / `:benchmark` 模块 / dependency-analysis）？会改构建与 CI。
   *建议*：lint 必上（低风险高收益）；其余按需，`:benchmark` 与 baseline profile 单独立项。
4. **数据库破坏性兜底怎么定？** 现状是 <v12 用户升级即清库，且与约定冲突。
   *建议*：补 v11→12 迁移（若历史版本仍在流通）；否则把「清库」写进发版说明并取消 `fallbackToDestructiveMigration`。
5. **目标设备范围**：只手机，还是要覆盖平板/折叠屏？（决定方向锁、Rail 分支、大屏适配的审查深度）
   *建议*：至少覆盖「600dp 强制」模拟验证，否则 `screenOrientation=portrait` 在 API 36+ 是定时炸弹。
6. **国际化**：确认「中文单语，不做多语言」，只补 Locale 与文案资源化？
   *建议*：确认不做多语言，但把结论写进文档（否则 792 处硬编码会被反复当缺陷提）。
7. **发布口径**：不上架商店，是否仍按 Play 技术质量 / Core App Quality 全套审？
   *建议*：按 Core App Quality 的功能/稳定性/媒体/无障碍审，跳过 Play Console 专属项（pre-launch report、Android vitals）。
8. **时间盒**：如果只给 1-2 天，选哪批？*建议*：B1（门禁与构建）+ B5（性能回归）。

---

## 9. 预审快照（已静态确认，正式结论待复核）

> 来自 4 份侦察报告（`temp/recon/`），全部带 file:line；这里只列最值得先看的。标注「待验证」的必须真机/工具定论后才进报告正文。

| # | 级别 | 线索 | 位置 |
|---|---|---|---|
| 1 | **P0** | 三处 `runBlocking` 读 DataStore 首值挂主线程：首图加载、SimpleCache 构造、ExoPlayer 创建 | `app:TingBiliApp.kt:30`；`media:AudioCacheModule.kt:38`；`media:ServicePlayerModule.kt:82` |
| 2 | **P1** | CI 测试回退永不触发，56 个测试文件只跑 21 个（含 `NavigationTest` 在内 35 个从不执行） | `.github/workflows/ci.yml:62-63` |
| 3 | **P1** | DB v15 只有 12→15 三个迁移 + `fallbackToDestructiveMigration(true)`，与「禁止破坏性迁移」冲突；`exportSchema=false` 让 `room-testing` 成死依赖 | `core/data/.../di/DatabaseModule.kt:89,93`；`db/TingBiliDatabase.kt:18-19` |
| 4 | **P1** | 4 处 `startForegroundService` 无 try/catch；「起服务但未播放」有 startForeground 超时崩溃面（待真机） | `PlaybackConnection.kt:47`；`PlayerManager.kt:665,743,749` |
| 5 | **P1** | 平板/≥600dp 分支在同一作用域读 500ms tick 的 `player.state`，整个 NavHost 壳体每秒失效 2 次（手机分支已修，平板漏了） | `app/navigation/MainTabsScaffold.kt:159-186` |
| 6 | **P1** | `PlayerScreen` 顶层 collect `progUi/lyricUi`，播放中 1Hz 整页 body 重跑——10-23「拆三层」的隔离被抵消 | `feature/player/PlayerScreen.kt:160-162` |
| 7 | **P1** | 全仓 `@Immutable/@Stable` 0 处、`derivedStateOf` 0 处、`contentType` 0 处，是所有「父级 tick」问题的乘数 | 全仓 grep；`core/data/model/Track.kt:5-25` |
| 8 | **P1** | 启动首帧门禁从「读偏好」变成「Room 查询」，首装/大版本升级后冷启动变慢（待测） | `app/navigation/MainViewModel.kt:48-58`；`MainActivity.kt:102-104` |
| 9 | **P2** | Manifest 无 `allowBackup`/`dataExtractionRules`：收藏/历史进云备份；加密凭证随备份迁移但 Keystore 不迁移 → 换机必掉登录 | `app/src/main/AndroidManifest.xml:10-15`；`EncryptedAuthStore.kt:34-51` |
| 10 | **P2** | MediaSession 未设 `sessionActivity`（点通知不回 App）、无断连重建、无 `onPlaybackResumption` | `media:TingBiliPlaybackService.kt:96`；`PlaybackConnection.kt:51-63` |
| 11 | **P2** | 重构后索引退化：`HistoryEntity` 改主键丢掉 bvid/updatedAt 索引，`FavoriteTrackEntity` 缺 `(bvid,cid)`；叠加 Room 表级失效 → 播放中每 10s 多条查询重跑 | `core/data/.../db/HistoryDao.kt:20-38`；`FavoriteEntities.kt:22-34`；`DatabaseModule.kt:55-83` |
| 12 | **P2** | 无障碍硬伤与规范偏差：自绘进度条触控区仅 22dp（注释自认）；`formatDuration` 重复 3 份且规范版 `core/ui/TrackFormat.kt` 未被使用 | `PlayerScreen.kt:1006-1012`；`DetailScreen.kt:663`、`HomeScreen.kt:762`、`FavoriteCards.kt:323` |
| 13 | **P2** | 无效构建属性 `android.nonTransitiveRResources`（AGP 9.4.1 里不存在，正确名为 `nonTransitiveRClass`）；无配置/构建缓存、`parallel=false`、daemon 30s 自杀 | `gradle.properties:3-7` |
| 14 | **P2** | 三份文档三套版本口径（`CONVENTIONS` 写 36/旧版本，`DEVELOPMENT` 写 37，`PERF_AUDIT` 称无混用） | `docs/CONVENTIONS.md:8-10`；`docs/DEVELOPMENT.md:5`；`docs/PERF_AUDIT.md:431` |
| 15 | **P3（决策）** | `strings.xml` 仅 1 条，主源码 792 处中文字面量、`stringResource` 0 处 | `app/src/main/res/values/strings.xml:3` |

**两处对既有结论的主动纠正**（复核时需确认）：
- 「小控件用 480 档图浪费 10 倍内存」在 Coil3 下不成立（`AsyncImage` 按布局约束解码），真实浪费在下载量与磁盘缓存；
- 图片档位实际只剩 3 档（`ImageUrl.kt`），`PERF_AUDIT` 里「五档已收尾」的表述与代码不符。

---

## 10. 交付物与仓库约定

| 产出 | 路径 | 入库 |
|---|---|---|
| 本计划 | `docs/CODE_REVIEW_PLAN.md` | ✅ |
| 正式审查报告（含分级 backlog） | `docs/REVIEW_REPORT.md` | ✅ |
| 自动扫描原始输出 | `temp/review/scan-*.md` | ❌（`temp/` 已 gitignore） |
| 侦察阶段材料（4 份） | `temp/recon/*.md` | ❌ |
| 整改 | 一批一提交，信息 `fix/chore/docs(范围): 简述` | ✅ |

- 遵守 `AGENTS.md`：只相对路径、不写死绝对路径、不提交 `build/ .gradle/ .idea/ local.properties *.apk *.log`。
- 三份「已封版」审计文档**不改正文**，只在文末追加「落地进度」（沿用 `PERF_AUDIT` 既有惯例）。
- 报告里每个问题必须有 `file:line`；无法定论的写进「待验证」章节并列测法。

---

## 11. 风险与依赖

| 风险 | 影响 | 应对 |
|---|---|---|
| 没有平板/折叠屏设备 | A9/J8/G13 只能静态推断 | 开发者选项强制 600dp + 模拟器；结论标「未覆盖真机」 |
| targetSdk 37 超前于工具链 | Robolectric 4.17 是否支持 sdk 37 未知（`feature:player` 无 `robolectric.properties` 会回落 targetSdk） | B0 先跑一次全量单测确认；不支持则显式钉 `@Config(sdk)` |
| 官方文档在本网络不可达 | 依据只能走镜像 | 已用 `developer.android.google.cn` 同路径抓取，附录 A 标注 |
| 审查期间代码继续变动 | 结论与行号漂移 | 以 `2900f56` 为锚点，每批开工前 `git rev-parse HEAD` 记录 |
| 「已封版」文档被误当现状 | 重复排期已废弃项 | 报告开头统一写「以本报告为准，PERF_AUDIT 正文诊断已冻结」 |
| lint 首次全量会刷出海量告警 | 一次性改不完 | 引入 baseline，只对**新增**问题设门禁；存量按 P 级分批 |

---

## 附录 A 官方规范来源（30 项，侦察阶段实测可达）

| # | 页面 | 管什么 | 抓取 |
|---|---|---|---|
| S1 | developer.android.com/docs/quality-guidelines/core-app-quality | 核心应用质量基线（功能/UX/性能/稳定/通知/无障碍/音频） | ✅ 镜像 |
| S2 | /topic/performance/overview | 性能指南入口 | ✅ 镜像 |
| S3 | /develop/ui/compose/performance | Compose 性能总览（三阶段、稳定性、R8、profile） | ✅ 镜像 |
| S4 | /develop/ui/compose/performance/bestpractices | Compose 性能细则（remember/key/contentType/derivedState/lazy） | ✅ 镜像 |
| S5 | /topic/architecture | 架构总纲 | ✅ 镜像 |
| S6 | /topic/architecture/recommendations | 架构建议清单（分层/UDF/collectAsStateWithLifecycle） | ✅ 镜像 |
| S7 | /kotlin/coroutines/coroutines-best-practices | 协程最佳实践（注入 Dispatcher、main-safe） | ✅ 镜像 |
| S8 | /privacy-and-security/security-best-practices | 安全基线（TLS/凭据/导出/WebView） | ✅ 镜像 |
| S9 | /develop/ui/compose/accessibility | Compose 无障碍（语义/遍历/可缩放/ATF） | ✅ 镜像 |
| S10 | /guide/topics/ui/accessibility/apps | 应用无障碍 | ✅ 镜像 |
| S11 | /guide/topics/resources/localization | 本地化（文案进资源） | ✅ 镜像 |
| S12 | /training/testing/fundamentals | 测试分层 | ✅ 镜像 |
| S13 | /media/media3/session/background-playback | Media3 后台播放/FGS/通知 | ✅ 镜像 |
| S15 | /develop/background-work/services/fgs/service-types | FGS 类型与权限 | ✅ 镜像 |
| S16 | /media/optimize/audio-focus | 音频焦点 | ✅ 镜像 |
| S17 | /topic/performance/baselineprofiles/overview | Baseline Profile | ✅ 镜像 |
| S18 | /studio/build/optimize-your-build | 构建速度优化 | ✅ 镜像 |
| S19 | /develop/ui/compose/api-guidelines（+ androidx 源文） | Compose API 风格 | ✅ 镜像 |
| S20 | kotlinlang.org/docs/coding-conventions.html | Kotlin 编码规范 | ✅ 直连 |
| S21 | /topic/libraries/architecture/room + migrating-db-versions | Room 与迁移 | ✅ 镜像 |
| S22 | /topic/libraries/architecture/datastore | DataStore | ✅ 镜像 |
| S23 | /about/versions/16/behavior-changes-16（+ -all） | Android 16 行为变更 | ✅ 镜像 |
| S24 | /about/versions/17/behavior-changes-17 | Android 17 定向变更 | ✅ 镜像 |
| S25 | /topic/performance/vitals/render | 掉帧与帧预算 | ✅ 镜像 |
| S26 | /develop/ui/compose/state | 状态提升/rememberSaveable | ✅ 镜像 |
| S27 | /develop/ui/compose/lists | Lazy key 与列表 | ✅ 镜像 |
| — | /media/media3/session、/topic/performance/app-optimization、/topic/performance/app-baseline-profiles、/develop/ui/paging/overview、.../api-guidelines/app-guidelines | — | ❌ 404（已用等价页替代） |

> 说明：本机访问 `developer.android.com` 失败（`TypeError: fetch failed`），全部改用官方中国镜像 `developer.android.google.cn` 同路径同正文抓取；表中路径为规范形式。

## 附录 B 工具与命令清单

```bash
# 1 静态扫描
./gradlew :app:lintDebug --no-daemon
./gradlew assembleDebug compileDebugUnitTestKotlin --no-daemon
./gradlew testDebugUnitTest --no-daemon --continue        # 全量跑，修 CI 的等价命令

# 2 探针（只读，直接可跑）
git grep -n "runBlocking" -- '*.kt'
git grep -n "collectAsState(" -- '*.kt'                  # 裸收集（应为 0）
git grep -n "derivedStateOf|@Immutable|@Stable|contentType" -- '*.kt'
git grep -n "AsyncImage(" -- '*.kt'
git grep -n "SimpleDateFormat" -- '*.kt'
git grep -n "startForegroundService" -- '*.kt'
git grep -n "fallbackToDestructiveMigration" -- '*.kt'
git grep -n "Dispatchers." -- '*.kt'
git grep -n 'Text("' -- '*/src/main/**/*.kt'              # 硬编码文案
git grep -n "implementation("" -- '*/build.gradle.kts'   # 绕过版本目录

# 3 Manifest / 产物取证
aapt2 dump badging  app/build/outputs/apk/perfDebug/app-perfDebug.apk
aapt2 dump permissions app/build/outputs/apk/perfDebug/app-perfDebug.apk
Get-Content app/build/outputs/mapping/perfDebug/configuration.txt | Select-String "SourceFile|LineNumberTable"

# 4 真机
adb shell am start -W -n cn.debubu.tingbili/.MainActivity
adb shell dumpsys meminfo cn.debubu.tingbili
adb shell dumpsys gfxinfo cn.debubu.tingbili framestats
adb shell dumpsys netstats detail
adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml   # 状态验证只认控件树
# Layout Inspector（重组计数）、Perfetto、Battery Historian、Accessibility Scanner 按需
```

## 附录 C 模块 × 审查重点矩阵

| 模块 | 重点类别 | 关键文件 |
|---|---|---|
| `app` | J/I/A/D | `MainActivity.kt`、`navigation/*`、`TingBiliApp.kt`、`di/` |
| `core:ui` | C/H | `theme/*`、`component/*`、`TrackFormat.kt` |
| `core:data` | E/D | `db/*`（实体/DAO/迁移）、`datastore/PreferencesRepository.kt`、`Result.kt` |
| `core:media` | G/B/D | `PlayerManager.kt`、`TingBiliPlaybackService.kt`、`PlaybackConnection.kt`、`HistoryTracker.kt` |
| `core:bili-ui` | C/H | `BiliAccountUi.kt` |
| `data:bilibili` | F/D/G | `BiliRepository.kt`、`auth/AuthRepository.kt`、`di/NetworkModule.kt`、`WbiSigner.kt` |
| `feature:player` | B/C/A | `PlayerScreen.kt`(1978)、`PlayerViewModel.kt`、`LyricState.kt` |
| `feature:home` | B/C | `HomeScreen.kt`、`HomeViewModel.kt`、`*PagingSource.kt` |
| `feature:favorites` | B/C/E | `*Screen.kt`、`FavoriteCards.kt`、`FavoritesViewModel.kt` |
| `feature:history` | B/C | `HistoryScreen.kt`、`HistoryViewModel.kt` |
| `feature:mine` | C/F/H | `LoginScreen.kt`(949)、`GeetestDialog.kt`、`Settings*.kt` |
| `feature:detail` | C | `DetailScreen.kt`、`DetailViewModel.kt` |
| `feature:space` | C/H | `SpaceScreen.kt`、`SpaceViewModel.kt` |

## 附录 D 侦察阶段已有材料（不入库）

| 文件 | 内容 | 行数 |
|---|---|---|
| `temp/recon/specs.md` | 88 条官方检查点 + 30 项来源 + 不适用清单 + 项目特有风险 | 215 |
| `temp/recon/build-infra.md` | 构建/CI/依赖/R8/文档漂移，含可复现命令 | 177 |
| `temp/recon/runtime-perf.md` | 既有审计逐项复验 + 回归与新发现 + 真机测法 | 191 |
| `temp/recon/quality-compliance.md` | 约定逐条对照 + 平台合规 + 安全 + a11y + 测试 | 173 |

---

*本文由 Team Lead 汇总（4 名侦察员并行产出）。下一步：你拍板 §8，我按 §6 开工。*
