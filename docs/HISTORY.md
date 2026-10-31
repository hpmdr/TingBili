# 本地历史记录：按播单记录 + 来源恢复（2026-11-01）

> 目标：① 看之前听过什么；② 恢复之前的收听进度。行为变更必须同步更新本文。

## 1. 粒度：一条历史 = 一个播单

历史不再按 BV 一行，而是按**播放来源**一行：

| 播放来源 | 记录类型 | sourceId | 主标题 |
|---|---|---|---|
| BV 详情页 / 搜索 / 首页 / B站收藏 | BV | bvid | BV 标题 |
| 自定义收藏夹 | 收藏夹 | folderId | 收藏夹名 |
| 我的最爱 | 收藏夹 | 1 | 我的最爱 |

- 从收藏夹播放时，即使夹里有多个 BV，也只产生**一条收藏夹记录**，最后播放的 P/BV 跟着更新；
- 同一个 BV 从收藏夹播过、又从搜索播过，会有**两条记录**：一条夹名、一条 BV 名；
- 一条播单只保留最后一次播放的分 P + 位置，不是每个 P 一行。

## 2. 数据模型

```kotlin
@Entity(primaryKeys = ["sourceType", "sourceId"])
data class HistoryEntity(
    val bvid: String,             // 最后播放的分集
    val cid: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val listenDurationMs: Long,   // 本播单累计收听
    val sourceType: Int = SOURCE_BV,   // 0=BV, 1=收藏夹
    val sourceId: String = bvid,       // bvid / folderId.toString()
    val sourceTitle: String? = null,   // 播放时的夹名/BV 标题快照，不实时改名
)
```

Room v15：表重建（主键从 `bvid` 改成 `(sourceType, sourceId)`），旧数据全部迁移为
`sourceType=BV / sourceId=bvid`，位置不丢。

## 3. 写入

`HistoryTracker` 保存时：

- `sourceFolderId != null` → `sourceType=收藏夹`、`sourceId=folderId`、
  `sourceTitle=当前队列的 sourceTitle`（夹名/我的最爱）；
- 否则 → `sourceType=BV`、`sourceId=track.bvid`、
  `sourceTitle=track.videoTitle ?: track.title`；
- 累计收听按「播单 + 分集」记账：换播单载入该播单已累计值；同播单换 P 保留累计、重置位置基线。

## 4. 续播

### 收藏夹记录

1. 夹还在，且记录里的 `bvid+cid` 仍在 → 用夹**当前**内容重建队列、定位到该 P，
   带 `sourceFolderId`（继续跟随夹内增删/排序）；
2. 夹已删除 / 被清空 / 该 P 已移出 → 回退 BV 分 P，并提示：
   - 「原收藏夹已删除，已按 BV 续播」
   - 「该集已移出收藏夹，已按 BV 续播」
3. 回退后仍找不到该 `cid` → 「该分 P 已失效」，不乱播第一集。

### BV 记录

- `getView(bvid)` 找 `cid`，按 BV 分 P 续播；
- 找不到 → 「该分 P 已失效」。

### 自动续播（正常起播，不是点历史）

`PlayerManager.play()` 取位置顺序：

1. 当前来源（收藏夹或 BV）且 `bvid+cid` 一致的记录；
2. 该分 P 跨来源最近一条；
3. 都没有 → 从头播。

## 5. UI

### 收藏夹记录

```
[封面]  胖宝宝                         [收藏夹 ›]
        无限流悬疑多人有声《请活到天明》
        P17 · 295-308             11-01
        ─────────────────────────────
        上次听到 P17 · 1:31:17 / 1:31:17
```

- 主标题 = 收藏夹名；副标题 = 最后播放的 BV 标题；第三行 P/分 P 名/日期；
- `收藏夹 ›` 可点 → 跳转对应收藏夹（`FavoriteFolderRoute`）；
- 收藏夹已删除：标签置灰「收藏夹已删除」，历史行保留，续播走回退；
- 夹名不实时跟随改名，显示播放时快照；跳转按 id，仍能进当前夹。

### BV 记录

```
[封面]  无限流悬疑多人有声《请活到天明》   [BV]
        P17 · 295-308             11-01
        ─────────────────────────────
        上次听到 P17 · 1:31:17 / 1:31:17
```

- `[BV]` 是类型标识，不跳转；
- 主标题优先用 view 的 BV 标题，view 未回来时退 `sourceTitle`/bvid。

### 通用

- 单 P 不显示 `P1`，与播放页/迷你/通知栏一致；
- 按 `updatedAt` 倒序，收藏夹和 BV 混排；
- 「共 N 条」= N 个播单记录；
- 删除按 `(sourceType, sourceId)`，不会删收藏夹本身。

## 6. 受影响的逻辑

- `HistoryDao`：`getSource` / `getLatestForTrack` / `deleteSource`；旧 `get(bvid)` 删除；
- `PlayerManager`：自动续播按来源优先；
- `DetailViewModel` / `BvFavoriteViewModel` / `FavoriteFolderViewModel`：
  `observeByBvid(s)` 可能返回同一 track 的多条来源记录，统一 `latestPerTrack()` 取最新；
- `HistoryViewModel`：注入 `FavoritesRepository`，负责夹恢复、回退提示、删除按来源；
- `HistoryScreen`：来源 badge、夹跳转、Snackbar；
- `MainTabsPager`：把 `onFolderClick` 接到 `FavoriteFolderRoute`。

## 7. 情况矩阵

| 情况 | 显示 | 续播 |
|---|---|---|
| 自定义夹播放 | 「夹名」+ 收藏夹 › | 恢复夹当前队列到该 P |
| 我的最爱播放 | 「我的最爱」+ 收藏夹 › | 恢复我的最爱队列 |
| BV 页/搜索播放 | BV 标题 + BV | 恢复 BV 分 P |
| 同 BV 从夹 + 搜索播过 | 两条：夹名 + BV 名 | 各按来源恢复 |
| 同夹多次播放 | 一条，最后 P 更新 | 恢复该夹 |
| 夹改名 | 旧名 | 跳转/续播按 id，正常 |
| 夹删除 | 记录保留，标签置灰 | 回退 BV + 提示 |
| 夹清空 | 记录保留 | 回退 BV + 提示 |
| 最后 P 移出夹 | 记录保留 | 回退 BV + 提示 |
| 旧历史无来源 | 视为 BV | 恢复 BV |
| B站分 P 失效 | 记录保留 | 提示，不播错集 |
| 当前有别的播单 | — | 新播单替换旧播单 |

## 8. 验证

- 真机 v14→v15：旧历史全部变成 BV 记录，位置保留；
- 从我的最爱起播：历史出现「我的最爱」夹记录，副标题是 BV 标题；
- 点「收藏夹 ›」进入我的最爱；点行续播，播放器来源=`我的最爱`、`sourceFolderId=1`；
- 点 BV 记录续播：来源=`播放历史`、`sourceFolderId=null`、队列为 BV 全部 P；
- 单测：`HistoryTrackerTest`（夹/BV 来源落库）、`HistoryViewModelTest`（夹恢复、夹删除回退、
  P 移出回退、BV 失效）、`PlayerManagerTest`（来源优先 + 跨来源兜底）、
  `feature:history/core:media/player/home/mine/favorites/app` 全绿。
