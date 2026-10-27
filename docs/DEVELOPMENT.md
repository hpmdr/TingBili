# 开发指南（给想自己编译 / 改代码的人）

> 普通用户请看 [README](../README.md)，这里只写开发相关的东西。

- 包名 `cn.debubu.tingbili` · `minSdk 31 (Android 12) / targetSdk 37 / compileSdk 37 / Java 17`
- 技术栈：Kotlin + Compose (Material3, dynamicColor) + Navigation + Hilt + Room + DataStore + Media3 + Retrofit/OkHttp + Coil + Paging3
  （依赖与插件版本统一在 `gradle/libs.versions.toml` 维护，文档里不再重复，免得两处跑偏）
- 架构：`app` 壳 + `core:ui/media/data` + `feature:home/playlist/history/player/mine/detail/search` + `data:bilibili`（特性模块化 Clean Architecture）

## 构建与测试

```bash
# 需 JDK 25（构建；字节码目标保持 Java 17）+ Android SDK cmdline-tools
# 推荐使用新 CLI（sdkmanager 已废弃）： $ANDROID_HOME/cmdline-tools/latest/bin/android
# 组件与 CI 保持一致：compileSdk 37 解析到 platforms;android-37.0，AGP 9.4 默认 build-tools 36.0.0
android sdk install "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
./gradlew assembleDebug --no-daemon   # 轻量门禁，全部命中缓存时约 30s
./gradlew :feature:history:assembleDebug :feature:player:assembleDebug --no-daemon
# 全量测试较重，建议 CI 跑： ./gradlew testDebugUnitTest --no-daemon
```

## 多电脑开发

- 另一台电脑：装 JDK 25 + SDK（`platforms;android-37.0`、`build-tools;36.0.0`、`platform-tools`），配好 `ANDROID_HOME`，各自写 `local.properties`（不入库），`git clone` 后直接 `./gradlew assembleDebug --no-daemon`（Gradle 9.8.0 由 wrapper 自动下载）。
- debug 签名已统一为仓库内 `app/debug.keystore`，多台电脑编出的测试包签名一致，同一部测试机换电脑装包无需卸载重装。release 签名严禁入库。

国内构建已配置 `aliyun` 镜像优先（`settings.gradle.kts`），`gradle.properties` 限制 `Xmx2g` + `daemon.idletimeout 30s` + `stop`，避免 IDE 侧 NodeService 阻塞。

## 模块

```
app (MainActivity + AdaptiveMainScaffold + AppDataModule)
core:ui (TingBiliTheme)
core:data (Room playlists/history + DataStore prefs + Result)
core:media (ExoPlayer audio-only + MediaSession + PlayerManager + Timer)
data:bilibili (search/view/playurl/subtitle, Wbi stub, visitor)
feature:home (Paging3 搜索列表 + 自适应网格；推荐 = 默认词自动执行一次搜索)
feature:playlist (CRUD + dedup + reorder + playAll)
feature:history (history flow + resume)
feature:player (LyricState + SubtitleParser + full-screen)
feature:mine (我的 + B站收藏/历史/稍后听 + 设置/登录)
feature:space (UP 主主页：头像/粉丝/签名 + 投稿列表，pubdate/click/stow 三种排序)
```

## 约定

- 团队约定细节看 `docs/CONVENTIONS.md`。
- 导航路由只定义在 `app/.../navigation/Routes.kt`，改路由必须同步改 `NavigationTest`。
- Room 升级必须手写 `Migration`，禁止破坏性迁移。
