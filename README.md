# TingBili — 哔哩哔哩专用音频播放器

> 音频优先 · 轻量常驻 · 歌单驱动。解决 B 站官方“听视频”臃肿（GB级内存）、以视频为中心的痛点，专为听书/听音乐设计。

- 包名 `cn.debubu.tingbili` · `minSdk 31 (Android 12) / targetSdk 36 / compileSdk 36 / Java 17`
- 技术栈：Kotlin 2.1 + Compose BOM + Material3 (dynamicColor) + Navigation 2.8 + Hilt 2.53 + Room 2.6 + DataStore + Media3 1.4.1 + Retrofit/OkHttp + Coil + Paging3
- 架构：`app` 壳 + `core:ui/media/data` + `feature:home/playlist/history/player/settings` + `data:bilibili`（特性模块化 Clean Architecture）

## 功能（MVP）
- 后台常驻播放 + 通知/锁屏/蓝牙/耳机线控 + 来电/拔出暂停
- 倍速 0.5-3.0 + 自定义秒步进（5-60s）+ 顺序/随机/单曲/列表循环
- 快捷定时关闭（15/30/60/90 + 自定义）
- 本地自建歌单（增删改排序、拖拽、去重 bvid+cid）、多P合集一键成辑（BV → List<Track>）、长视频断点续播
- 播放进度记忆（bvid+cid，节流1s写入 Room history）
- 字幕逐行滚动高亮（投稿优先 > AI），点击跳转

## 导航
底部 4 Tab `首页(发现/搜索) | 歌单 | 历史 | 设置` + **底部居中圆形 mini 播放器**（圆环进度 + 封面旋转），自适应手机 `NavigationBar` / 平板 `NavigationRail` (`NavigationSuiteScaffold`)

## 快速开始
```bash
# 需 Android SDK cmdline-tools 23.0 + platform-tools 37.0.1 + build-tools 36.1.0 + JDK17
# 推荐使用新 CLI： /path/to/android-sdk/cmdline-tools/latest/bin/android
android sdk --install "platforms;android-36" "build-tools;36.1.0" "platform-tools"
./gradlew assembleDebug --no-daemon   # 轻量门禁，约 10-20s
./gradlew :feature:history:assembleDebug :feature:player:assembleDebug --no-daemon
# 全量测试较重，建议 CI 跑： ./gradlew testDebugUnitTest --no-daemon
```

国内构建已配置 `aliyun` 镜像优先（`settings.gradle.kts`），`gradle.properties` 限制 `Xmx2g` + `daemon.idletimeout 30s` + `stop`，避免 IDE 侧 NodeService 阻塞。

## 模块
```
app (MainActivity + AdaptiveMainScaffold + AppDataModule)
core:ui (TingBiliTheme)
core:data (Room playlists/history + DataStore prefs + Result)
core:media (ExoPlayer audio-only + MediaSession + PlayerManager + Timer)
data:bilibili (search/view/playurl/subtitle, Wbi stub, visitor)
feature:home (Paging3 search + BV bottomSheet)
feature:playlist (CRUD + dedup + reorder + playAll)
feature:history (history flow + resume)
feature:player (LyricState + SubtitleParser + full-screen)
feature:settings (step/dynamicColor)
```

## 二期
登录（扫码）→ 导入收藏夹/稍后再看 → 云同步

## 协议
仅用于学习交流，B 站 API 需遵守官方与 `bilibili-API-collect` 约束。
