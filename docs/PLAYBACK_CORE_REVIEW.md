# TingBili 播放核心设计评审（PlayerManager 解耦）

> 落点：只谈 `core/media` 播放核心怎么拆，不动 UI、不动网络、不改对外行为。
> 上游结论：整体结构见 `docs/ARCH_REVIEW.md`（已指出"播放双真相源"最危险，队列接口化已落地）；
> 音频管线见 `docs/AUDIO_PIPELINE.md`（L0/L1/L2 三级策略已实现）。
> 本文方法：`PlayerManager.kt`（1090 行）逐段精读 + Media3 官方文档/UAMP 标杆对照。
> 按 AGENTS.md 约定：先落设计文档，等拍板再动手，本次不改一行实现代码。

## 1. 业界播放核心长什么样（标杆）

### 1.1 Media3 官方分层：Service 持 Player，UI 只经 Controller

官方后台播放指南的固定搭配是三件套：
`MediaSessionService`（Service 内持有唯一的 `ExoPlayer`）+
`MediaSession`（锁屏/通知/蓝牙/线控入口）+
`MediaController`（UI 进程经 `SessionToken` 连接，只发指令、只收回调）。

参考：

- <https://developer.android.com/media/media3/session/background-playback>
- <https://developer.android.com/media/media3/session/mvc>
- <https://developer.android.com/media/media3/guide/playback-basics>

这三条是"道"：**Player 的创建与释放权只在 Service**（`onDestroy` 释放），
UI 侧永远不直接摸 `ExoPlayer`。我们这一点是对的（见 §3），后面拆分不能把这一层拆坏。

### 1.2 UAMP 三分法：播控抽象 / 播控中介 / 队列数据中介

Google 官方播放样板 UAMP（`android-uamp`）的播放核心是三个角色：

- `Playback` 接口（播控抽象：play/pause/seek，ExoPlayer 实现只是其中之一，可 fake；
  对应我们的 `PlayerHandle`）。
- `PlaybackManager`（播控中介：收 `MediaSession` 回调，只做"播/停/切"决策，不存业务数据；
  对应我们的 `PlayerManager`——但 UAMP 的这个类很薄）。
- `QueueManager`（队列/数据中介：`MediaSession.timeline` 是唯一队列真相，
  元数据走 `MusicSource` 接口，换音源不碰播放器；对应我们缺失的一块）。

参考：<https://github.com/android/uamp>

核心启示只有一句：**队列是数据问题，播停是控制问题，历史/定时是副作用问题，
三者不该住在同一个类里**。UAMP 里巨型 `PlaybackManager`（兼 URL 拉取+队列+播控）
正是后来被拆分的反面教材——它和我们现在的 `PlayerManager` 长得几乎一样。

### 1.3 播放列表 API：Media3 希望你用整表语义，而不是手拼 timeline

Media3 的 `Player` 接口原生提供播放列表语义：
`setMediaItems / addMediaItem / removeMediaItem / replaceMediaItem / moveMediaItem`，
旧的 `ConcatenatingMediaSource` 手拼方式已被官方收敛掉。
也就是说"不断播刷新队列"官方已有原子操作可用，不需要应用层维护第二份 timeline 镜像。

参考：<https://developer.android.com/media/media3/guide/playlists>

### 1.4 同类音频应用：队列落盘 + 单 tick

AntennaPod（播客，音频优先，和我们最像）的经验是两条：
队列落数据库表（播放器只渲染，崩溃/杀进程恢复零丢失），
进度/历史共用一个 tick（多个 `while(true){delay}` 各自为政是耗电与抖动的根因）。
这两条正好打中我们 §4 的 P0-2 和 P0-3。

## 2. 我们的现状（实测）

### 2.1 文件清单（`core/media/`，共 14 个文件）

| 文件 | 行数/规模 | 职责 |
|---|---|---|
| `PlayerManager.kt` | 1090 行 | 6 职全揽（见 §2.2），构造 9 依赖 |
| `TingBiliPlaybackService.kt` | ~150 行 | Service 持 ExoPlayer + MediaSession + offload 下发 + 打点 |
| `ServicePlayerModule.kt` | ~120 行 | `@ServiceScoped` 建 ExoPlayer（offload 意愿/decoderFallback/缓存/UA） |
| `PlaybackConnection.kt` | ~80 行 | 拉起服务 + 建 MediaController + 监听器扇出 |
| `MediaControllerHandle.kt` | ~60 行 | `PlayerHandle` 生产实现：委托 Controller，`release()` 为空 |
| `MediaResolvers.kt` | ~40 行 | `PlayUrlResolver/CatalogResolver/ArtworkResolver` 三接口（ARCH_REVIEW P0-1 已落地） |
| `AudioCacheModule.kt` | ~50 行 | SimpleCache 音频缓存单例 |
| `AudioPipelineMonitor.kt` | ~40 行 | offload/解码器名/format 打点（内存态） |
| `PipelineInfo.kt` | ~50 行 | 播放信息快照（解码器种类判定与徽标同规则） |
| `PreferHardwareAudioDecoderSelector.kt` | ~60 行 | 硬解优先重排（audio/ 限域，HW>未知>SW） |
| `AudioDecoderInspector.kt` | ~60 行 | 本机解码器实测列表 |
| `PlaybackState.kt` | ~70 行 | UI 快照（含睡眠定时 4 字段） |
| `PlayerHandle.kt` | ~30 行 | 传输层抽象（单测 fake 用） |
| `MediaModule.kt` | ~10 行 | `PlayerHandle` 绑定 |

`feature/player/PlayerViewModel` 只读 `player.state` 再 combine 字幕/登录态，
UI 链路干净，这是好底子。

### 2.2 PlayerManager 的 6 个职责（9 个构造依赖）

构造依赖：`transport / historyDao / prefs / playUrls / catalog / artwork / playUrlCache / playQueue / appContext`。

| # | 职责 | 代码落点（PlayerManager.kt） | 本该属于谁 |
|---|---|---|---|
| 1 | 队列持有（queue/currentIndex + Room 快照 + 旧 DataStore 迁移 + refreshPlan/spliceTransport） | `restoreLastPlayback/saveQueueSnapshot/persistQueue/resolveUrls/toMediaItem/refreshQueue/computeRefreshPlan/spliceTransport/play` | `QueueManager` + `PlayUrlPipeline` |
| 2 | 播控（play/pause/seek/speed/repeat/next/previous/resume/toggle） | `play/pause/resume/toggle/seekTo/seekBy/seekStep/setSpeed/setRepeatMode/next/previous` | 薄 facade（保留签名） |
| 3 | 直链管线（cid 补齐 + 4 并发 resolve + TTL 缓存 + MediaItem 组装 + 403 过期重试 + 主动刷新） | `resolveCid/resolveUrls/toMediaItem/urlRefreshRetry/scheduleProactiveRefresh/attachPlayerListener` | `PlayUrlPipeline`（类 MediaSource） |
| 4 | 历史/进度（1s 节流写 + force 即时写 + 累计收听 + 位置轮询 + DataStore lastPosition） | `saveHistorySnapshot/startHistoryThrottle/startPositionPoll/listenAccumMs/lastTickPos` | `HistoryTracker`（单 tick） |
| 5 | 睡眠定时（deadline + DataStore 落盘 + 尾阶段 + 杀进程恢复） | `startSleepTimer/restoreSleepTimer/launchSleepCountdown/enterChapterTail/watchChapterTail/cancelSleepTimer/fireSleepTimer` | `SleepTimer`（纯逻辑） |
| 6 | 冷启动恢复 + 旧 DataStore 搬家（playurl 大 Map/旧三键队列） | `init{...}/restoreLastPlayback` | 各组件的 `restore()`（搬家代码已是一次性的，可删） |

### 2.3 三处"队列真相源" + 两个"位置轮询"

- 真相源 A：`PlayerManager.queue/currentIndex`（内存）。
- 真相源 B：`transport`（ExoPlayer playlist timeline）。
- 真相源 C：Room `PlayQueue` 单行快照 + DataStore `lastPositionMs`。
- `refreshQueue()` 用 300 行 mirror 对齐（deletions 倒序删 + spliceTransport 逐项走位，
  重排直接停手）——本质是在应用层重做 Media3 playlist 本该保证的一致性（§1.3）。
- 轮询 1：`startPositionPoll`（约 1s，内存态 position 更新）。
- 轮询 2：`startHistoryThrottle`（约 1s，Room + DataStore 写）。
- 两个 `while(true){delay}` 各自为政；单测坑 CONVENTIONS §6 已记录：
  触发过播放的测试必须在 `finally` 里 `release()`，否则 `runTest` 收尾永不返回。

### 2.4 生命周期错位：@Singleton（app 进程）指挥 Service 进程的 Player

`PlayerManager` 是 `@Singleton`（app 进程），真正的 `ExoPlayer` 在
`TingBiliPlaybackService`（可独立存活，`@ServiceScoped`）。
中间经 `MediaControllerHandle` 跨进程/跨组件指挥：
未连接时读操作回安全默认值、写操作 no-op（调用方 `play()` 先 `await` 就绪）。
这套容错是对的，但代价是"队列在 app 进程内存里、播放在 Service 里"——
杀进程恢复只能靠 Room 快照 + `resume()` 重建 MediaItem（现已实现，但分散在 6 职之间）。

## 3. 做得对的（拆分时保持不动）

1. **Service 持 Player、UI 经 Controller**：与 §1.1 官方分层一致，
   `ExoPlayer` 唯一释放权在 Service，`MediaControllerHandle.release()` 为空是刻意设计。
2. **transport 抽象可 fake**：`PlayerHandle` 让单测不依赖 ExoPlayer，
   这是 UAMP `Playback` 接口的同款思路，保留并复用。
3. **resolver 接口隔离 B 站**：ARCH_REVIEW P0-1 已落地，
   `core/media` 零 `data.bilibili` 引用，换音源只换实现。
4. **音频管线策略集中**：`ServicePlayerModule`（创建时下发）+
   `PreferHardwareAudioDecoderSelector`（选型）+ `AudioPipelineMonitor`（打点），
   见 `docs/AUDIO_PIPELINE.md`，本次不碰。
5. **UI 只认 `state`**：`PlayerViewModel` 读 `PlayerManager.state`，
   因此 facade 保留方法签名 + `PlaybackState` 不变时，UI 零改动。

## 4. 问题清单（按严重度）

- **P0-1 三处队列真相源（A 内存 / B transport / C Room），对齐靠 300 行 mirror**：
  `refreshQueue/computeRefreshPlan/spliceTransport` 是全文件最难懂的一段，
  且"重排直接停手"的 fallback 说明作者自己也不信它。
  对照 §1.2/UAMP：队列应该只有一个真相源（QueueManager），transport 只是渲染镜像。
- **P0-2 历史与位置双轮询**：两个 1s 循环 + `saveHistorySnapshot` 里顺手写
  DataStore `lastPosition`。对照 §1.4：合并为单 tick，
  "读 position → 更新内存 → 到节流点写 Room"一次走完。
- **P0-3 睡眠定时与播控同锁同类**：`sleepJob/watchChapterTail` 与播放状态机
  共享 `_state`，恢复逻辑（`restoreSleepTimer` 三分支）与播放恢复交织。
  它是纯时间逻辑，完全可抽成无 Android 依赖的类，虚拟时间单测（CONVENTIONS 坑 1 的重灾区）。
- **P1-1 `play()` 乐观更新 + 火后不管**：`next()/previous()` fire-and-forget
  `launch{play}`，`playMutex` 互斥 `refreshQueue` 但连点可丢请求；
  `play()` 内 `resolveCid→resolveUrls→setMediaItems` 任一步失败都靠 catch 兜，
  UI 与 transport 不一致只能靠"下次切歌自然对齐"（注释原话）。
- **P1-2 offload/倍速互斥散在两处**：`setSpeed` 拒绝非 1.0x +
  `applyAudioOffload` 回 1.0x + Service 侧 `applyOffloadLive` 下发，
  三处靠 `@Volatile offloadEnabled` 镜像同步。应收敛为一个 `AudioPipelinePolicy`。
- **P2-1 一次性搬家代码常驻**：`drainPlayUrlCache/drainLastQueue`
  早晚删（Room 快照接管后），留着每次冷启动多一次 DataStore 全量读。
- **P2-2 `seekTo` 钳制写了两遍**：`seekTo/seekStep` 各算一次 max，
  收敛为一个 `clampPosition()` 即可（顺手修，不单独立项）。

## 5. 目标架构（对外 API 不变，内部拆解）

```
UI (PlayerViewModel) ──只读──▶ PlayerManager (facade, 保留 state + 现有方法签名)
                                   │  ┌──────────────┐
                                   ├──▶│ QueueManager │──▶ PlayQueueDao / prefs(恢复)
                                   │   │ 唯一队列真相 │    computeRefreshPlan 纯函数
                                   │   └──────────────┘
                                   │  ┌────────────────┐
                                   ├──▶│ PlayUrlPipeline│──▶ PlayUrlResolver/Catalog/Artwork
                                   │   │ (类MediaSource) │   PlayUrlCacheDao / SimpleCache
                                   │   └────────────────┘
                                   │  ┌────────────────┐
                                   ├──▶│ HistoryTracker │──▶ HistoryDao / prefs.lastPosition
                                   │   │   (单 tick)    │
                                   │   └────────────────┘
                                   │  ┌──────────────┐
                                   ├──▶│  SleepTimer  │──▶ prefs(只存 deadline 三键)
                                   │   │  (纯逻辑)    │
                                   │   └──────────────┘
                                   │  ┌────────────────────┐
                                   └──▶│ AudioPipelinePolicy│──▶ prefs.audioHwDecode/audioOffload
                                       │ offload×倍速互斥   │   PreferHardwareAudioDecoderSelector
                                       └────────────────────┘
TingBiliPlaybackService (不变: 持 ExoPlayer/MediaSession/打点) ◀── transport ──┘
```

拆解原则（对照标杆逐条有出处）：

1. **QueueManager = 唯一队列真相**（§1.2 QueueManager）：收编
   `queue/currentIndex/sourceTitle + restoreLastPlayback + saveQueueSnapshot/persistQueue +
   refreshQueue/computeRefreshPlan/spliceTransport`；
   `RefreshPlan/spliceTransport` 提为无 Android 依赖的纯函数，单测零成本
   （CONVENTIONS 坑 1/2 直接绕开）。
2. **PlayUrlPipeline = 数据源层**（§1.2 MusicSource / §1.3 playlist 语义）：
   收编 `resolveCid/resolveUrls/toMediaItem/过期重试/主动刷新`；
   对外只暴露 `suspend fun itemsFor(tracks): List<TrackMedia>`，
   transport 操作（set/replace/add/remove）仍由 facade 按最终一致性下发。
3. **HistoryTracker = 单 tick**（§1.4）：一个 1s 循环同时做
   position 内存更新 + 节流落盘 + 累计收听；
   `pause/seek` 的 force 写走同一入口，不再各写各的。
4. **SleepTimer = 纯逻辑**（§1.2 副作用分离）：`deadline/countdown/tail/restore/cancel`
   全进该类，时间源 `clock()` 注入，DataStore 经小接口注入假实现；
   虚拟时间单测，不再进 `PlayerManager` 单测的重灾区。
5. **AudioPipelinePolicy = 互斥收敛**（§4 P1-2）：`offloadEnabled` 镜像 +
   `setSpeed` 钳制 + 开关打开回 1.0x 全收进来，Service 侧下发保持不动。
6. **PlayerManager 收敛为 facade**：只做"参数校验 → 调组件 → 合 `_state`"，
   目标 200 行以内；`PlaybackState` 字段不变，`PlayerViewModel` 零改。
7. **Service/transport/UI 三层不动**：§3 的 5 条优点原样保留，
   迁移期间任一时刻可编译、可单测、可真机播。

## 6. 迁移步骤（每步一个提交，步步可回滚）

| 步 | 内容 | 验证 |
|---|---|---|
| 0 | 本文（不碰实现） | 文档评审通过 |
| 1 | `QueueRefreshPlanner` 纯函数抽离（computeRefreshPlan/spliceTransport 的计算部分）+ 单测 | 新增单测绿，原有单测绿 |
| 2 | `SleepTimer` 抽离（含恢复/尾阶段），`PlayerManager` 只转发 + 合 `_state` | 睡眠定时单测（虚拟时间）+ 真机：倒计时/杀进程恢复/播完即停 |
| 3 | `HistoryTracker` 合并双轮询为单 tick | 历史单测 + 真机：进度/断点续播/累计收听 |
| 4 | `QueueManager` 抽离（队列+快照+恢复），transport 只做渲染镜像 | 队列单测 + 真机：冷启动恢复/刷新分P/不断播 |
| 5 | `PlayUrlPipeline` 抽离（resolve/组装/重试/主动刷新） | 管线单测 + 真机：403 过期恢复/无可播源提示 |
| 6 | `AudioPipelinePolicy` 收敛 + `PlayerManager` 收敛为 facade（目标 200 行内） | 全量相关单测 + 真机回归 + 更新 ARCH_REVIEW 落地进度 |
| 7 | 删一次性搬家代码（drainPlayUrlCache/drainLastQueue，需确认线上无旧版用户） | 灰度/版本号确认后再动 |

顺序讲究：1/2 纯逻辑先行、零风险建立"拆分范式"；
3/4 动数据流但 UI 无感；5/6 收尾；7 单独确认。

## 7. 待拍板（请选一条）

- **A 全拆（推荐）**：按 §6 走完 1→6，约 6 个提交。利：根治 P0 三项，
  后续播客源/本地音频直接复用；弊：两周量级，真机回归面大。
- **B 试点（稳妥）**：只做步骤 1+2（RefreshPlanner + SleepTimer），
  验证拆分范式与单测收益后再定。利：一周内见分晓，风险最低；弊：P0-2/P1-1 留着。
- **C 维持**：不拆，只修 P2（clamp 收敛/删搬家代码）。利：零风险；
  弊：ARCH_REVIEW#3"双真相源"继续背，越往后越难拆。

我的建议：**B 先行**——步骤 1 是纯函数抽离，天然可单测，
正好验证"纯逻辑类脱离 Robolectric"的路走不走得通（CONVENTIONS 坑 1/2），
走通了再 A，一次只冒一步的风险。

## 8. 参考

- Media3 后台播放：<https://developer.android.com/media/media3/session/background-playback>
- Media3 会话 MVC：<https://developer.android.com/media/media3/session/mvc>
- Media3 播放列表：<https://developer.android.com/media/media3/guide/playlists>
- Media3 播放基础：<https://developer.android.com/media/media3/guide/playback-basics>
- UAMP 官方样板：<https://github.com/android/uamp>
- 本仓库：`docs/ARCH_REVIEW.md` §3（双真相源）/ `docs/AUDIO_PIPELINE.md`（管线已实现）
  / `docs/CONVENTIONS.md` §6（单测三坑）
