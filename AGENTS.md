# TingBili

B 站专用音频播放器（非官方，仅供学习交流，禁止商用）。单 Activity、全 Compose。
包名 `cn.debubu.tingbili`。产品介绍看 `README.md`，免责与许可看 `LICENSE`。

## 命令

```bash
./gradlew assembleDebug --no-daemon --stacktrace
./gradlew :core:data:testDebugUnitTest :core:media:testDebugUnitTest --no-daemon --stacktrace
```

全量 `./gradlew testDebugUnitTest` 很重，留给 CI 跑。CI 流程看 `.github/workflows/ci.yml`。
版本号统一在 `gradle/libs.versions.toml` 维护，别处不要硬编码。

## 地图

- 模块清单以 `settings.gradle.kts` 为准（`app` + `core:*` + `data:bilibili` + `feature:*`）。
- 应用入口：`app/src/main/java/cn/debubu/tingbili/MainActivity.kt`；导航：`app/.../navigation/`（`Routes.kt`、`AppNavHost.kt`）。
- 播放：`core/media/`（`PlayerManager.kt`、`PlaybackConnection.kt`）；数据：`core/data/`（`TingBiliDatabase.kt`、`model/Track.kt`）；网络：`data/bilibili/`（`BiliRepository.kt`）。
- 团队约定细节看 `docs/CONVENTIONS.md`（历史 opencode superpowers 文档已删除，不再使用）。

## 环境（先探测，不假设）

- 先跑 `java -version` 确认；字节码目标是 17，构建一律用 `./gradlew` 并加 `--no-daemon`。如报工具链错误，以报错信息和 `gradle/wrapper/gradle-wrapper.properties` 为准对齐 JDK，不要跟本文件较劲。
- SDK 通过 `ANDROID_HOME` / `local.properties` 解析（`local.properties` 各机器不同，不入库）。缺组件看 `README.md` 快速开始。
- `settings.gradle.kts` 已自动切换镜像（本地 vs `CI=true`），不要为了修网络去改它。先重试一次，再读报错。
- 缺开发环境（JDK / SDK / 构建组件）时先停下问用户，不要自己下载安装或改系统配置。把缺什么、去哪装、装哪版一次性问清，等用户确认再动。

## 规则

- 只用相对路径。禁止把本机绝对路径、个人工具链、密钥写进仓库。
- `build/ .gradle/ .idea/ local.properties *.apk *.log .superpowers/ .codebuddy/` 不提交（见 `.gitignore`）。
- `feature` 之间禁止互相依赖，复用下沉到 `core`。UI 用 `collectAsStateWithLifecycle()` 收 `StateFlow`；错误用 `Result.Error` / `UiState` 表达，不要把堆栈抛到界面。
- 导航：路由只定义在 `Routes.kt`，`AppRootNavHost` 是唯一入口；改路由必须同步改 `NavigationTest`。
- Room 升级必须手写 `Migration`，禁止破坏性迁移。改动最小化，收尾前跑通构建和相关测试。
- 需求不确定时多问用户：先给建议方案和利弊，等用户拍板再动手，不要自行定需求、定范围、定交互。
