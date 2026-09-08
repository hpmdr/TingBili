# 播放服务独立化设计（方案 A：标准 Media3 架构）

> 范围：完整后台播放（前台状态同步 + 后台/锁屏继续播 + 通知栏/耳机线控）。
> 对标：网易云/QQ 音乐/Spotify/Apple Music/Media3 UAMP——前台 Service 唯一持有 ExoPlayer + MediaSession，UI 只经 MediaController 发命令、收状态。

## 1. 现状诊断（实测代码）

- `core/media/PlayerManager.kt` 是 `@Singleton`，详情页与播放页共用它，状态源统一。播放页“不同步”的直接原因是：
  1. `feature/detail/.../DetailScreen.kt:113/115` 的 `onPlayAll/onPlayPage` 只调 `play()`，**不导航到播放页**；
  2. `PlayerManager.play()` 串行解析（`resolveCid` + 逐个 `getPlayUrl`）全跑完才 `update state`，期间 UI 无反馈。
- `TingBiliPlaybackService`（`MediaSessionService`）仅在 Manifest 声明，全工程无 `startForegroundService` / `MediaController` / `SessionToken`（已全库检索确认）。后果：后台无保活、无通知、无比线控；且 `MediaModule` 把同一个 `@Singleton Player` 同时注入 Service 与 `PlayerManager`，`Service.onDestroy` 会 `release()` 掉仍在用的实例（双 owner）。
- Manifest 已就绪：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、service `mediaPlayback` 声明均已存在。缺的只有 `POST_NOTIFICATIONS`（Android 13+ 显示通知需要）。

## 2. 架构（目标）

```
UI 层（各 ViewModel / MiniPlayer）
  │  只依赖 PlayerManager.state + 控制方法
  ▼
PlayerManager（@Singleton，队列门面：cid 解析、取链、拼 MediaItem、历史节流、配置）
  │  经 PlayerHandle（传输层接口，保持不变）
  ▼
MediaControllerHandle → PlaybackConnection → MediaController ──bind── MediaSession
                                                                       ▲
TingBiliPlaybackService（唯一 ExoPlayer owner，@ServiceScoped Player）───┘
```

- 唯一 owner：ExoPlayer 只在 Service 作用域创建、只在 `Service.onDestroy` 释放。
- 唯一入口：UI 永远不碰 `Player`，命令经 Controller，状态经 `Player.Listener` 回流 `PlayerManager.state`。
- 启动链：`play()` 先 `startForegroundService`，再等 Controller 连接（pending 命令排队），乐观更新队列状态，首个可播立即起播。

## 3. 组件（文件级）

| 文件 | 职责 |
|---|---|
| `core/media/.../PlaybackConnection.kt`（新建） | `SessionToken` + `MediaController` 异步连接；`controllerFlow`；`awaitController()`；`addListener` 扇出到新旧 Controller；`release()` |
| `core/media/.../MediaControllerHandle.kt`（新建） | `PlayerHandle` 的生产实现，委托当前 Controller；`release()` 空实现并注明归属 |
| `core/media/.../ServicePlayerModule.kt`（新建） | `@InstallIn(ServiceComponent::class)` + `@ServiceScoped` 提供带 B 站请求头（Referer/UA）的 ExoPlayer |
| `core/media/.../MediaModule.kt`（改） | 删除 `@Singleton Player` 与 `ExoPlayerHandle` 绑定；`providePlayerHandle(connection)` 返回 `MediaControllerHandle`；`TimerManager` 绑定不变（自动改走 Controller） |
| `core/media/.../PlayerHandle.kt`（改） | 接口原样保留（测试零改动）；删除 `ExoPlayerHandle`；补 KDoc 说明传输层语义 |
| `core/media/.../PlayerManager.kt`（改） | 构造参数 `player` 更名 `transport`（调用方全是位置传参，安全）+ 新增 `@ApplicationContext context`；`play()` 首行拉起前台服务 + 乐观更新队列状态；注释补齐 |
| `core/media/.../TingBiliPlaybackService.kt`（改） | 注入改为 Service 作用域 Player；其余逻辑不变 |
| `feature/detail/.../DetailScreen.kt`（改） | 新增 `onPlayNavigate` 参数，播后调用 |
| `app/.../navigation/AppNavHost.kt`（改） | 详情页传入 `onPlayNavigate = 导航到 PlayerRoute（launchSingleTop）` |
| `app/.../MainActivity.kt`（改） | 注入 `PlaybackConnection` 并 `connect()`；Android 13+ 申请 `POST_NOTIFICATIONS` |
| `app/src/main/AndroidManifest.xml`（改） | 加 `POST_NOTIFICATIONS` 权限声明 |
| 4 处测试构造点（改） | `PlayerManager(...)` 补 `context` 实参；Fake 不变（接口未动） |

## 4. 数据流

详情播全部 → `startForegroundService`（幂等）→ 等 Controller（冷启动时 MainActivity 已建连，通常即时就绪）→ 乐观写队列/当前曲目 → 逐个解析 URL → 首个可播 `setMediaItems+prepare+play` → `onMediaItemTransition/onIsPlayingChanged` 回写 `state` → 播放页/mini-player/通知栏三方一致。断连期间仅 `play()` 挂起等待，其余控制为 no-op（冷启动建连后实际不可达）。

## 5. 错误处理

- 单曲 URL 失败跳过；整队失败 `isPlaying=false`（保留现有语义，播后加 Toast 由调用方现有 `message` 通道承载，不新增通道）。
- Controller 断连：除 `play()`（挂起等待）外其余控制为 no-op，避免空指针。
- `onTaskRemoved` 不停播；通知由 Media3 自动管理。

## 6. 测试

- 现有 `PlayerManagerTest` / home / history / playlist 的 Fake 套件保持通过（接口未变，只补构造实参）。
- 新增：`play() 拉起前台服务`（Robolectric `ShadowApplication` 断言 service intent）；Controller 排队语义用 Fake 覆盖（`MediaControllerHandle` 行为经可注入的 `PlaybackConnection` 替身验证，必要时给 `PlaybackConnection` 加构造注入的 `controllerFlow` 以便单测）。
- 真机验收：详情播全部自动进播放页；前后台切换；锁屏/通知栏暂停；拔耳机停播；杀进程后通知消失无崩溃。

## 7. 不做的（YAGNI）

- 独立进程 Service、下载/缓存、桌面小组件、Android Auto——均不在本期。
- `PlaybackState` 不加字段（乐观更新复用现有字段）。
