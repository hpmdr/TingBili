# TingBili — 哔哩哔哩专用音频播放器 设计文档

- 日期: 2026-09-01
- 状态: 已确认 (方案 B)
- 包名: `cn.debubu.tingbili`
- SDK: `minSdk 31 (Android 12)`, `targetSdk 36`, `compileSdk 36`, `Java 17`, `cmdline-tools 23.0 / platform-tools 37.0.1 / build-tools 36.1.0`

## 1. 背景与目标

解决 B 站官方“听视频”臃肿（GB级内存）、以视频为中心的交互痛点，做**音频优先、轻量常驻、歌单驱动**的专用 App。核心场景：听书（有声小说合集）、听音乐。

原则：无视频解码链路、MB级常驻、游客可用、本地歌单为第一公民。

## 2. 范围 (MVP)

### 2.1 包含
- 后台常驻播放 + 通知/锁屏/蓝牙/耳机线控 + 来电/拔出暂停
- 倍速 0.5-3.0 + 自定义秒步进 (±5/15/30s 可配) + 顺序/随机/单曲循环/列表循环
- 快捷定时关闭 (15/30/60/90分 + 自定义, 到期 pause/stop)
- 本地自建歌单 (增删改排序, 拖拽), 多P合集一键成辑
- 播放进度记忆 (按 bvid+cid, 节流1s写入)
- 字幕展示：投稿字幕优先 > AI字幕，逐行滚动高亮，点击跳转

### 2.2 明确不做 (二期)
- 离线下载/缓存、均衡器、评论、投屏、AAuto、云同步、自建后端、登录导入 (预留接口)

### 2.3 成功标准
游客可完成：搜索公开视频 → 以音频加入本地歌单 → 后台播放 → 多P连续播 → 记忆进度 → 字幕滚动 → 定时关闭。

## 3. 总体架构 (方案 B - 特性模块化 Clean Architecture)

```
cn.debubu.tingbili
├── app (壳: Navigation + Theme + 权限)
├── core:ui (Material3 主题/组件)
├── core:media (Media3 唯一可信源)
├── core:data (Room + DataStore + Result<T>)
├── feature:home (发现/搜索 Paging3)
├── feature:playlist (歌单 CRUD + 队列)
├── feature:history (播放记录)
├── feature:player (全屏播放 + 字幕)
├── feature:settings (设置)
└── data:bilibili (B站 API 网关)
```

- 分层: `UI (Compose + ViewModel + UIState StateFlow) → Domain (UseCase) → Data (Repository + Room/网络)` 单向数据流
- DI: Hilt (core:media 单例)
- 导航: Navigation Compose 2.8 + type-safe routes
- 构建: Gradle KTS + Version Catalog (libs.versions.toml) + KSP + Kotlin 2.0
- 边界: feature 间不直调, 经 core:media/ core:data

## 4. 数据模型

### 4.1 曲目抽象 (关键)
```kotlin
data class Track(bvid:String, cid:Long, title:String, author:String, cover:String, durationMs:Long, subtitleUrl:String?)
```
- BV 多P合集 (如100P小说): bvid 展开 `List<Track>` = 虚拟专辑, 可整体或单P加入歌单
- BV 单P长视频 (数小时): 单 Track (cid = bvid)
- BV 单P短视频 (几分钟): 单 Track

```kotlin
data class LocalPlaylist(id:Long, name:String, createdAt:Long)
data class PlaylistTrack(playlistId:Long, track:Track, order:Int) // 去重按 bvid+cid
data class History(bvid:String, cid:Long, positionMs:Long, updatedAt:Long)
```

### 4.2 本地存储
- Room: `playlists`, `playlist_tracks`, `history` (history.db)
- DataStore Preferences: `stepSec`, `timerPresets`, `repeatMode`, `speed`, `theme`, `dynamicColor`

### 4.3 网络 (data:bilibili)
- 栈: Retrofit + OkHttp + Kotlinx Serialization + Coil
- 仅4接口: `search(type=video)` / `view(bvid→多P)` / `playurl(bvid,cid→dash/audio)` / `subtitle(bvid,cid)`
- Wbi签名与风控隔离在 `BiliAuthInterceptor`, 预留登录Cookie注入点, 游客无Cookie可用
- 缓存: view/search 5分钟, playurl不缓存, history本地唯一可信源
- 错误: `Result<T>` + `BiliError(风控/下架/需登录)` 映射空状态/重试

## 5. 媒体与播放 (core:media)

- Service: `TingBiliPlaybackService : MediaSessionService` + `ExoPlayer (media3 latest)` 仅音频轨道, 不创建 Surface, WakeLock/WifiLock按需, foreground可重建
- 会话: `MediaSession + MediaController` 接入 通知/锁屏/蓝牙/线控
- 队列: `PlayerManager` 持有 `List<MediaItem>`, 支持 顺序/随机/单曲/列表循环
- 控制: 倍速/步进/定时, `PlaybackState` 暴露 `StateFlow`
- 轻量: audio-only, Baseline Profile + R8, 冷启动<1s, 常驻<100MB, StrictMode检测

字幕: 解析 xml/json → `List<LyricLine(timeMs, text)>` → `LyricState` → Compose LazyColumn 高亮当前行, 基于 `player.currentPosition` 同步, 点击Seek.

## 6. 导航与 UI

### 6.1 底部导航
```
[首页] [歌单] (圆形mini播放器) [历史] [设置]
       ^ 圆环进度 + 封面旋转 + 点击进全屏
```
- 手机: `NavigationBar`, 平板/横屏: `NavigationRail`, mini播放器悬浮居中
- 4 Tab:
  - 首页: 搜索框 + 分区筛选 + Paging3列表 (封面/标题/UP/时长/分P数), 点击 bv → bottomSheet (整集合集加入 / 单P加入 / 直接播放)
  - 本地歌单: 卡片 + 详情 (Track拖拽/滑动删除/播放全部)
  - 历史记录: 按 updatedAt倒序, 进度条, 续播
  - 设置: 步进/定时预设/主题/动态取色/关于, 预留登录入口
- 播放页 (全屏): 大封面旋转 + 字幕区逐行高亮 + 控制条 (步进/倍速/定时/循环/上一首/下一首/P列表抽屉)

### 6.2 主题
- Material3 Expressive + dynamicColor, 深浅色, `core:ui:TingBiliTheme`, 仅 Coil加载封面, 无视频View
- 适配平板/横屏, 自适应布局

## 7. 工程与质量

- 技术栈: 采用 2026-09-01 时最新稳定版: Kotlin 2.0+, Compose BOM latest, Material3 latest, Navigation latest, Hilt latest, Room latest (KSP), DataStore, Media3 latest, Retrofit/OkHttp/Coil latest, Paging3, Coroutines
- 构建: Version Catalog + KSP + Gradle KTS
- 测试: `core:media` Robolectric+Turbine, `data:bilibili` MockWebServer, Compose Test + Screenshot
- 质量: ktlint + detekt + GitHub Actions (build/test/lint), .gitignore 忽略 local.properties
- 性能: R8 + Baseline Profile + StrictMode

## 8. 迭代规划
- MVP: 本文档范围
- 二期: 登录(扫码) → 导入收藏夹/稍后再看 → 云同步
- 三期: 推荐算法、桌面/鸿蒙 KMP (如需)

## 9. 开放问题 (已决)
- 已决: minSdk 31, 无视频链路仅官方 Media3, 深色+动态取色+平板横屏, 包名 cn.debubu.tingbili, 技术栈取最新, 参考 piliplus 但仅音频链路
