# 播放顺序与随机播放（2026-10-31）

> 播放列表（章节目录）里的正序 / 倒序 / 随机，行为变更必须同步更新本文。

## 1. 概念

- **播放列表（当前队列）**：同一时间只有一条。章节弹窗展示的就是它。
- **正序 / 倒序**：只改当前这条队列的顺序，以及自动连播/手动下一首的去向；
  不改收藏夹、BV 收藏、BV 缓存页的来源顺序。
- **随机**：只对当前这条队列生效的开关，决定下一首怎么选；列表展示顺序不变。
- **随机方式**：全局偏好，在「播放设置 → 随机播放方式」里选。

新播放列表（从收藏夹/BV/搜索重新起播）会丢弃旧列表的正/倒序与随机开关，
默认 **正序 + 随机关**。正/倒序与随机开关随当前队列快照保存，冷启动恢复同一条队列时一并恢复。

## 2. 正序 / 倒序

- 实现：`QueueManager.setPlayOrder()` 原地反转当前队列，并按 `bvid:cid` 重新定位在播项；
  `PlayerManager.setPlayOrder()` 用反转后的队列重建 transport，回到原进度/播放态。
- 当前在播的那一集不打断，只是它在下标里的位置变了；下一首跟着新顺序走。
- 收藏夹实时同步时，`syncFolderQueue` 会按当前顺序对齐新内容（倒序时先反转收藏夹顺序再合并）。
- 章节弹窗的列表就是反转后的队列，当前项照常自动定位/高亮。

## 3. 随机播放

开关在章节弹窗（`播放列表` 标题右侧），属于当前队列。

### 3.1 洗牌（默认）

- 用 ExoPlayer `shuffleModeEnabled = true`，当前列表随机顺序、每轮每集最多一次。
- 列表展示顺序仍是用户选的正序/倒序；当前项高亮会随机跳。
- 自动连播和手动下一首都走 transport 的随机顺序。

### 3.2 纯随机

- 播放设置里选择「纯随机」后：每次下一首都从当前列表随机取，**可能重复同一集**。
- 实现：`PlayerManager.next()` 随机取下标并 `seekTo`；自动连播在
  `onMediaItemTransition(AUTO)` 里立刻改跳随机项（`randomRedirectTarget` 防递归）。
  为保证章尾一定还有“下一首”，纯随机期间 transport 临时用 `REPEAT_MODE_ALL`；
  关闭随机时恢复用户 repeat 偏好。
- 手动「上一首」仍按当前列表顺序回退，不做随机回溯。

## 4. next / previous

- 统一走 `transport.seekToNextMediaItem() / seekToPreviousMediaItem()`，让 ExoPlayer 按当前顺序
  （含洗牌随机）切歌；不再自己算下标重建队列。
- 顺序模式播到最后一集时，手动「下一首」不再绕回第一集（repeat off 下不动）。
- 冷启动 transport 尚未加载时，用当前队列/顺序重建后再跳。

## 5. 持久化

| 数据 | 落点 |
|---|---|
| 当前列表的正/倒序、随机开关 | `PlayQueueSnapshotEntity.playOrder / shuffleEnabled`（Room v14，`MIGRATION_13_14`） |
| 随机方式（洗牌 / 纯随机） | `PreferencesRepository.randomPlayMode`（DataStore `random_play_mode`） |
| 当前播放列表内容 | `PlayQueueSnapshotEntity.tracksJson`（保存的是当前顺序下的队列） |

冷启动恢复：`QueueManager.restore()` 恢复正/倒序与开关，`PlayerManager.restoreLastPlayback()`
恢复 UI 态；真正起播时 `rebuildTransport()` 再下发 shuffle/repeat。

## 6. UI

- 播放页底部辅助栏**保持原样**（定时/章节/倍速/音质/信息）。
- 章节弹窗标题行下方：`正序 / 倒序` 分段按钮（带 ↑/↓ 图标）+ 右侧 `随机播放` 开关 chip
  （Shuffle 图标；开启时高亮）。随机**方式**不在这里选，在播放设置里。
- 播放设置 → 播放 → 新增「随机播放方式」：洗牌播放 / 纯随机。

## 7. 验证（真机 Redmi K70E）

- 胖宝宝 P1 起播 → 切倒序：当前仍是 P1、进度 0:54 未回零；列表反转为 P1 在最后一行并高亮；
  快照 `playOrder=1`、`currentIndex` 跟着变。
- 切回正序：队列恢复原顺序，P1 重回第一行。
- 开启随机（洗牌）：连续点下一首，index 跳到随机位置而非 +1；快照 `shuffleEnabled=1`。
- 设置改「纯随机」后再点下一首：index 跳到非相邻项；把当前集拖到结尾等待自动切歌，
  会跳到随机项（实测 P2 → P9），而不是顺序下一首。
- 从另一个收藏夹重新起播：新队列 `playOrder=0`、`shuffleEnabled=0`，旧列表状态作废。
- 冷启动恢复同一条队列：章节弹窗仍显示「倒序」选中、「随机」开启。
- 单测：`core:media` 新增倒序/随机方式/纯随机下一首/队列持久化用例，全绿。

## 8. 分集标题口径（2026-10-31）

播放页封面下方、迷你播放器、收藏/历史/章节列表、通知栏/锁屏统一成：

```
P17 · 295-308
```

- 多 P 且序号已知才带 `P{n}`；多 P 的第 1 集显示 `P1`（否则分不清是第几 P）。
- 单 P（BV 标题与分 P 同名）不显示 `P1`，直接显示分 P 名。
- 没有分 P 序号时只显示标题。
- 规则收敛在 `core:data` 的 `episodePageLabel()` / `Track.episodeLabel()`：
  `core:ui.trackPageLabel()` 与 `core:media.PlayUrlPipeline` 的
  `MediaMetadata.title` 共用，避免播放页、迷你、通知栏各写一套。

