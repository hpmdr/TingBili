# TingBili

B 站专用音频播放器（非官方，仅供学习交流，禁止商用）。单 Activity、全 Compose。
包名 `cn.debubu.tingbili`。产品介绍看 `README.md`，免责与许可看 `LICENSE`。

## 命令

```bash
# 日常迭代（本机调试）：只出 debug 包
./gradlew :app:installDebug --no-daemon
# 提交前门禁 / CI：无 daemon + 全量
./gradlew assembleDebug --no-daemon --stacktrace
./gradlew :core:data:testDebugUnitTest :core:media:testDebugUnitTest --no-daemon --stacktrace
```

**所有构建一律加 `--no-daemon`**（2026-11-23 决定）：本机内存紧张（29Gi，常驻程序已占 ~10Gi），
保留 daemon 会常驻 2.5GB+，叠加 Kotlin daemon / R8 的全量构建峰值曾把系统压到 swap 打满、
MiMo Desktop 被 OOM 杀死。无 daemon 每次构建后 JVM 立即退出、内存归还，代价是冷启动多约 10s，
换来内存安全，值得。

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

- **构建一律 `--no-daemon`**（见「命令」节原因）；每次 fork JVM 的 CPU 抖动远小于 daemon 常驻
  2.5GB 挤爆内存导致 OOM 的代价。
- 攒着改一起验证，**不要一步一构建**；纯文档/单模块改动只跑目标任务，别动全量。
- 跑之前说一句「要构建，约 N 秒」，跑完再说结果——用户在游戏时尤其如此。

## 地图

- 模块清单以 `settings.gradle.kts` 为准（`app` + `core:*` + `data:bilibili` + `feature:*`）。
- 应用入口：`app/src/main/java/cn/debubu/tingbili/MainActivity.kt`；导航：`app/.../navigation/`（`Routes.kt`、`AppNavHost.kt`）。
- 播放：`core/media/`（`PlayerManager.kt`、`PlaybackConnection.kt`）；数据：`core/data/`（`db/TingBiliDatabase.kt`、`model/Track.kt`）；网络：`data/bilibili/`（`BiliRepository.kt`）。
- 团队约定细节看 `docs/CONVENTIONS.md`（历史 opencode superpowers 文档已删除，不再使用）。

## 环境（先探测，不假设）

- **用户刚克隆仓库、或环境可能变化时，先逐项核对本地开发环境与 README「开发环境要求」节是否相符**
  （JDK 25 / android-37.2 / build-tools 36.0.0 / platform-tools / android CLI 1.1+ / Gradle wrapper 版本 / 内存）。
  若发现任何不符（版本偏旧或缺失），**不要自行降级项目配置、不要改代码将就、也不要擅自下载安装或改系统配置**——
  先用一句话列出「哪项不符 / 本机实际版本 / 推荐版本」，询问用户怎么办，等用户拍板再动手。
  用户明确同意后才安装/升级环境，或调整项目配置。
- 构建 JDK 25 + 字节码目标 Java 25（toolchain 自动探测；本机探测路径在 `~/.gradle/gradle.properties` 的 `org.gradle.java.installations.paths`，不入库）。构建一律 `./gradlew ... --no-daemon`（内存原因见「命令」节），如报工具链错误，以报错信息和 `gradle/wrapper/gradle-wrapper.properties` 为准对齐 Gradle 版本，不要跟本文件较劲。
- SDK 通过 `ANDROID_HOME` / `local.properties` 解析（`local.properties` 各机器不同，不入库）。缺组件看 `README.md`「开发环境要求」与 `docs/DEVELOPMENT.md`。
- **SDK 组件管理一律用新版 `android` CLI**（1.1+，含 `sdk install` / `update` / `remove` / `list`）。
  旧 `sdkmanager` 已废弃（会打印弃用警告且首次执行易卡死），**禁止再用**。网络操作加 `timeout`。
- **`android` CLI 与 Gradle 的 SDK 定位方式不同**：CLI 不读 `local.properties`（那是 Gradle 专用），
  且 1.1+ 不再自动探测路径——`ANDROID_HOME` 为空时**必须显式传 `--sdk=<路径>`**，否则
  `sdk list` 会返回空（看似「没装任何东西」），其他子命令直接报错。例：
  `android sdk list --sdk=/home/ljq/ssd/app/android-sdk`。
- `settings.gradle.kts` 已自动切换镜像（本地 vs `CI=true`），不要为了修网络去改它。先重试一次，再读报错。
- 缺开发环境（JDK / SDK / 构建组件）时先停下问用户，不要自己下载安装或改系统配置。把缺什么、去哪装、装哪版一次性问清，等用户确认再动。

## 规则

- **技术栈一律追最新**：最新的技术、最新的库、最新的 SDK（用户明确要求）。依赖/插件版本以 `gradle/libs.versions.toml` 为唯一事实源；JDK 25 + toolchain 25 + `jvmTarget JVM_25`；compileSdk/targetSdk 跟进最新稳定版。遇到"最新特性是否有坑"时优先实测验证可行性，而不是保守降级。
- 只用相对路径。禁止把本机绝对路径、个人工具链、密钥写进仓库。
- `build/ .gradle/ .idea/ local.properties *.apk *.log .superpowers/ .codebuddy/` 不提交（见 `.gitignore`）。
- 单人开发：直接提交 `master`，不走分支、不开 PR，一次改动一个提交。提交信息 `feat/fix/docs/test/refactor/chore(范围): 简述`。提交前门禁绿 + 相关单测绿 + `git status` 干净（`gradlew` 可执行位 100755 别提交成 100644，否则 Linux CI 跑不了）。
- **打 tag 发版前必须先 `git fetch --tags` 核对远程最新 tag，按远程版本号递增**（如远程已 `v0.4.0` 则下一个是 `v0.4.1`），不要只看本地 tag 就递增——本地 tag 可能落后，曾因此误打倒退版本 `v0.3.5`。tag 名即发版版本号（`v0.4.1` → versionName `0.4.1`），推 tag 会触发 release.yml 正式发版，操作前务必确认。
- `feature` 之间禁止互相依赖，复用下沉到 `core`。UI 用 `collectAsStateWithLifecycle()` 收 `StateFlow`；错误用 `Result.Error` / `UiState` 表达，不要把堆栈抛到界面。
- 导航：路由只定义在 `Routes.kt`，`AppRootNavHost` 是唯一入口；改路由必须同步改 `NavigationTest`。
- 高频状态（播放进度/位置 tick）只在真正消费的叶子 Composable 里 `collectAsStateWithLifecycle()`；**禁止在页面或导航壳顶层订阅**——10-23 修过的隔离被后续提交抵消过一次（见 `docs/FIX_PLAN.md` F4a）。
- Room：**单人测试期允许破坏性升级**（抬 DB 版本 + `fallbackToDestructiveMigration`，升级即清库；没有其他用户），对外发版前再补手写 `Migration`；新增/修改实体必须补索引。改动最小化，收尾前跑通构建和相关测试。
- 需求不确定时多问用户：先给建议方案和利弊，等用户拍板再动手，不要自行定需求、定范围、定交互。
- **真机操作默认用 1.1+ 的 `android` CLI，不要直接用 `adb`**（CLI 未覆盖的操作见下方例外）：
  - **读状态/控件树**：`android layout --flat -p --device=<serial>` 拿 JSON，**自带 `center` 坐标**，
    不用 `pull` 再解析 XML。首次会装一次 instrumentation server（慢），之后秒回。
    禁止用 `adb shell uiautomator dump` 那套（要 pull 再解析）。
  - **操作 UI**：`android input tap/text/scroll/swipe/key` 按**选择器**操作，例如
    `android input tap "text=我的"`（歧义时加 `--match=first`，加 `--wait=<ms>` 等元素出现）。
    禁止用 `adb shell input tap x y`——不依赖屏幕密度与坐标，更稳。
  - **装包并启动**：`android run --apks=<apk> --device=<serial>`，**delta install 实测只传约一半数据**
    （80MB→40MB），比 `adb install` + `am start` 快。
  - **`android build` 不要用**：实测它是 gradle 的套壳（自己下一套 gradle 再跑），没有额外好处。
  - **例外（这些仍用 adb）**：无线调试配对/连接（`adb pair` / `adb connect`）、`adb forward/reverse`
    端口转发、抓 logcat、`adb pull/push` 传文件、`adb -s <serial> shell getprop` 取系统属性。
  - **SDK 路径**：CLI 不读 `local.properties`（那是 Gradle 专用），只认 `ANDROID_HOME` 环境变量。
    **AI 的执行环境常拿不到 `ANDROID_HOME`**（`~/.bashrc` 对非交互式 shell 会直接 return），
    此时按以下顺序解决，**不要在仓库里写死 SDK 绝对路径**（路径变更只改 `~/.bashrc` 一处）：
    1. 显式传 `--sdk="$ANDROID_HOME"`（`ANDROID_HOME` 非空时）；
    2. 或从 `local.properties` 读 `sdk.dir` 再传 `--sdk`；
    3. 都不行就问用户，别猜。
    注意 `source ~/.bashrc` **无效**（非交互式 shell 拿不到），别依赖它。
  仍要遵守的纪律：**了解屏幕内容（当前页面、加载/空态/转圈、文案）一律读控件树**，
  `android screen capture` 截图又慢又没时效性，**仅在用户明确要看视觉效果时**提供，
  **禁止**用截图做常规状态验证、猜布局、猜坐标点击。（2026-10-14 教训：多次用截图验状态又慢又卡，已纠正）
- 真机验「启动自动检查更新」**必须用 `perfDebug` 包**：`debug` 包 `FLAG_DEBUGGABLE` 会让
  `MainViewModel.autoCheckUpdate()` 直接 return，永远验不到。且 debug 包 versionName 是占位符
  `1.0`，tag 判定凑不出来——需配合 `BUILD_TIME_OVERRIDE`（见 `core/data/build.gradle.kts`）
  把打包时间压到目标 release 之前。
- **覆盖安装注意 versionCode 单调**：用 `APP_VERSION_CODE=100xxx` 装过测试包后，直接装回默认
  debug 包（versionCode=1）会 `INSTALL_FAILED_VERSION_DOWNGRADE`。要么用更高的
  `APP_VERSION_CODE` 重建 debug 包装回去，要么卸载（会丢本地数据）。
