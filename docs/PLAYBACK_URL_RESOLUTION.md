# 播放直链解析：惰性解析 + 风控兜底

> 2026-10-31。行为变更必须同步更新本文。

## 1. 现象

- 播一个 1:46 的短视频，界面显示的是新曲目，但声音还是上一集，进度/时长也是上一集的（例如 `4:18 / 1:31:17`）。
- 历史表被写脏：新 bvid 记录下旧播放位置（`5:37`，而该视频只有 `1:46`）。
- 播放页报 `暂无可播放音源` / `Source error`。

## 2. 根因

旧实现是**起播前把整个队列的直链一次性解析**（`PlayUrlPipeline.resolveUrls`）：

- 一部 67 P 的视频 = 起播瞬间 67 次 `playurl` 请求（4 并发）；
- B 站按「IP + 登录态」风控，直接返回 `code -412`（文案 `受到神秘力量干扰，请稍后再试！`）；
- 之后短时间内**所有**分 P 解析都失败，下一个视频也解析不出来 → `items` 为空；
- 但界面已经乐观切到新曲目，旧音频没停、时长/进度还是旧值 → 「新标题 + 旧声音」；
- `HistoryTracker` 按 `state.currentTrack`（新 bvid）+ 播放器位置（旧）落盘 → 历史错位。

日志证据（`adb logcat -s TingBiliPlay`）：

```
resolve BV1hN4y1s77j:1382111641 qn=30232 -> 受到神秘力量干扰，请稍后再试！
... 连续 60+ 条，只有 1 条 -> ok
```

## 3. 方案

### 3.1 惰性直链（核心）

- `MediaItem` 不再携带 CDN 直链，只带自描述 URI：
  `tingbili-audio://play?bvid=BV...&cid=123`（`PlayUrlPipeline.buildItem`）。
- Service 侧用 `ResolvingDataSource.Factory` 包住 HTTP 数据源：真正要读某集时才调用
  `AudioUrlResolver.resolveBlocking(bvid, cid)` 换成本次解析出的直链。
- 结果：起播只解析「当前这一集」，67 P 视频起播 = 1 次请求，秒开；跳集/下一首各解析一次。
- 直链按 `bvid:cid` 落库（`PlayUrlCacheEntity`，TTL 90min），重播/续播不再请求；
  命中缓存时 `CacheDataSource` 先查音频缓存，甚至不需要解析直链（离线也能放已缓存集）。

### 3.2 风控兜底

- `BiliMediaResolvers.resolve` 遇到 `-412 / 神秘力量`：改用**游客身份**（跳过登录 Cookie）
  重试一次。游客请求不受账号级风控影响（实测登录态 412 时游客 `ok`）。
- 命中后进入 10 分钟冷却，期间直接走游客，避免每条都先撞一次 412。
- 游客通常只有 64K/132K：音质降级但不断播；冷却结束自动探测登录态是否恢复。
- 游客标记用内部请求头 `X-TingBili-Guest`，`WbiInterceptor` 读取后**移除**，不会外发。

### 3.3 失效与重试

- 音质切换（`PlayerManager.refreshCurrentUrl`）与 403 重试（`onRetryRecover`）都先删掉该集
  直链缓存，再 replace 惰性 item + `prepare`，保证按新偏好/新 URL 重新解析，而不是复用旧缓存。
- 播放错误文案取 `PlaybackException.cause`（外层通常是无信息量的 `Source error`），
  解析失败时展示「音源解析失败（可能触发风控）」。

### 3.4 起播一致性（上一轮已修，保留）

- `PlayerHandle.awaitReady()`：冷启动 `MediaController` 未连上时先等，避免 `setMediaItems` 被静默丢弃。
- 切歌前先暂停旧声；乐观更新同步重置时长/进度；`setMediaItems` 抛异常时回滚到上一首并恢复播放。

## 4. 落点

| 决策 | 落点 |
|---|---|
| 惰性 URI 协议、MediaItem 组装 | `core/media/.../PlayUrlPipeline.kt` |
| 打开时解析 + 直链缓存 + 过期兜底 | `core/media/.../AudioUrlResolver.kt` |
| DataSource 挂载 | `core/media/.../ServicePlayerModule.kt`（`ResolvingDataSource` + `CacheDataSource`） |
| 起播/切歌/回滚/缓存失效 | `core/media/.../PlayerManager.kt` |
| 412 → 游客重试 + 冷却 | `data/bilibili/.../BiliMediaResolvers.kt` |
| 游客标记跳 Cookie | `data/bilibili/.../WbiInterceptor.kt`、`BiliApi.playUrl(guest=)` |
| 直链缓存表 | `core/data/.../db/PlayUrlCache.kt`（`get/putAll/delete/prune*`） |

## 5. 验证（真机 Redmi K70E）

- 67 P 视频起播：日志只有当前 1 集的解析；`next()` 逐集解析，队列/分 P 序号正确（P1→P2→P3）。
- 账号被风控期间：`resolve ... failed: 受到神秘力量干扰` → `guest retry ... -> ok`，正常出声。
- 冷却期内再切集：只发一条游客请求，无 412 日志。
- 已缓存集重播：0 次直链请求（URL 缓存命中），UI 时长/进度正确。
- 收藏夹内切歌：标题、时长、进度一致，无「新标题 + 旧声音」。
- 单测：`:core:media`（含惰性解析/缓存/失败兜底）、`:data:bilibili`（含 `WbiInterceptorTest`）、
  `:core:data`、`:feature:player/favorites/history/home/mine`、`:app` 全绿。

## 6. 已知取舍

- 游客兜底期间音质可能降到 64K/132K，UI 面板仍显示偏好档；属风控期的降级妥协。
- 惰性解析意味着「队列里未播的集」不预热直链；弱网下首次切集会有一次解析延迟（通常 <300ms，
  且被 ExoPlayer 的缓冲掩盖）。
