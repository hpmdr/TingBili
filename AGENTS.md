# TingBili

B 站专用音频播放器（非官方，仅供学习交流，禁止商用）。单 Activity、全 Compose。
包名 `cn.debubu.tingbili`。产品介绍看 `README.md`，免责与许可看 `LICENSE`。

## 命令

```bash
# 日常迭代（本机调试）：保留 Gradle daemon，只出 debug 包
./gradlew :app:installDebug
# 提交前门禁 / CI：无 daemon + 全量
./gradlew assembleDebug --no-daemon --stacktrace
./gradlew :core:data:testDebugUnitTest :core:media:testDebugUnitTest --no-daemon --stacktrace
```

全量 `./gradlew testDebugUnitTest` 很重，留给 CI 跑。CI 流程看 `.github/workflows/ci.yml`。
**依赖版本**统一在 `gradle/libs.versions.toml` 维护；**应用版本号**（versionName/Code）
不在这里——由 `release.yml` 从 git tag 经环境变量 `APP_VERSION_NAME` / `APP_VERSION_CODE` 注入
（本地默认 `1.0` / `1`）。

**构建变体怎么选**（R8 包慢 3~5 倍，别拿它做日常验证）：

| 变体 | 混淆 | 用途 | 增量耗时 |
|---|---|---|---|
| `assembleDebug` / `installDebug` | 否 | **真机功能验证、日常调试默认用它** | ~20s |
| `assemblePerfDebug` | 是（R8 + 资源收缩，debug 签名） | 只在改了 `proguard-rules.pro` / 依赖 / 序列化与反射点，或要验「压缩后能否正常跑」时用 | ~1~2min |
| `assembleRelease` | 是 | 发版；需签名配置与 Secrets | — |

`perfDebug` 存在的意义是「不碰发版签名也能测 R8 路径」，CI 只跑冒烟（`lintDebug` 之外的一步）。
**唯一例外**：验「启动自动检查更新」必须用它（debug 包会被跳过，见文末规则）。

**本机可能在忙别的（游戏 / 会议 / 渲染），构建要限流**：

```bash
# 降调度优先级：冲突时给其他软件让路，没人抢时照吃满全部核（峰值不损失）
nice -n 19 ./gradlew assembleDebug --console=plain
```

**不要用 `--max-workers` 限 CPU**（2026-11-10 实测）：它只压 Gradle 自己的 worker 池，
而编译主力是**独立的 Kotlin Compile Daemon 进程**（每个模块的 Kotlin 插件版本各起一个）
和 R8，两者都不归它管——带 `--max-workers=2` 构建，java 进程总 CPU 照样冲到 1400%+（约 14 核）。
真要硬性封顶只能用 OS 层 cgroup（`systemd-run --scope -p AllowedCPUs=0-7 ./gradlew ...`），
或改 `kotlin.compiler.execution.strategy=in-process` 把编译拉回 daemon 内（会加大 daemon 内存压力）。

- **`nice` 对已存在的 daemon 无效**：nice 值属于进程，daemon 若此前以 nice 0 启动，
  后续 `nice` 只影响它 fork 出的子进程。实测影响不大（daemon 多数时间在等 worker，
  且 Kotlin daemon 会继承父进程 nice），不必为限流去 `--stop` 重启。
- 日常用 **daemon**（保留 JVM），不要为了「干净」反复 `--no-daemon`：每次 fork 一个 JVM 的 CPU 抖动比增量构建更大。
- 攒着改一起验证，**不要一步一构建**；纯文档/单模块改动只跑目标任务，别动全量。
- 跑之前说一句「要构建，约 N 秒」，跑完再说结果——用户在游戏时尤其如此。

## 地图

- 模块清单以 `settings.gradle.kts` 为准（`app` + `core:*` + `data:bilibili` + `feature:*`）。
- 应用入口：`app/src/main/java/cn/debubu/tingbili/MainActivity.kt`；导航：`app/.../navigation/`（`Routes.kt`、`AppNavHost.kt`）。
- 播放：`core/media/`（`PlayerManager.kt`、`PlaybackConnection.kt`）；数据：`core/data/`（`db/TingBiliDatabase.kt`、`model/Track.kt`）；网络：`data/bilibili/`（`BiliRepository.kt`）。
- 团队约定细节看 `docs/CONVENTIONS.md`（历史 opencode superpowers 文档已删除，不再使用）。

## 环境（先探测，不假设）

- 先跑 `java -version` 确认；字节码目标是 17，构建一律用 `./gradlew`。日常调试可保留 daemon（`./gradlew :app:installDebug`）；提交前门禁/CI 用 `--no-daemon`。如报工具链错误，以报错信息和 `gradle/wrapper/gradle-wrapper.properties` 为准对齐 JDK，不要跟本文件较劲。
- SDK 通过 `ANDROID_HOME` / `local.properties` 解析（`local.properties` 各机器不同，不入库）。缺组件看 `README.md` 快速开始。
- **SDK 组件管理一律用新版 `android` CLI**（1.1+，含 `sdk install` / `update` / `remove` / `list`）。
  旧 `sdkmanager` 已废弃（会打印弃用警告且首次执行易卡死），**禁止再用**。网络操作加 `timeout`。
- **`android` CLI 与 Gradle 的 SDK 定位方式不同**：CLI 不读 `local.properties`（那是 Gradle 专用），
  且 1.1+ 不再自动探测路径——`ANDROID_HOME` 为空时**必须显式传 `--sdk=<路径>`**，否则
  `sdk list` 会返回空（看似「没装任何东西」），其他子命令直接报错。例：
  `android sdk list --sdk=/home/ljq/ssd/app/android-sdk`。
- `settings.gradle.kts` 已自动切换镜像（本地 vs `CI=true`），不要为了修网络去改它。先重试一次，再读报错。
- 缺开发环境（JDK / SDK / 构建组件）时先停下问用户，不要自己下载安装或改系统配置。把缺什么、去哪装、装哪版一次性问清，等用户确认再动。

## 规则

- 只用相对路径。禁止把本机绝对路径、个人工具链、密钥写进仓库。
- `build/ .gradle/ .idea/ local.properties *.apk *.log .superpowers/ .codebuddy/` 不提交（见 `.gitignore`）。
- 单人开发：直接提交 `master`，不走分支、不开 PR，一次改动一个提交。提交信息 `feat/fix/docs/test/refactor/chore(范围): 简述`。提交前门禁绿 + 相关单测绿 + `git status` 干净（`gradlew` 可执行位 100755 别提交成 100644，否则 Linux CI 跑不了）。
- `feature` 之间禁止互相依赖，复用下沉到 `core`。UI 用 `collectAsStateWithLifecycle()` 收 `StateFlow`；错误用 `Result.Error` / `UiState` 表达，不要把堆栈抛到界面。
- 导航：路由只定义在 `Routes.kt`，`AppRootNavHost` 是唯一入口；改路由必须同步改 `NavigationTest`。
- 高频状态（播放进度/位置 tick）只在真正消费的叶子 Composable 里 `collectAsStateWithLifecycle()`；**禁止在页面或导航壳顶层订阅**——10-23 修过的隔离被后续提交抵消过一次（见 `docs/FIX_PLAN.md` F4a）。
- Room：**单人测试期允许破坏性升级**（抬 DB 版本 + `fallbackToDestructiveMigration`，升级即清库；没有其他用户），对外发版前再补手写 `Migration`；新增/修改实体必须补索引。改动最小化，收尾前跑通构建和相关测试。
- 需求不确定时多问用户：先给建议方案和利弊，等用户拍板再动手，不要自行定需求、定范围、定交互。
- **真机操作优先用 1.1+ 的 `android` CLI，它比 adb 更贴合 UI 自动化**（都需显式 `--sdk=<路径>`）：
  - `android layout --flat -p > layout.json` 拿控件树：JSON 直出，**自带 `center` 坐标**，
    不用 `pull` 再解析 XML。首次会装一次 instrumentation server（慢），之后秒回。
  - `android input tap/text/scroll/swipe/key` 按**选择器**操作：`android input tap "text=我的"`
    （歧义时加 `--match=first`，`--wait=<ms>` 等元素出现）。比 `adb shell input tap x y`
    健壮——不依赖屏幕密度与坐标。
  - `android run --apks=<apk>` 装包并启动：**delta install 实测只传约一半数据**（80MB→40MB），
    比 `adb install` + `am start` 快。
  - **`android build` 不要用**：实测它是 gradle 的套壳（自己下一套 gradle 再跑），没有额外好处。
  仍要遵守的纪律：**了解屏幕内容（当前页面、加载/空态/转圈、文案）一律读控件树**，
  `screencap` 截图又慢又没时效性，**仅在用户明确要看视觉效果时**提供，
  **禁止**用截图做常规状态验证、猜布局、猜坐标点击。（2026-10-14 教训：多次用截图验状态又慢又卡，已纠正）
- 真机验「启动自动检查更新」**必须用 `perfDebug` 包**：`debug` 包 `FLAG_DEBUGGABLE` 会让
  `MainViewModel.autoCheckUpdate()` 直接 return，永远验不到。且 debug 包 versionName 是占位符
  `1.0`，tag 判定凑不出来——需配合 `BUILD_TIME_OVERRIDE`（见 `core/data/build.gradle.kts`）
  把打包时间压到目标 release 之前。
- **覆盖安装注意 versionCode 单调**：用 `APP_VERSION_CODE=100xxx` 装过测试包后，直接装回默认
  debug 包（versionCode=1）会 `INSTALL_FAILED_VERSION_DOWNGRADE`。要么用更高的
  `APP_VERSION_CODE` 重建 debug 包装回去，要么卸载（会丢本地数据）。
