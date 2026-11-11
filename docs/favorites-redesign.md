# 收藏系统重设计 · 实现计划

> 状态：阶段 7 已完成（2026-10-31），收藏系统重设计收尾完成。
> 范围：本地收藏（我的最爱 / 手动收藏夹 / BV 收藏）+ B站账号收藏入口（只读）。
> 旧数据不迁移：升级时 Room 全库清空（已确认）。

---

## 1. 目标与原则

1. 收藏 Tab 同时承载「本地收藏」和「B站账号收藏入口」，但两者数据完全隔离。
2. 本地收藏分三类，边界清晰：
   - **BV 收藏**：引用一个 BV，只读、不可编辑；本地保留一份缓存镜像，打不开时仍能看到内容。
   - **手动收藏夹**：用户创建，放任意 BV 的任意 P，可改名/删除/排序。
   - **我的最爱**：系统默认收藏夹，规则同手动收藏夹，但固定第一、不可改名、不可删除。
3. 所有“添加”动作都尽量轻量；所有“管理/删除”动作集中在收藏页与收藏夹详情页。
4. B站账号侧只读，不接任何 B站写接口。
5. 新的数据层、模块、路由都按新需求设计，不沿用旧 `Playlist` 语义。

---

## 2. 已定产品规则

### 2.1 收藏 Tab

- 顺序固定：
  1. 我的最爱
  2. B站收藏（入口卡）
  3. 手动收藏夹（可拖拽排序）
  4. BV 收藏（按收藏时间倒序）
- 顶栏标题：`收藏`；副标题：`X 个收藏夹 · Y 个 BV`。
- 右上角菜单：`新建收藏夹`、`排序收藏夹`。
- 排序只作用于手动收藏夹；少于 2 个时禁用。
- 新建的手动夹追加到末尾。
- 我的最爱：新收藏的 P 放最前。
- 手动收藏夹：新 P 追加到末尾。
- 空分区显示空态提示，不隐藏。
- B站卡未登录：`登录后查看 B站收藏`，点击进入登录引导页。

### 2.2 收藏夹详情

- 播放全部 / 继续播放。
- 单集移出，带确认。
- 手动夹：重命名、删除收藏（带确认）。
- 我的最爱：不显示重命名/删除。
- 不做夹内拖动排序。
- 空态：
  - 我的最爱：`还没有收藏，播放页点爱心试试`
  - 手动夹：`从 BV 详情长按收藏加入，或在播放页点爱心`

### 2.3 BV 收藏缓存页

- 卡片点进来就是该页，**页内不再提供「打开 BV 详情」**。
- 每次进入都强制刷新：`BiliRepository.getViewDetail(bvid, forceRefresh = true)`，绕过 5 分钟内存 memo。
- 先展示缓存；顶部显示“正在同步…”细进度条。
- 刷新成功：更新 BV 信息 + 整份替换 P 列表 + 更新 `lastSyncedAt`。
- 刷新失败：保留缓存，顶部告警条写明原因 + 上次同步时间 + 重试。
  - 网络异常：`网络异常，以下为缓存内容`
  - BV 失效/不可见/已删除：`BV 已失效或不可见，以下为缓存内容`
  - 其他：直接展示接口返回原因
- 无缓存且失败：整页错误 + 原因 + 重试 + 取消收藏。
- 操作：播放全部 / 继续播放、加入收藏夹（只加不删）。
- 右上角菜单：刷新、取消收藏（带确认）。
- P 列表复用“上次听到 / 正在播放”焦点逻辑。
- 播放仍需要联网取 playurl；离线时列表可看，播放走播放器现有错误提示。

### 2.4 B站收藏页（只读）

- 使用 `/x/v3/fav/folder/created/list-all` 一次拉全，不再分页。
- 页面结构：
  1. 稍后再看入口行
  2. 默认收藏夹（识别到才显示）
  3. 我创建的收藏夹
- 默认收藏夹判断：`attr & 2 == 0`；`attr` 缺失时兜底标题是否为「默认收藏夹」。
- 不接 B站写接口，不做“我收藏的收藏夹”。
- 点视频 → BV 详情页。
- 未登录 → 登录引导页。

### 2.5 BV 详情页

- 按钮：`收藏BV` / `已收藏BV`。
- 单击：整 BV 进/出 BV 收藏，snackbar 提示。
- 长按：打开「收藏到收藏夹」弹层。
  - 多 P：P 多选，默认全不选，提供全选/全不选。
  - 单 P：自动选中，隐藏 P 选择区。
  - 收藏夹：我的最爱 + 手动夹，多选。
  - 弹层内「新建收藏夹」= 新建并立即把当前选中的 P 收进去，然后关闭弹层。
  - 只加不删；重复自动跳过。
- 按钮下提示：`长按收藏可挑选分P加入收藏夹`。

### 2.6 播放页

- 顶栏 `⋮` 左边加爱心；无当前曲目时隐藏。
- 单击：当前 P 进/出「我的最爱」。
- 长按：弹层列出「我的最爱 + 手动收藏夹」，显示当前 P 是否已在。
  - 单选，点一下切换并关闭。
  - 弹层内「新建收藏夹」= 新建并立即收当前 P，然后关闭。
- 爱心实心只表示当前 P 在「我的最爱」，不反映手动夹。
- 所有操作给 snackbar 反馈。

### 2.7 命名

- B站账号侧统一叫「稍后再看」（Me 页、登录页、B站页、稍后再看页一起改）。
- 详情页按钮叫「收藏BV / 已收藏BV」。
- 收藏 Tab 标题叫「收藏」。
- 区块名：`我的最爱`、`B站收藏`、`收藏夹`、`BV 收藏`。

---

## 3. 信息架构与路由

| 路由 | 页面 | 参数 |
|---|---|---|
| `FavoritesRoute` | 收藏 Tab（主 Tab，page 1） | 无 |
| `FavoriteFolderRoute` | 收藏夹详情 | `folderId: Long` |
| `BvFavoriteRoute` | BV 收藏缓存页 | `bvid: String` |
| `BiliFavoritesRoute` | B站收藏总页 | 无 |
| `BiliFavDetailRoute` | B站收藏夹内容 | `mediaId: Long, title: String = ""` |
| `BiliToviewRoute` | B站稍后再看 | 无 |
| `BiliHistoryRoute` | B站历史（保留在 mine） | 无 |
| `VideoDetailRoute` | BV 详情（现有） | `bvid: String` |
| `PlayerRoute` | 播放页（现有） | 无 |

- `PlaylistRoute` / `PlaylistDetailRoute` 删除。
- `PlaylistRoute` 的主 Tab 位置由 `FavoritesRoute` 替代，`mainTabPageOf` / `mainTabRouteOf` / `isMainTabRoute` / `isMainTabDestination` 同步修改。
- 收藏 Tab 的卡片跳转：
  - 我的最爱 / 手动夹 → `FavoriteFolderRoute`
  - BV 收藏 → `BvFavoriteRoute`
  - B站收藏 → `BiliFavoritesRoute`

> 路由注意事项：`FavoritesRoute` 是 tab/page 映射和冷启动用的伪路由，**不要**在 `AppNavHost` 里注册 `composable<FavoritesRoute>`；主 Tab 容器仍是 `MainTabsRoute`，page 1 由 `MainTabsPager` 渲染 `FavoritesScreen`。`NavigationTest` 只测真实 destination：`FavoriteFolderRoute`、`BvFavoriteRoute`、`BiliFavoritesRoute`、`BiliFavDetailRoute`、`BiliToviewRoute`。

---

## 4. 数据模型

### 4.1 实体

```kotlin
@Entity(tableName = "FavoriteFolderEntity")
data class FavoriteFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
)

@Entity(
    tableName = "FavoriteTrackEntity",
    primaryKeys = ["folderId", "bvid", "cid"],
    foreignKeys = [
        ForeignKey(
            entity = FavoriteFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("folderId")],
)
data class FavoriteTrackEntity(
    val folderId: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val videoTitle: String? = null,
    val pageIndex: Int? = null,
    val order: Int = 0,
)

@Entity(tableName = "BvFavoriteEntity")
data class BvFavoriteEntity(
    @PrimaryKey val bvid: String,
    val title: String,
    val author: String = "",
    val authorMid: Long? = null,
    val cover: String = "",
    val durationMs: Long = 0,
    val pageCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long? = null,
)

@Entity(
    tableName = "BvFavoritePageEntity",
    primaryKeys = ["bvid", "cid"],
    foreignKeys = [
        ForeignKey(
            entity = BvFavoriteEntity::class,
            parentColumns = ["bvid"],
            childColumns = ["bvid"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bvid")],
)
data class BvFavoritePageEntity(
    val bvid: String,
    val cid: Long,
    val pageIndex: Int,
    val title: String,
    val durationMs: Long = 0,
    val order: Int = 0,
)
```

### 4.2 汇总模型

```kotlin
data class FavoriteFolderSummary(
    @Embedded val folder: FavoriteFolderEntity,
    val trackCount: Int,
    val totalDurationMs: Long,
    val firstCover: String?,
    val lastPlayedPageIndex: Int?,
    val lastPlayedPositionMs: Long?,
)
```

- `firstCover`：夹内第一条有封面的 P 的封面；不单独存收藏夹封面。
- `lastPlayed*`：与现有 `PlaylistSummary` 相同，join `HistoryEntity` 取最近一条。

### 4.3 BV 缓存输入模型（避免 core:data 反向依赖 data:bilibili）

`FavoritesRepository` 住在 `core:data`，而 `ViewData` 在 `data:bilibili`。如果 Repository 直接收 `ViewData`，会形成 `core:data -> data:bilibili -> core:data` 的循环依赖。因此 Repository 只收下面这个中立模型，`ViewData` 的映射放在 `data:bilibili`：

```kotlin
data class BvFavoriteSnapshot(
    val bvid: String,
    val title: String,
    val author: String,
    val authorMid: Long?,
    val cover: String,
    val durationMs: Long,
    val pages: List<BvFavoritePageSnapshot>,
)

data class BvFavoritePageSnapshot(
    val cid: Long,
    val pageIndex: Int,
    val title: String,
    val durationMs: Long,
)
```

`data:bilibili/dto/ViewDto.kt` 增加：

```kotlin
fun ViewData.toFavoriteSnapshot(): BvFavoriteSnapshot
```

由 `toTracks()` 生成 pages，`pageCount = pages.size`，`durationMs = duration * 1000L`。

### 4.4 关键约束

- 默认夹固定 `id = 1`，`isDefault = true`，`sortOrder = 0`。
- 所有创建收藏夹/加入收藏的入口，先调用 `ensureDefaultFolder()`，避免默认夹 id 被手动夹占用。
- 夹内 P 唯一键 `(folderId, bvid, cid)`；同夹重复插入静默跳过。
- 同一个 P 可存在于多个夹。
- BV 收藏唯一键 `bvid`；BV 缓存页唯一键 `(bvid, cid)`。
- 删除收藏夹：外键级联删除夹内 P；默认夹在 DAO 和 Repository 双重拦截。
- 删除 BV 收藏：外键级联删除缓存 P。

---

## 5. DAO / Repository

### 5.1 `FavoriteDao`（新增）

```kotlin
@Dao
interface FavoriteDao {
    // Folders
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FavoriteFolderEntity): Long

    @Query("SELECT * FROM FavoriteFolderEntity WHERE id = :id")
    suspend fun getFolder(id: Long): FavoriteFolderEntity?

    @Query("SELECT * FROM FavoriteFolderEntity WHERE id = :id")
    fun observeFolder(id: Long): Flow<FavoriteFolderEntity?>

    @Query("SELECT * FROM FavoriteFolderEntity WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultFolder(): FavoriteFolderEntity?

    @Query("SELECT * FROM FavoriteFolderEntity ORDER BY isDefault DESC, sortOrder ASC, createdAt ASC")
    fun observeFolders(): Flow<List<FavoriteFolderEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM FavoriteFolderEntity WHERE isDefault = 0")
    suspend fun maxFolderSortOrder(): Int

    @Query("SELECT * FROM FavoriteFolderEntity ORDER BY isDefault DESC, sortOrder ASC, createdAt ASC")
    fun observeFolderSummaries(): Flow<List<FavoriteFolderSummary>>

    @Query("UPDATE FavoriteFolderEntity SET name = :name WHERE id = :id AND isDefault = 0")
    suspend fun renameFolder(id: Long, name: String)

    @Query("UPDATE FavoriteFolderEntity SET sortOrder = :sortOrder WHERE id = :id AND isDefault = 0")
    suspend fun updateFolderSort(id: Long, sortOrder: Int)

    @Query("DELETE FROM FavoriteFolderEntity WHERE id = :id AND isDefault = 0")
    suspend fun deleteFolder(id: Long): Int

    // Folder tracks
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: FavoriteTrackEntity): Long

    @Query("SELECT * FROM FavoriteTrackEntity WHERE folderId = :folderId ORDER BY `order`")
    suspend fun getTracks(folderId: Long): List<FavoriteTrackEntity>

    @Query("SELECT * FROM FavoriteTrackEntity WHERE folderId = :folderId ORDER BY `order`")
    fun observeTracks(folderId: Long): Flow<List<FavoriteTrackEntity>>

    @Query("SELECT COALESCE(MAX(`order`), -1) FROM FavoriteTrackEntity WHERE folderId = :folderId")
    suspend fun maxOrder(folderId: Long): Int

    @Query("SELECT MIN(`order`) FROM FavoriteTrackEntity WHERE folderId = :folderId")
    suspend fun minOrder(folderId: Long): Int?

    @Query("DELETE FROM FavoriteTrackEntity WHERE folderId = :folderId AND bvid = :bvid AND cid = :cid")
    suspend fun deleteTrack(folderId: Long, bvid: String, cid: Long)

    @Query("SELECT folderId FROM FavoriteTrackEntity WHERE bvid = :bvid AND cid = :cid")
    fun observeFolderIdsContaining(bvid: String, cid: Long): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM FavoriteTrackEntity WHERE folderId = :folderId AND bvid = :bvid AND cid = :cid)")
    fun observeTrackInFolder(folderId: Long, bvid: String, cid: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM FavoriteTrackEntity WHERE folderId = :folderId AND bvid = :bvid AND cid = :cid)")
    suspend fun isTrackInFolder(folderId: Long, bvid: String, cid: Long): Boolean

    // BV favorites
    @Upsert
    suspend fun upsertBvFavorite(favorite: BvFavoriteEntity)

    @Query("DELETE FROM BvFavoriteEntity WHERE bvid = :bvid")
    suspend fun deleteBvFavorite(bvid: String)

    @Query("SELECT * FROM BvFavoriteEntity WHERE bvid = :bvid")
    suspend fun getBvFavorite(bvid: String): BvFavoriteEntity?

    @Query("SELECT * FROM BvFavoriteEntity WHERE bvid = :bvid")
    fun observeBvFavorite(bvid: String): Flow<BvFavoriteEntity?>

    @Query("SELECT * FROM BvFavoriteEntity ORDER BY addedAt DESC")
    fun observeBvFavorites(): Flow<List<BvFavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM BvFavoriteEntity WHERE bvid = :bvid)")
    fun observeIsBvFavorited(bvid: String): Flow<Boolean>

    // BV cache pages
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBvPages(pages: List<BvFavoritePageEntity>)

    @Query("DELETE FROM BvFavoritePageEntity WHERE bvid = :bvid")
    suspend fun clearBvPages(bvid: String)

    @Query("SELECT * FROM BvFavoritePageEntity WHERE bvid = :bvid ORDER BY `order`")
    fun observeBvPages(bvid: String): Flow<List<BvFavoritePageEntity>>

    @Query("SELECT * FROM BvFavoritePageEntity WHERE bvid = :bvid ORDER BY `order`")
    suspend fun getBvPages(bvid: String): List<BvFavoritePageEntity>

    // Cold start
    @Query("SELECT (SELECT COUNT(*) FROM FavoriteTrackEntity) + (SELECT COUNT(*) FROM BvFavoriteEntity) > 0")
    suspend fun hasAnyFavorite(): Boolean
}
```

> `observeFolderSummaries` 的实际 SQL 需要 join `FavoriteTrackEntity` 和 `HistoryEntity`，与现有 `PlaylistSummary` 查询同构；实现时以 Room 编译校验为准。

### 5.2 `FavoritesRepository`（新增，`core:data`）

```kotlin
interface FavoritesRepository {
    fun observeFolderSummaries(): Flow<List<FavoriteFolderSummary>>
    fun observeFolders(): Flow<List<FavoriteFolderEntity>>
    fun observeBvFavorites(): Flow<List<BvFavoriteEntity>>
    fun observeBvFavorite(bvid: String): Flow<BvFavoriteEntity?>
    fun observeBvPages(bvid: String): Flow<List<BvFavoritePageEntity>>
    fun observeFolderIdsContaining(bvid: String, cid: Long): Flow<Set<Long>>
    fun observeTrackInFolder(folderId: Long, bvid: String, cid: Long): Flow<Boolean>
    fun observeIsBvFavorited(bvid: String): Flow<Boolean>

    suspend fun ensureDefaultFolder(): Long
    suspend fun createFolder(name: String): Long
    suspend fun renameFolder(id: Long, name: String)
    suspend fun deleteFolder(id: Long): Boolean
    suspend fun reorderFolders(orderedIds: List<Long>)
    suspend fun addTracksToFolders(folderIds: Set<Long>, tracks: List<Track>): AddTracksResult
    suspend fun removeTrack(folderId: Long, bvid: String, cid: Long)
    suspend fun toggleDefaultTrack(track: Track): Boolean
    suspend fun toggleTrackInFolder(folderId: Long, track: Track): Boolean
    suspend fun addBvFavorite(snapshot: BvFavoriteSnapshot)
    suspend fun removeBvFavorite(bvid: String)
    suspend fun refreshBvFavorite(snapshot: BvFavoriteSnapshot)
    suspend fun hasAnyFavorite(): Boolean
}

@Singleton
class DefaultFavoritesRepository @Inject constructor(
    private val db: TingBiliDatabase,
    private val dao: FavoriteDao,
) : FavoritesRepository {
    // 实现见下方规则
}

data class AddTracksResult(val added: Int, val skipped: Int)
```

> 做成 interface + 默认实现，是为了让 `DetailViewModel` / `PlayerViewModel` / 三个 Favorites ViewModel 的单测可以直接用 fake repository，不必每个测试都起 Room。Hilt 侧新增 `FavoritesModule`，用 `@Binds` 把 `DefaultFavoritesRepository` 绑到 `FavoritesRepository`。

规则：

- `ensureDefaultFolder()`：`INSERT OR IGNORE`，`id = 1`，名字「我的最爱」。
- `createFolder()`：先 ensure default，再 `sortOrder = max + 1`。
- `deleteFolder()`：默认夹返回 false；其余删除并级联。
- `reorderFolders()`：事务内按传入顺序写 `sortOrder`，跳过默认夹。
- `addTracksToFolders()`：事务内逐夹写入：
  - 默认夹：`base = (minOrder ?: 0) - tracks.size`，`order = base + index`（新收在前）。
  - 手动夹：`base = maxOrder + 1`，`order = base + index`（追加末尾）。
  - `INSERT OR IGNORE`，统计 added / skipped。
- `toggleDefaultTrack()`：先 ensure default；存在则删，不存在则用默认夹规则插入；返回现在是否在。
- `toggleTrackInFolder()`：默认夹转调 `toggleDefaultTrack()`；手动夹存在则删，不存在则末尾插入。
- `addBvFavorite(snapshot)`：事务写入 `BvFavoriteEntity` + 全量 `BvFavoritePageEntity`，`lastSyncedAt = now`；若已存在则走 refresh 语义，**保留原 `addedAt`**，避免重复点击把收藏时间刷新。
- `refreshBvFavorite(snapshot)`：事务内确认 BV 收藏仍存在；`@Upsert` 更新元信息（避免级联删 pages）；删除旧 pages；写入新 pages；更新 `lastSyncedAt`，**保留 `addedAt`**。
- `removeBvFavorite()`：删除实体，级联删 pages。

---

## 6. 模块与文件清单

### 6.1 新增模块

```text
feature/favorites/
core/bili-ui/
```

`settings.gradle.kts`：

```kotlin
include(":feature:favorites", ":core:bili-ui")
// 删除 ":feature:playlist"
```

`gradle/libs.versions.toml`：

```toml
[versions]
reorderable = "3.1.0"

[libraries]
reorderable = { module = "sh.calvin.reorderable:reorderable", version.ref = "reorderable" }
```

`app/build.gradle.kts`：

- 删除 `:feature:playlist`
- 新增 `:feature:favorites`
- 新增/传递 `:core:bili-ui`

### 6.2 `core:data`

| 文件 | 动作 |
|---|---|
| `db/FavoriteEntities.kt` | 新增：四个实体 + `FavoriteFolderSummary` |
| `model/FavoriteSnapshot.kt` | 新增：`BvFavoriteSnapshot` / `BvFavoritePageSnapshot` / `AddTracksResult` |
| `db/FavoriteDao.kt` | 新增 |
| `repository/FavoritesRepository.kt` | 新增：接口 + `DefaultFavoritesRepository` |
| `di/FavoritesModule.kt` | 新增：`@Binds` 绑定实现 |
| `db/TingBiliDatabase.kt` | 修改：entities、version 12、删除所有旧 `MIGRATION_*`、`favoriteDao()` |
| `di/DatabaseModule.kt` | 修改：提供 `FavoriteDao`、删除 `addMigrations(...)`、改用 `fallbackToDestructiveMigration(true)` |
| `db/PlaylistDao.kt` | 删除 |
| `model/Playlist.kt` | 删除 |
| `src/test/.../PlaylistDaoTest.kt` | 删除，替换为 `FavoriteDaoTest` |
| `src/test/.../FavoritesRepositoryTest.kt` | 新增 |

### 6.3 `data:bilibili`

| 文件 | 动作 |
|---|---|
| `dto/BiliSpaceDto.kt` | 修改：`FavFolderItem.attr`、`BiliFavFolder.attr/isDefault` |
| `dto/ViewDto.kt` | 新增 `ViewData.toFavoriteSnapshot()`，把 `ViewData` 映射成 core:data 的中立模型 |
| `BiliApi.kt` | 新增 `favFoldersAll(upMid)` → `/x/v3/fav/folder/created/list-all` |
| `BiliRepository.kt` | 新增 `getAllFavFolders(uid)`；替换旧的 `getFavFolders` 分页调用；可选：view 错误码映射 |
| `src/test/.../BiliSpaceDtoTest.kt` | 修改：attr/默认夹映射；补 `toFavoriteSnapshot` 用例 |

**测试影响（必须同步改，否则编译失败）：**

`BiliApi` 新增方法后，所有实现 `BiliApi` 的测试 fake 都要补 `favFoldersAll`：

| 文件 | 说明 |
|---|---|
| `app/src/test/.../cache/AppCacheManagerTest.kt` | `FakeBiliApi` |
| `feature/home/src/test/.../HomeViewModelTest.kt` | `FakeBiliApi` + 3 个匿名 `object : BiliApi` |
| `data/bilibili/src/test/.../SubtitleCacheTest.kt` | 1 个匿名 `object : BiliApi` |
| `feature/history/src/test/.../HistoryViewModelTest.kt` | `FakeBiliApi` |
| `feature/player/src/test/.../PlayerViewModelTest.kt` | `SlowSubtitleApi` + 1 个匿名 `object : BiliApi` |
| `feature/mine/src/test/.../StorageSettingsViewModelTest.kt` | `FakeBiliApi` |

`feature/playlist/src/test/.../PlaylistViewModelTest.kt` 随模块删除，不需要改。

**DTO 细节：**

- `FavFolderItem.attr: Int? = null`；`BiliFavFolder` 增加 `attr: Int?` + `isDefault: Boolean`。
- `isDefault = attr?.let { (it and 2) == 0 } ?: (title == "默认收藏夹")`。
- `BiliFavFolder.cover` 在 `created/list` / `list-all` 里通常是空串，UI 必须用占位图标；不要假设有封面。
- `toFolders()` 当前返回 `Pair<List<BiliFavFolder>, Boolean>`；`list-all` 没有 `has_more`，可保留该返回结构（`hasMore = false`），或新增 `toAllFolders()`，实现时二选一，避免两套映射漂移。

### 6.4 `core:ui`

| 文件 | 动作 |
|---|---|
| `component/BackNavIcon.kt` | 新增（从 `feature:mine` 下沉） |
| `component/FavoriteFolderPicker.kt` | 新增：`FavoriteFolderOption`、多选/单选弹层、P 多选列表 |
| `component/CreateFolderDialog.kt` | 新增 |

### 6.5 `core:bili-ui`（新增）

| 文件 | 内容 |
|---|---|
| `build.gradle.kts` | Compose + `core:ui` + `data:bilibili` + Coil |
| `BiliAccountUi.kt` | `BiliNoAuthGate`、`BiliErrorBox`、`BiliVideoRow`、`BiliVideoList`、`formatSpaceDuration` |

### 6.6 `feature:favorites`（新增）

| 文件 | 说明 |
|---|---|
| `build.gradle.kts` | 依赖 `core:ui`、`core:data`、`data:bilibili`、`core:media`、`core:bili-ui`、`libs.reorderable` |
| `FavoritesScreen.kt` | 收藏 Tab |
| `FavoritesViewModel.kt` | 收藏 Tab 状态与操作 |
| `FavoriteFolderScreen.kt` | 收藏夹详情 |
| `FavoriteFolderViewModel.kt` | 收藏夹详情状态与操作 |
| `BvFavoriteScreen.kt` | BV 收藏缓存页 |
| `BvFavoriteViewModel.kt` | 缓存页状态与刷新 |
| `BiliFavoritesScreen.kt` | B站收藏总页 |
| `BiliFavoritesViewModel.kt` | B站收藏夹加载/拆分 |
| `BiliFavDetailScreen.kt` | 从 `feature:mine` 迁入 |
| `BiliFavDetailViewModel.kt` | 从 `feature:mine` 迁入 |
| `BiliToviewScreen.kt` | 从 `feature:mine` 迁入，标题改「稍后再看」 |
| `BiliToviewViewModel.kt` | 从 `feature:mine` 迁入 |
| `components/FavoriteCards.kt` | 我的最爱/手动夹/BV/B站卡片 |
| `components/FolderSortList.kt` | 拖拽排序模式 |
| `src/test/...` | `FavoritesViewModelTest`、`FavoriteFolderViewModelTest`、`BvFavoriteViewModelTest` |

### 6.7 `feature:mine`

| 文件 | 动作 |
|---|---|
| `BiliFavScreens.kt` | 删除（收藏列表/详情迁出） |
| `BiliHistoryToviewScreens.kt` | 只保留 B站历史；toview 部分迁出 |
| `BiliSpaceUi.kt` | 删除/迁移；`BackNavIcon` 去 `core:ui`，其余去 `core:bili-ui` |
| `MeViewModel.kt` | `playlistCount` 改用 `FavoritesRepository.observeFolders()` |
| `MeScreen.kt` | 文案「稍后听」→「稍后再看」 |
| `LoginScreen.kt` | 文案「稍后听」→「稍后再看」 |
| `build.gradle.kts` | 增加 `core:bili-ui` |

### 6.8 `feature:detail`

| 文件 | 动作 |
|---|---|
| `DetailViewModel.kt` | 注入 `FavoritesRepository`；BV 收藏；收藏夹列表；批量加 P；不再用 `PlaylistDao` |
| `DetailScreen.kt` | 收藏按钮单击/长按；P+夹弹层；提示文案 |
| `build.gradle.kts` | 若新增 `DetailViewModelTest`，补 `junit` / `coroutines-test` / `arch-core-testing` / `robolectric` / `androidx.test.core` 测试依赖；当前模块没有这些依赖 |

### 6.9 `feature:player`

| 文件 | 动作 |
|---|---|
| `PlayerViewModel.kt` | 注入 `FavoritesRepository`；爱心状态；收藏夹选项；切换/新建 |
| `PlayerScreen.kt` | 顶栏爱心；长按弹层；snackbar 反馈 |
| `src/test/.../PlayerViewModelTest.kt` | 更新构造与新增用例 |

### 6.10 `app`

| 文件 | 动作 |
|---|---|
| `navigation/Routes.kt` | 新增/重命名路由 |
| `navigation/AppNavHost.kt` | 注册新页面；删除未使用的 `PlaylistScreen` import |
| `navigation/MainTabsPager.kt` | page 1 用 `FavoritesScreen`，点击回调改新路由 |
| `navigation/MainTabsScaffold.kt` | **无需改**：只按 `pagerState.currentPage` 判断，不引用 `PlaylistRoute`；仅以后要改图标/文案时再动 |
| `navigation/BottomNavWithCenterPlayer.kt` | **无需改**：同上 |
| `navigation/MainViewModel.kt` | 冷启动改用 `FavoritesRepository.hasAnyFavorite()` + `FavoritesRoute` |
| `MainActivity.kt` | **无需改**：只调 `mainTabPageOf(startTab)`，路由替换后自动生效 |
| `navigation/ImageFormatScope.kt` | **无需改**：注释已含“收藏” |
| `src/test/.../NavigationTest.kt` | 新路由用例 |
| `build.gradle.kts` | 模块依赖更新 |

> 新全屏页沿用现有写法，统一包 `ImageFormatScope(mainViewModel)`：`FavoriteFolderScreen`、`BvFavoriteScreen`、`BiliFavoritesScreen`、`BiliFavDetailScreen`、`BiliToviewScreen`。收藏 Tab 已在 `MainTabsPager` 的 page 1 `ImageFormatScope` 内。

---

## 7. 逐页实现

### 7.1 `FavoritesScreen` + `FavoritesViewModel`

状态：

```kotlin
data class FavoritesUiState(
    val folders: List<FavoriteFolderSummary> = emptyList(),
    val bvFavorites: List<BvFavoriteEntity> = emptyList(),
    val loggedIn: Boolean = false,
    val loaded: Boolean = false,
    val sortMode: Boolean = false,
    val message: String? = null,
)
```

- `init` 调 `ensureDefaultFolder()`。
- 拆分为 `defaultFolder` / `manualFolders`。
- 顶栏 actions：
  - 普通模式：`新建收藏夹`、`排序收藏夹`（少于 2 个手动夹时禁用）。
  - 排序模式：`完成`。
- LazyColumn：
  - 我的最爱卡片
  - B站收藏卡片
  - `收藏夹` 区块标题 + 手动夹卡片 + 空态
  - `BV 收藏` 区块标题 + BV 卡片 + 空态
  - 底部 `MiniPlayerClearance`
- 新建收藏夹：`CreateFolderDialog` → `createFolder(name)`。
- 排序模式：
  - 只显示手动收藏夹，使用独立的 `LazyColumn`，不混在普通页的分区列表里。
  - 使用 `sh.calvin.reorderable:reorderable:3.1.0`（Calvin-LL/Reorderable，Apache-2.0）：
    - `rememberReorderableLazyListState(lazyListState) { from, to -> ... }`
    - `ReorderableItem(state, key) { isDragging -> ... }`
    - 右侧独立 6 点拖拽手柄 + `Modifier.draggableHandle()`（手柄上直接拖，不等长按；不抢列表滚动）
    - 触感用 Compose `LocalHapticFeedback` + `HapticFeedbackType.LongPress / TextHandleMove`（3.1.0 库本身没有 haptic API，README 的 haptic 示例对应更高版本）
    - 拖动中 item 抬升/阴影；库内建边缘自动滚动和 `animateItem`
  - 松手（`onDragStopped`）才写 `reorderFolders(ids)`；拖动过程只改本地顺序。
  - 无障碍：排序 item 加 `CustomAccessibilityAction("上移"/"下移")`；卡片右上角 overflow 也提供「上移/下移」作为非拖拽替代（WCAG 2.5.7）。
  - `BackHandler` 退出排序模式。
- BV 卡片长按：菜单/确认框「取消收藏」。
- B站卡片：未登录显示登录文案；点击进 `BiliFavoritesRoute`。
- 反馈：snackbar。

### 7.2 `FavoriteFolderScreen` + `FavoriteFolderViewModel`

- `savedStateHandle["folderId"]`。
- 观察 `folder`、`tracks`、`progress`、`focus`、`playingTrack`。
- 复用现有 `PlaylistDetailViewModel` 的播放/续播/焦点逻辑。
- 默认夹：
  - 无重命名/删除菜单。
  - 空态文案不同。
- 手动夹：
  - 重命名对话框。
  - 删除确认。
- 单集移出确认。
- 播放队列名 = 收藏夹名。
- 不再做老数据 meta 补全。

### 7.3 `BvFavoriteScreen` + `BvFavoriteViewModel`

状态：

```kotlin
sealed interface BvRefreshState {
    data object Idle : BvRefreshState
    data object Refreshing : BvRefreshState
    data object Success : BvRefreshState
    data class Failed(val reason: String, val invalid: Boolean) : BvRefreshState
}
```

- `init` / `LaunchedEffect(bvid)` 调 `refresh()`。
- `refresh()`：
  - `_refreshState = Refreshing`
  - `repo.getViewDetail(bvid, forceRefresh = true)`
  - 成功：`favoritesRepository.refreshBvFavorite(video.toFavoriteSnapshot())`
  - 失败：映射原因
    - `throwable is IOException` / `UnknownHostException` / `SocketTimeoutException` → 网络异常
    - 接口 message 命中「不可见 / 不存在 / 已删除 / 啥都木有」→ invalid
    - 其他 → 原 message
- 页面：
  - 缓存优先展示。
  - 顶部细进度条（Refreshing）。
  - 失败告警条：原因 + 上次同步时间 + 重试。
  - 无缓存且失败：整页错误 + 重试 + 取消收藏。
  - 头部、播放、加入收藏夹、P 列表。
  - 右上角菜单：刷新、取消收藏（确认）。
- 取消收藏后 popBackStack。
- 观察 `favorite == null`：显示「已取消收藏」并可返回。

### 7.4 `BiliFavoritesScreen` + `BiliFavoritesViewModel`

- `AuthRepository.sessionFlow`；未登录 → `BiliNoAuthGate`。
- `getAllFavFolders(uid)`。
- 拆 `defaultFolder` / `createdFolders`。
- 页面：
  - 稍后再看入口行 → `BiliToviewRoute`
  - 默认收藏夹 → `BiliFavDetailRoute`
  - 我创建的收藏夹 → `BiliFavDetailRoute`
- 无分页、无 loadMore。
- 加载/错误/空态。
- 只读。

### 7.5 `DetailScreen` + `DetailViewModel`

- 注入 `FavoritesRepository`。
- `isBvFavorited` 改为 `observeIsBvFavorited(bvid)`。
- `toggleBvFavorite()`：
  - 已收藏 → `removeBvFavorite`
  - 未收藏 → `addBvFavorite(video.toFavoriteSnapshot())`
  - snackbar
- 长按按钮：
  - 标准 Material 交互：单击 = 收藏/取消整 BV，长按 = 选 P 收藏到夹；不引入双击、滑动等额外手势。
  - 用自定义 `Surface` + `combinedClickable`（外观沿用现有 `OutlinedButton` 样式；不能直接用 `OutlinedButton` 的 `onClick`，否则长按被按钮消费）。
  - `onClick` / `onLongClick` 明确分开；`onLongClickLabel = "选择分P和收藏夹"`；长按触发 `LocalHapticFeedback`。
  - 触控目标 ≥ 48dp；TalkBack 能读出“已收藏BV，长按选择分P”。
  - `onLongClick` 打开 `FavoriteFolderMultiPickerSheet`。
- 弹层：
  - 多 P：`TrackMultiSelectList`，默认全不选，全选/全不选。
  - 单 P：自动选中。
  - 收藏夹：我的最爱 + 手动夹，多选。
  - 新建收藏夹：新建 + 立即收选中 P + 关闭。
  - 确认：`addTracksToFolders`，snackbar 汇总 added/skipped。
- 提示文案：`长按收藏可挑选分P加入收藏夹`。

### 7.6 `PlayerScreen` + `PlayerViewModel`

- 注入 `FavoritesRepository`。
- `isDefaultFavorite: StateFlow<Boolean>`：
  - `trackFlow.flatMapLatest { track -> repo.observeTrackInFolder(DEFAULT_ID, bvid, cid) }`
- `folderOptions: StateFlow<List<FavoriteFolderOption>>`：
  - 手动夹 + 我的最爱；带当前 P 是否已在。
- `toggleDefaultFavorite()`：
  - `repo.toggleDefaultTrack(track)`
  - snackbar 已收藏/已移出
- `toggleTrackInFolder(folderId)`：
  - `repo.toggleTrackInFolder`
  - snackbar
- `createFolderAndAdd(name)`：
  - `createFolder` + 加入当前 P
- UI：
  - 顶栏右侧、`⋮` 左边加 48dp 爱心；用 `Box` + `combinedClickable`（`IconButton` 只支持单击，不适合这里）。
  - 标准交互：单击切换「我的最爱」，长按开 `FavoriteFolderSinglePickerSheet`；`onLongClickLabel = "选择收藏夹"`；长按给触感反馈。
  - 爱心 `contentDescription` 随状态变化（`收藏到我的最爱` / `已收藏到我的最爱`）；TalkBack 可识别长按动作。
  - 弹层单选、点一下切换并关闭。
  - 无当前曲目隐藏爱心。
  - 标题左右 padding 从 64dp 调到 96/104dp。
  - 复用现有 snackbar host。

### 7.7 `MeScreen` / `LoginScreen` / `BiliToviewScreen`

- 文案统一「稍后再看」。
- Me 四宫格保留。
- B站收藏入口 → `BiliFavoritesRoute`。
- 稍后再看入口 → `BiliToviewRoute`。
- B站历史入口不变。

---

## 8. 共享组件

### 8.1 `FavoriteFolderOption`

```kotlin
data class FavoriteFolderOption(
    val id: Long,
    val name: String,
    val isDefault: Boolean,
    val checked: Boolean = false,
    val supportingText: String? = null,
)
```

### 8.2 `FavoriteFolderMultiPickerSheet`

- 输入：`tracks`、`folders`、`onDismiss`、`onCreateFolder`、`onConfirm`。
- 内部状态：选中的 P keys、选中的 folder ids。
- P 选择区（多 P 时显示）：
  - 全选 / 全不选
  - 行：`P{pageIndex} · {title}` + 时长 + Checkbox
- 收藏夹区：
  - 我的最爱 + 手动夹，Checkbox
  - `+ 新建收藏夹`
- 底部：取消 / 收藏（显示 N 个 P → M 个夹）。
- 新建收藏夹：直接创建并立即收取选中的 P，然后关闭弹层。

### 8.3 `FavoriteFolderSinglePickerSheet`

- 输入：`track`、`folders`、`onToggle(folderId)`、`onCreateFolder`。
- 行：文件夹名 + `默认` 标记 + 当前 P 是否已在。
- 点击：切换并关闭。
- `+ 新建收藏夹`：创建 + 收当前 P + 关闭。

### 8.4 `CreateFolderDialog`

- 名称输入、非空校验、确定/取消。
- 收藏 Tab 用空创建；弹层内用「创建并收取」。

### 8.5 `TrackMultiSelectList`

- 输入 `List<Track>`、选中集合、切换回调。
- 行显示 P 号、标题、时长。
- 高度上限 + LazyColumn。

### 8.6 收藏夹拖拽排序（采用成熟实现）

查过官方文档和开源实现后的结论：

- **官方 Compose 没有内建 `LazyColumn` 重排组件**。`Modifier.dragAndDropSource` / `dragAndDropTarget` 是数据传输（跨组件/跨应用拖放），不是列表重排。
- 官方推荐/常见模式是：专用拖拽手柄 + `LazyListState` + `animateItem` + 边缘自动滚动 + 触感反馈。
- Material 3 / WCAG 2.5.7 要求拖拽之外提供单指针替代，所以排序 item 必须有「上移/下移」或等价操作。
- 采用 **Calvin-LL/Reorderable 3.1.0**（Apache-2.0），它已经实现：
  - `rememberReorderableLazyListState`
  - `ReorderableItem`
  - `Modifier.draggableHandle()` / `longPressDraggableHandle()`
  - 边缘自动滚动、`animateItem`、不同高度 item、section header/footer
  - 注意：3.1.0 没有 `rememberReorderHapticFeedback`；触感用 Compose `LocalHapticFeedback` 自行补
- 依赖变更：
  - `gradle/libs.versions.toml` 增加 `reorderable = "3.1.0"` 和 library 条目
  - `feature:favorites/build.gradle.kts` 增加 `implementation(libs.reorderable)`
- 交互定稿：
  - 排序模式独立 `LazyColumn`。
  - 手柄在卡片右侧，`draggableHandle()` 立即拖动（手柄是显式拖拽区，避免和整卡点击冲突）。
  - 拖动开始/移动/结束给触感反馈。
  - 松手才落库；拖动中只改内存顺序。
  - 卡片 overflow 提供「上移/下移」；TalkBack 用 `CustomAccessibilityAction`。
- 参考：
  - Android Developers — [Drag composables](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-composables)（明确区分 drag 数据传递与列表重排）
  - Material 3 — [Lists / accessibility](https://m3.material.io/components/lists/accessibility)（拖拽需单指针替代）
  - [Calvin-LL/Reorderable](https://github.com/Calvin-LL/Reorderable) README 与 `SimpleReorderableLazyColumnScreen` 示例（Apache-2.0）
- 发布合规：Apache-2.0，发布时在第三方许可/关于页保留 LICENSE/NOTICE。

---

## 9. 数据库升级：全库清空

已确认：Room 可以完全清空，本次不保留任何本地数据，因此**不写 `MIGRATION_11_12`，也不做 schema 对齐**。

`TingBiliDatabase`：

- `version = 12`
- entities 改为四张新表
- 删除 `companion object` 里所有旧 `MIGRATION_*`
- 新增 `favoriteDao()`，移除 `playlistDao()`

`DatabaseModule`：

```kotlin
Room.databaseBuilder(ctx, TingBiliDatabase::class.java, "tingbili.db")
    // 不要再接 fallbackToDestructiveMigrationOnDowngrade(true)：
    // 它会把 requireMigration 重新设为 true，导致升级 fallback 失效。
    .fallbackToDestructiveMigration(true)
    .build()
```

- 升级时 Room 直接 drop 所有表并重建新 schema；不存在手写 SQL / schema 校验崩溃。
- **实测坑**：`fallbackToDestructiveMigrationOnDowngrade(true)` 会重置 `requireMigration = true`，所以前面调的 `fallbackToDestructiveMigration(true)` 会失效；两者不要叠加，前者本身已允许 downgrade 清库。
- 会清空：本地收藏、B站历史、首页快照、播放队列、字幕/playurl 缓存、播放历史。
- 新装用户直接创建 v12 schema。
- 默认夹由 `FavoritesRepository.ensureDefaultFolder()` 首次访问时创建，不依赖迁移插入。
- `fallbackToDestructiveMigration(true)` 是 Room 2.7+ 推荐的重载（no-arg 版本已废弃）。
- 注意：`fallbackToDestructiveMigrationFrom(true, 11)` 不能和现有 `MIGRATION_10_11` 混用（11 是它的 endVersion，Room 会抛异常）；既然全库清空，直接删除所有 migrations + 全量 fallback。
- 仓库约定“禁止破坏性迁移”本次按用户明确要求例外处理；后续版本若要保留数据，再恢复显式 Migration。
- 升级烟测仍要做：旧 APK → 新 APK，确认启动不崩、收藏 Tab 能自动建默认夹。

---

## 10. 测试计划

### 10.1 单元测试

| 测试 | 覆盖 |
|---|---|
| `FavoriteDaoTest` | 默认夹唯一、重复 P 忽略、跨夹允许、默认夹顺序、手动夹排序、级联删除、成员查询、BV 收藏/缓存级联、`hasAnyFavorite` |
| `FavoritesRepositoryTest` | ensureDefault、create/rename/delete/reorder、addTracks added/skipped、默认夹新收在前、手动夹追加末尾、toggle 语义、refresh 缓存 |
| `FavoritesViewModelTest` | 三区块拆分、新建、排序、登录态、空态 |
| `FavoriteFolderViewModelTest` | 默认夹禁止改名/删除、播放、移出、焦点 |
| `BvFavoriteViewModelTest` | 缓存优先、每次刷新、forceRefresh、失败原因映射、失效告警、取消收藏 |
| `PlayerViewModelTest` | 爱心状态、单击切换我的最爱、长按夹切换、新建夹、无曲目隐藏 |
| `BiliSpaceDtoTest` | `attr & 2 == 0` 默认夹、attr 缺失兜底、`list-all` 解析 |
| `NavigationTest` | 新路由解析 |
| `HomeViewModelTest` | 删除死代码后的构造与用例 |

### 10.1.1 现有测试的连带修改

| 改动 | 影响 |
|---|---|
| `BiliApi` 新增 `favFoldersAll` | 6 个测试文件里的 10 个 `BiliApi` 实现要补方法（见 6.3） |
| `HomeViewModel` 移除 `playlistDao` | `HomeViewModelTest` 删除 `FakePlaylistDao`，10 处构造调用改参数，删除 `addToPlaylist single and all` 用例 |
| `PlayerViewModel` 新增 `FavoritesRepository` | `PlayerViewModelTest` 7 处构造调用改参数；用 fake repository |
| `DetailViewModel` 新增 `FavoritesRepository` | 若加 `DetailViewModelTest`，先给 `feature:detail` 补测试依赖 |
| 模块删除 / 路由重命名 | `NavigationTest`、`MainTabsPager`、`AppNavHost` 同步 |

### 10.2 构建 / 测试命令

```bash
./gradlew assembleDebug --no-daemon --stacktrace
# CI 门禁：编译所有模块的测试源码，能拦住 BiliApi fake / ViewModel 构造没同步的问题
./gradlew compileDebugUnitTestKotlin --no-daemon --stacktrace
./gradlew :core:data:testDebugUnitTest \
  :feature:favorites:testDebugUnitTest \
  :feature:player:testDebugUnitTest \
  :feature:detail:testDebugUnitTest \
  :app:testDebugUnitTest --no-daemon --stacktrace
```

### 10.3 真机验证

- 旧 APK 安装 → 覆盖安装新 APK，确认全库清空后启动不崩、收藏 Tab 自动创建「我的最爱」。
- 收藏 Tab：
  - 我的最爱 → B站 → 手动夹 → BV 收藏 顺序。
  - 新建收藏夹、拖拽排序、完成。
  - 各空态。
- 收藏夹详情：
  - 播放、续播、移出、手动夹改名/删除。
  - 我的最爱无改名/删除。
- BV 详情：
  - 单击收藏BV、取消。
  - 长按选 P、全选、选多个夹、新建夹。
- 播放页：
  - 爱心单击、长按、切夹、新建夹、无曲目隐藏。
- BV 收藏缓存页：
  - 在线进入刷新成功。
  - 断网进入：缓存展示 + 网络异常提示 + 重试。
  - 失效 BV：缓存展示 + 失效提示 + 取消收藏。
  - 每次进入都有刷新反馈。
- B站收藏页：
  - 未登录引导。
  - 稍后再看入口。
  - 默认收藏夹、我创建的收藏夹。
  - 只读。
- 用 `uiautomator dump` 核对文本/结构，不用截图做常规验证。

---

## 11. 实施阶段

> 关键顺序约束：旧 `PlaylistDao` 一旦从 `TingBiliDatabase` 移除，`HomeViewModel` / `MainViewModel` / `DetailViewModel` / `feature:playlist` 就会立刻编译失败。因此**数据层收口 + 新模块空壳 + 旧引用迁移必须在同一阶段完成**，不能拆成“先删 DAO、后补 UI”。

### 阶段 1：数据层 + 编译收口

- 四张表、`FavoriteDao`、`FavoritesRepository` 接口 + `DefaultFavoritesRepository`、`FavoritesModule`。
- `TingBiliDatabase` v12 + **全库清空**（`fallbackToDestructiveMigration(true)`）；删除所有旧 `MIGRATION_*`，从 entities 移除旧 `PlaylistEntity` / `PlaylistTrackEntity`。
- `DatabaseModule` 提供 `FavoriteDao`、删除 `addMigrations(...)`、移除 `PlaylistDao`。
- 删除旧 `PlaylistDao` / `Playlist` 模型 / `PlaylistDaoTest`。
- `data:bilibili` 增加 `ViewData.toFavoriteSnapshot()`（阶段 1 就要用）。
- 新建 `feature:favorites`、`core:bili-ui` 两个模块；先放可编译的空壳 Screen。
- `settings.gradle.kts` / `app/build.gradle.kts` 注册新模块；移除 `:feature:playlist`。
- 路由：`FavoritesRoute` / `FavoriteFolderRoute` / `BvFavoriteRoute` / `BiliFavoritesRoute`；`MainTabsPager` 与 `AppNavHost` 指向空壳。
- `MainViewModel` 改用 `FavoritesRepository.hasAnyFavorite()` + `FavoritesRoute`。
- `HomeViewModel` 删除 `playlistDao` 与 `addToPlaylist` / `addBvToPlaylist` / `ensureDefaultPlaylist` 死代码。
- `DetailViewModel` 改为 `FavoritesRepository`：先实现 BV 收藏的单击/取消，长按弹层留到阶段 5。
- 删除 `feature:playlist`；`BiliFavScreens.kt` / `BiliSpaceUi.kt` 的共享组件迁入 `core:bili-ui` / `core:ui`；`BiliHistoryToviewScreens.kt` 只保留历史。
- 更新 `HomeViewModelTest`（10 处构造、删 `FakePlaylistDao` / `addToPlaylist` 用例）、`NavigationTest`、旧 `PlaylistViewModelTest` 删除。
- 验收：
  - `./gradlew assembleDebug --no-daemon --stacktrace` 通过。
  - `:core:data:testDebugUnitTest` 绿；`FavoriteDaoTest` / `FavoritesRepositoryTest` 绿。
  - 旧 APK 覆盖安装新 APK 不崩。
  - 收藏 Tab 是空壳，但导航可达。

### 阶段 2：收藏 Tab + 收藏夹详情 + 排序

- `FavoritesScreen` / `FavoritesViewModel`。
- `FavoriteFolderScreen` / `FavoriteFolderViewModel`。
- 卡片、空态、新建、改名、删除、我的最爱限制。
- 拖拽排序（独立 LazyColumn + Calvin-LL/Reorderable，见 8.6）。
- 验收：本地三类收藏完整可用。

### 阶段 3：BV 收藏缓存页

- `BvFavoriteScreen` / `BvFavoriteViewModel`。
- 镜像读写、每次进入强制刷新、失败原因映射、告警条、取消收藏。
- 验收：在线/断网/失效三种状态。

### 阶段 4：B站收藏总页

- `FavFolderItem.attr` / `BiliFavFolder.isDefault` / `favFoldersAll` / `getAllFavFolders`。
- `BiliFavoritesScreen` / `BiliFavoritesViewModel`。
- 迁移 `BiliFavDetailScreen` / `BiliFavDetailViewModel` / `BiliToviewScreen` / `BiliToviewViewModel`。
- 补齐 6 个测试文件的 10 个 `BiliApi` fake。
- 验收：登录/未登录、默认夹、稍后再看、只读。

### 阶段 5：BV 详情页

- `DetailScreen` 长按弹层；`DetailViewModel` 补收藏夹列表、批量加 P、新建夹。
- 多 P 选择 + 多夹选择 + 只加不删。
- 验收：收藏/取消/加 P 全链路。

### 阶段 6：播放页

- `PlayerViewModel` 注入 `FavoritesRepository`；爱心状态、收藏夹选项、切换/新建。
- `PlayerScreen` 顶栏爱心 + 长按弹层。
- 更新 `PlayerViewModelTest` 7 处构造。
- 验收：单击/长按/新建夹/无曲目隐藏。

### 阶段 7：收尾 + 全量验证

- `MeScreen` / `LoginScreen` / `BiliToviewScreen` 文案统一「稍后再看」。
- 删除死代码、更新注释。
- 更新 `docs/DEVELOPMENT.md` 模块清单：`feature:playlist` → `feature:favorites` + `core:bili-ui`，`core:data` 描述补收藏。
- 全量构建 + 相关单测 + `compileDebugUnitTestKotlin`。
- 真机逐屏验证（含旧 APK 升级）。
- `git status` 干净。

---

## 12. 风险与对策

| 风险 | 对策 |
|---|---|
| Room 升级（全库清空） | `fallbackToDestructiveMigration(true)` + 删除所有旧 `Migration`；无 schema 对齐风险，但仍做旧 APK 升级烟测 |
| 详情页按钮长按被 Button 消费 | 用自定义 `Surface` + `combinedClickable`（标准做法），不要依赖 `OutlinedButton` |
| 拖拽排序在 LazyColumn 中不稳 | 用 Calvin-LL/Reorderable 的成熟实现；独立排序 LazyColumn；松手才落库；补上移/下移无障碍替代 |
| 模块搬迁 import 大量报错 | 先建空模块 + 路由骨架，再逐文件搬；每步编译 |
| `getViewDetail` 内存 memo 导致“刷新”没请求 | BV 收藏页固定 `forceRefresh = true` |
| B站 `attr` 语义变动 | `attr & 2 == 0` + 标题兜底；DTO 测试覆盖 |
| `PlayerViewModel` / `HomeViewModel` 构造变化导致测试崩 | `PlayerViewModelTest` 7 处、`HomeViewModelTest` 10 处构造调用；先改 fake/构造再跑测试 |
| `BiliApi` 新增方法导致所有测试 fake 编译失败 | 一次性补齐 6 个测试文件的 10 个实现，见 6.3 |
| `feature:detail` 没有测试依赖 | 加 `DetailViewModelTest` 前先补 build.gradle 测试依赖 |
| 弹层状态复杂（P/夹/新建） | 共享 `core:ui` 组件；新建夹 = 创建并立即收取并关闭 |
| 冷启动查询变慢 | `hasAnyFavorite()` 用两条 EXISTS/COUNT，不 join 全表 |

---

## 13. 不在本期范围

- B站写操作（收藏/取消收藏 B站视频、管理 B站收藏夹）。
- B站“我收藏的收藏夹”（collected list）。
- 收藏夹内 P 的拖动排序。
- 自定义收藏夹封面。
- 离线音频下载。
- 多账号。

---

## 14. 验收清单

- [ ] 收藏 Tab 顺序正确：我的最爱 → B站收藏 → 手动收藏夹 → BV 收藏
- [ ] 我的最爱固定第一，不可改名、不可删除
- [ ] 手动收藏夹可新建、改名、删除、拖拽排序
- [ ] 默认夹新 P 在最前；手动夹新 P 在末尾
- [ ] 同夹同 P 去重；跨夹允许
- [ ] 收藏夹封面取首条有封面的 P
- [ ] BV 详情单击收藏BV 可进/出
- [ ] BV 详情长按可选 P、多夹、新建夹
- [ ] 播放页爱心单击切换我的最爱
- [ ] 播放页长按可切我的最爱/手动夹
- [ ] BV 收藏页每次进入强制刷新
- [ ] 刷新失败展示缓存 + 原因 + 重试
- [ ] 失效 BV 仍可看缓存并取消收藏
- [ ] B站页稍后再看/默认收藏夹/我创建的收藏夹
- [ ] B站页只读、未登录引导
- [ ] 旧 APK 升级不崩；全库清空后收藏 Tab 能自动创建「我的最爱」
- [ ] 相关单测 + `assembleDebug` 绿
- [ ] `git status` 干净

---

## 15. 备注

- 本计划中涉及的旧 `Playlist` 代码全部删除，不做兼容层。
- 所有新增中文文案以本文档为准。
- 实现时如发现 Room schema / 库行为与本文档不一致，先更新本文档再改代码，避免设计与实现漂移。

---

## 16. 代码对照审查结论（2026-10-31）

已按当前仓库逐项对照，发现并已修正以下问题：

| 发现 | 处理 |
|---|---|
| `MainTabsScaffold` / `BottomNavWithCenterPlayer` 只按 `pagerState.currentPage` 判断，不引用 `PlaylistRoute` | 文档改为“无需改”；路由替换不影响它们 |
| `MainActivity` 只调 `mainTabPageOf(startTab)` | 文档改为“无需改” |
| `ImageFormatScope` 注释已含“收藏” | 文档改为“无需改” |
| `BiliApi` 新增 `favFoldersAll` 会破坏 6 个测试文件的 10 个实现 | 6.3 增加完整影响清单；10.1.1 列为必改项 |
| `HomeViewModelTest` 有 10 处 `HomeViewModel(... playlistDao ...)` 构造、1 个 `FakePlaylistDao`、1 个 `addToPlaylist` 用例 | 10.1.1 明确为连带修改 |
| `PlayerViewModelTest` 有 7 处 `PlayerViewModel(...)` 构造 | 10.1.1 明确；建议用 fake repository |
| `feature:detail` 当前没有测试依赖 | 6.8 增加 build.gradle 测试依赖项 |
| 旧 migration 测试 | `HomeSnapshotTest` / `SubtitleDiskCacheTest` 里的 `MIGRATION_6_7` / `MIGRATION_7_8` 用例随迁移删除，保留 DAO roundtrip |
| `HomeViewModelTest` 快照用例 | 改为先等 `keyword` 初始化完成，避免 `StateFlow.first()` 直接读到 null 的竞态 |
| `FavoritesRepository` 若是具体类，ViewModel 单测要么起 Room、要么写复杂 fake | 改为 `interface` + `DefaultFavoritesRepository` + `FavoritesModule`，单测用 fake |
| 排序模式若混在普通分区列表里，拖拽索引会错位 | 7.1 改为“排序模式使用独立 LazyColumn” |
| 官方 Compose 没有内建 LazyColumn 重排；自己写 `detectDragGesturesAfterLongPress` 容易在滚动、自动滚屏、无障碍上出问题 | 采用 Calvin-LL/Reorderable 3.1.0（Apache-2.0），见 8.6；补上移/下移无障碍替代 |
| `BiliFavFolder.cover` 在 `created/list` / `list-all` 里通常为空 | 6.3 明确 UI 用占位图标，不假设有封面 |
| `BiliSpaceDto.toFolders()` 当前返回 `Pair<List<BiliFavFolder>, Boolean>` | 6.3 明确 `list-all` 可复用该结构（`hasMore=false`）或新增映射，二选一 |
| `BiliFavScreens.kt` 同时包含列表页和详情页 | 6.7 明确“删除列表页部分 + 详情页迁出”，不是整文件丢弃 |
| `BiliHistoryToviewScreens.kt` 同时包含历史和稍后再看 | 6.7 明确拆分：历史留 mine，toview 迁 favorites |
| `AppNavHost` 有未使用的 `PlaylistScreen` import | 6.10 明确删除 |
| `HistoryEntity` 一个 BV 只留一条，`cid` 是最近播放分 P | **2026-11-01 已升级**为按播单（BV/收藏夹）记录，见 `docs/HISTORY.md`；旧数据迁移为 BV 记录 |
| `FavoritesRepository` 若直接收 `ViewData`，会让 `core:data` 反向依赖 `data:bilibili`，形成循环 | 增加 core:data 的 `BvFavoriteSnapshot` 中立模型；`ViewData.toFavoriteSnapshot()` 映射放 data:bilibili |
| `BvFavoriteEntity` 若用 `@Insert(REPLACE)`，SQLite 会先删后插，触发 `BvFavoritePageEntity` 的 `ON DELETE CASCADE`，把缓存 P 全清掉 | DAO 改为 `@Upsert`，更新实体不触发级联删除 |
| Repository 的 toggle 需要“是否已在某夹”的 suspend 判断；createFolder 需要最大 sortOrder | DAO 补 `isTrackInFolder(...)` 和 `maxFolderSortOrder()`，避免把 Flow 当同步查询用 |
| `fallbackToDestructiveMigrationFrom(true, 11)` 和现有 `MIGRATION_10_11` 的 endVersion=11 冲突，Room 会抛异常 | 全库清空：删除所有旧 `MIGRATION_*`，改用 `fallbackToDestructiveMigration(true)` |
| **真机实测**：`fallbackToDestructiveMigration(true)` 后再调 `fallbackToDestructiveMigrationOnDowngrade(true)`，后者会把 `requireMigration` 重置为 true，导致 11→12 升级仍抛 “migration required but not found” | `DatabaseModule` 只保留 `fallbackToDestructiveMigration(true)`；该重载本身已设置 `allowDestructiveMigrationOnDowngrade = true` |
| 原计划手写 `MIGRATION_11_12` 仍有 schema 对齐风险 | 用户确认可全库清空，改为 Room 全量 destructive fallback；只保留“旧 APK → 新 APK 启动不崩”烟测 |

### 审查后仍建议保留的决策

- `core:bili-ui` 只为 B站账号相关共享组件单独建模块；不要把这些组件塞进 `core:ui` 以免 `core:ui` 反向依赖 `data:bilibili`。
- `feature:favorites` 同时承载本地收藏与 B站收藏页入口，符合“收藏 Tab 一个入口”的产品结构。
- `FavoritesRepository` 接口化只用于测试解耦，不引入额外领域层。
- 迁移明确全库清空；不要为了“看起来干净”再加兼容层。全库清空是本次明确的产品决策，后续版本不要默认沿用，除非再次确认。
- 排序不再自己造轮子：用成熟库 + 专用手柄 + 上移/下移替代，符合 Material/WCAG 的通用做法。

---

## 17. 阶段 1 实施记录（2026-10-31）

### 已完成

- `core:data`：四张表、`FavoriteDao`、`FavoritesRepository` + `DefaultFavoritesRepository`、`FavoritesModule`。
- `TingBiliDatabase` version 12；删除所有旧 `MIGRATION_*`；`DatabaseModule` 使用 `fallbackToDestructiveMigration(true)`。
- 删除 `PlaylistDao` / `Playlist` 模型 / `PlaylistDaoTest` / `feature:playlist`。
- `data:bilibili`：`ViewData.toFavoriteSnapshot()`。
- 新建 `core:bili-ui`，迁入 B 站共享 UI；`BackNavIcon` 下沉 `core:ui`。
- 新建 `feature:favorites` 空壳（收藏 Tab / 收藏夹详情 / BV 缓存页）。
- 路由：`FavoritesRoute`、`FavoriteFolderRoute`、`BvFavoriteRoute`；`MainViewModel` 冷启动改用 `hasAnyFavorite()`。
- `DetailViewModel` 的 BV 收藏改为 `BvFavoriteSnapshot`；`MeViewModel.playlistCount` 改用 `FavoritesRepository`。
- `HomeViewModel` 删除 `playlistDao` 与 `addToPlaylist` 死代码；`HomeViewModelTest` 同步。
- 真机覆盖安装旧库：DB 从 11 升到 12，`Favorite*` 表存在，`Playlist*` 表消失，启动不崩。
- 收藏 Tab 空壳可达，显示「收藏系统建设中」。

### 验证结果

- `./gradlew assembleDebug --no-daemon --stacktrace`：成功。
- `:core:data:testDebugUnitTest`：成功。
- `./gradlew compileDebugUnitTestKotlin --no-daemon --stacktrace`：成功。
- `:feature:home:testDebugUnitTest` / `:app:testDebugUnitTest`：成功。
- 真机 11→12 升级烟测：通过。

### 真机踩坑

- `fallbackToDestructiveMigration(true)` 后再接 `fallbackToDestructiveMigrationOnDowngrade(true)`，后者会把 `requireMigration` 重置为 true，升级仍抛 “migration required but not found”。已改为只保留 `fallbackToDestructiveMigration(true)`。

### 偏差 / 延后

- B 站收藏页仍在 `feature:mine`，`BiliFavFoldersRoute` 暂未改名；阶段 4 迁移。
- BV 缓存页目前是空壳；阶段 3 填充。
- 排序已在阶段 2 落地。
- P 选择弹层、爱心、B 站长页等未开始，按阶段 4~6 推进。

### 阶段 2：收藏 Tab + 收藏夹详情 + 排序（2026-10-31）

已完成：

- `FavoritesViewModel`：默认夹 ensure、收藏夹 summaries、BV favorites、登录态、消息、排序模式。
- `FavoritesScreen`：我的最爱 / B站入口 / 收藏夹 / BV 收藏四段；空态；右上角新建/排序；BV 卡片长按取消。
- `FavoriteFolderViewModel` / `FavoriteFolderScreen`：封面、全部/继续播放、集列表、进度焦点、单集移除、手动夹重命名/删除、我的最爱限制。
- `FavoriteCards.kt`、`FavoriteSortList.kt`、`core:ui/CreateFolderDialog.kt`。
- 排序：Calvin-LL/Reorderable 拖拽 + 上移/下移 + TalkBack 自定义动作；松手落库。
- `CreateFolderDialog` 确认/取消时主动 hide 键盘，避免键盘盖住底部导航。
- `MainActivity`：pagerState 在 `startTab` 判空前创建，并用 `rememberSaveable` 的一次性标记应用首 Tab；修复弹窗/IME 后回到首页的问题。

真机验证：

- 收藏 Tab 显示默认「我的最爱」+ B站入口 + 收藏夹/BV 两段空态。
- 新建 A/B、上移/下移、拖拽排序，DB `sortOrder` 正确落库。
- 最新构建下新建收藏夹后停留在收藏页；键盘会收起。
- 收藏夹详情：全部播放、空态、重命名、删除。
- BV 卡片显示标题 / UP / P 数 / 收藏日期；长按取消收藏并清缓存。
- 测试数据已清理。

验证：

- `assembleDebug`、`:core:data:testDebugUnitTest`、`compileDebugUnitTestKotlin` 成功。
- UI VM 单测（`FavoritesViewModelTest` / `FavoriteFolderViewModelTest`）暂未加，留阶段 8 统一补。
- 3.1.0 库没有 haptic API，触感改用 Compose `LocalHapticFeedback`。

### 阶段 3：BV 收藏缓存页（2026-10-31）

已完成：

- `BvFavoritePageEntity.toTrack(parent)`：缓存 P 映射成可播放 `Track`。
- `FavoriteTrackRow` 从 `FavoriteFolderScreen` 抽到模块内共享，`onDelete` 改为可空（BV 缓存页不提供单 P 删除）。
- `FavoriteFolderMultiPickerSheet` / `FavoriteFolderOption` 落在 `core:ui`：多 P 时默认全不选 + 全选/全不选；单 P 自动选中；收藏夹多选；弹层内新建夹 = 创建并立即收取选中 P。
- `BvFavoriteViewModel`：观察收藏/缓存/收藏夹/历史/播放状态；`refresh()` 固定 `forceRefresh = true`；成功覆盖镜像，失败按 `IOException` / 失效关键词映射原因；播放、加入收藏夹、新建夹、取消收藏。
- `BvFavoriteScreen`：头部（封面、标题、UP、P 数、收藏时间、上次同步）、刷新细进度条、失败告警条（含上次同步 + 重试）、全部/继续播放、加入收藏夹、P 列表进度焦点、右上角刷新/取消收藏。
- 无缓存且刷新失败：整页错误 + 重试 + 返回。
- 刷新错误映射抽成 `internal fun Result.Error.toBvRefreshFailure()`，补 `BvRefreshFailureTest`（失效、IO、空消息、服务端原因）。

真机验证：

- 在线进入：头部信息完整，67 P 全部展示，`上次同步` 时间更新。
- 加入收藏夹：P1/P2 + 「我的最爱」确认后，DB `FavoriteTrackEntity` 写入 2 条（folderId=1）。
- 断网进入（系统代理指向黑洞端口）：顶部显示「刷新失败：网络异常，请检查网络连接」+ 上次同步时间 + 重试，缓存 67 P 仍可浏览。
- 取消收藏：确认框文案正确，返回收藏 Tab；`BvFavoriteEntity` / `BvFavoritePageEntity` 级联清空。
- 取消 BV 收藏后，手动收藏夹里已加入的 P 保留（BV 引用与手动收藏相互独立）。
- 测试数据已清理，设备只剩默认「我的最爱」。

验证：

- `:feature:favorites:compileDebugKotlin`、`assembleDebug` 成功。
- `:feature:favorites:testDebugUnitTest --tests BvRefreshFailureTest`、`:core:data:testDebugUnitTest` 成功。

踩坑 / 说明：

- 飞行模式不会切断已连接的 Wi‑Fi，无法制造断网；`adb` 本身走无线调试，禁用 Wi‑Fi 会掉线。改用 `settings put global http_proxy 127.0.0.1:9`，且需杀进程重启后新进程才会读到代理。
- 失效 BV 未造真机数据，错误映射由单测覆盖。

### 阶段 4：B站收藏总页（2026-10-31）

已完成：

- `FavFolderItem.attr` + `BiliFavFolder.isDefault`：`attr & 2 == 0` 判默认夹，`attr` 缺失时按标题「默认收藏夹」兜底；`toAllFolders()` 一次映射，`toFolders()` 复用。
- `BiliApi.favFoldersAll`（`/x/v3/fav/folder/created/list-all`）替换旧的 `favFolders` 分页接口；`BiliRepository.getAllFavFolders(uid)` 替换 `getFavFolders`。
- `BiliFavoritesScreen` / `BiliFavoritesViewModel` 移入 `feature:favorites`：稍后再看入口行、默认收藏夹（识别到才显示）、我创建的收藏夹、加载/错误/空态、下拉刷新按钮。
- `BiliFavDetailScreen` / `BiliFavDetailViewModel`、`BiliToviewScreen` / `BiliToviewViewModel` 从 `feature:mine` 迁入 `feature:favorites`；`BiliHistoryScreen` 留在 `feature:mine` 并独立成文件。
- 路由 `BiliFavFoldersRoute` → `BiliFavoritesRoute`；收藏 Tab 的 B站入口和 Me 页入口统一走新路由。
- 同步 6 个测试文件里的 10 个 `BiliApi` fake（`favFolders` → `favFoldersAll`）；`NavigationTest` 改用新路由。
- `BiliSpaceDtoTest` 补默认夹判定：`attr=0`、`attr=2`、缺省标题兜底。

真机验证：

- B站收藏页：稍后再看入口、默认收藏夹（带「默认」标记、64 个视频）、我创建的收藏夹（开盒/哈基米/滑伪/听书/看看/音乐）正常展示。
- 默认收藏夹 → 详情列表加载正常；点视频进入 BV 详情正常。
- 我创建的收藏夹 → 详情列表正常。
- 稍后再看入口 → 列表正常，标题已改为「稍后再看」。
- 只读：页面无任何编辑/删除入口。
- 未登录态沿用 `BiliNoAuthGate`，本次未清登录态验证，避免影响设备现有账号。

验证：

- `assembleDebug`、`:data:bilibili:testDebugUnitTest`、`:feature:favorites:testDebugUnitTest`、`compileDebugUnitTestKotlin` 成功。

### 阶段 5：BV 详情页长按收藏（2026-10-31）

已完成：

- `DetailViewModel`：观察收藏夹列表、`ensureDefaultFolder()`、`addTracksToFolders()`、`createFolderAndAdd()`；复用 `AddTracksResult` 生成 added/skipped 提示。
- `DetailScreen` 收藏按钮改为 `Surface + combinedClickable`：
  - 单击：`收藏BV` / `已收藏BV` 整本进/出。
  - 长按：触感反馈 + 打开 `FavoriteFolderMultiPickerSheet`；`onLongClickLabel = "选择分P和收藏夹"`。
  - 按钮下增加提示「长按收藏可挑选分P加入收藏夹」。
- 弹层复用 `core:ui` 多选组件：多 P 默认全不选、全选/全不选；单 P 自动选中并隐藏 P 选择区；收藏夹多选；弹层内新建夹 = 创建并立即收取选中 P。
- 只加不删，重复自动跳过。

真机验证：

- 2P 视频：按钮显示「收藏BV」，单击后变「已收藏BV」，DB 写入 1 个 BV + 2 个缓存 P。
- 长按：弹层显示 P1/P2 默认未选；选 P2 + 我的最爱，DB 写入 `folderId=1` 1 条。
- 弹层内「+ 新建收藏夹」：输入 `Stage5Test` 创建，P1 同时写入新夹；DB 收藏夹与成员关系正确。
- 1P 视频：长按弹层不显示 P 选择区，自动选中唯一 P；选 `Stage5Test` 加入成功。
- 重复加入同一个 P：DB 行数不变（去重跳过）。
- 测试数据已清理，设备只剩默认「我的最爱」，BV 收藏为 0。

验证：

- `assembleDebug`、`compileDebugUnitTestKotlin` 成功。

### 阶段 6：播放页爱心（2026-10-31）

已完成：

- `core:ui` 新增 `FavoriteFolderSinglePickerSheet`：单选、点行切换并关闭、含「我的最爱」、弹层内新建夹。
- `PlayerViewModel` 注入 `FavoritesRepository`：
  - `favoriteFolderIds` 随当前 P 自动重订阅。
  - `isDefaultFavorite` 只表示当前 P 在「我的最爱」。
  - `favoriteFolders` 合并收藏夹列表与当前 P 成员关系。
  - 单击 `toggleDefaultFavorite()`；长按弹层 `toggleFavoriteFolder()`；`createFavoriteFolderAndAdd()`。
- `PlayerTopBar` 在 `⋮` 左侧加爱心：
  - 单击切换我的最爱，长按打开单选收藏夹弹层。
  - 用 `combinedClickable`，`onClickLabel` / `onLongClickLabel` 完整，长按触感反馈。
  - 无当前曲目时隐藏；爱心实心仅表示我的最爱。
  - 标题左右留白从 64dp 调到 96dp，避免压住动作区。
- `PlayerViewModelTest` 增加 `FakeFavoritesRepository`，7 处构造同步。

真机验证：

- 播放页顶栏显示空心爱心与「播放设置」。
- 单击爱心：加入我的最爱（DB 写入当前 P），图标变实心。
- 再次单击：移出我的最爱（DB 0 条）。
- 长按：弹层显示「我的最爱」+ 手动夹，当前 P 所在夹为选中态。
- 点「我的最爱」行：加入并关闭弹层。
- 再次长按点同一行：移出并关闭。
- 弹层内新建 `PlayerTest`：创建并立即收藏当前 P；DB 收藏夹与成员关系正确。
- 点手动夹行：移出成功。
- 测试数据已清理，仅剩默认「我的最爱」。

验证：

- `assembleDebug`、`:feature:player:testDebugUnitTest`、`:feature:favorites:testDebugUnitTest`、`compileDebugUnitTestKotlin` 成功。

### 阶段 7：收尾 + 全量验证（2026-10-31）

已完成：

- 文案统一「稍后再看」：`MeScreen` 四宫格、`LoginScreen` 副标题、`Routes` 注释。
- 死代码清理：
  - `MeScreen` 移除未使用的 `playlistCount` 订阅。
  - `MeViewModel` 移除 `FavoritesRepository` 注入与 `playlistCount`。
  - 底部导航 `isPlaylistSelected` 更名 `isFavoritesSelected`。
- `docs/DEVELOPMENT.md` 模块清单更新：
  - 架构行改为 `feature:home/favorites/history/player/mine/detail/space` + `core:bili-ui`。
  - `feature:playlist` → `feature:favorites`。
  - `core:data` 描述改为收藏/历史。
  - `feature:mine` 去掉已迁出的 B站收藏/稍后再看。

真机验证：

- 「我的」页四宫格显示「B站收藏 / B站历史 / 稍后再看 / 本地缓存」（已登录的 B站三项不再显示「去查看」副文案）。
- 登录页文案改动已写入代码（设备保持登录态，未登出验证）。

验证：

- `assembleDebug`、`compileDebugUnitTestKotlin` 成功。
- 相关单测：`:core:data`、`:data:bilibili`、`:feature:favorites`、`:feature:player`、`:feature:mine`、`:feature:home`、`:app` 全部通过。
- 真机测试数据已清理：只剩默认「我的最爱」，0 个手动夹、0 个 BV 收藏。

> 所有改动当前仍未提交；`LoginScreen.kt` / `LoginScreenTest.kt` 含用户此前的登录页改版，提交时需确认是否一并纳入。

### 补充：收藏夹 P 行展示 BV 标题与时长（2026-10-31）

`FavoriteTrackEntity` 本就带了 `videoTitle` / `pageIndex` / `durationMs`（详情页、BV 缓存、播放页三处入口都会写入），只是列表没展示。

`FavoriteTrackRow` 改为三栏布局，长标题不再挤掉关键信息：

```
[01]  1-20                                  2:08:31
      P1 · 无限流悬疑多人有声《请活到天明》连载最新
```

- 中栏：主标题（最多两行）+ 小字 `P{序号} · BV 标题`（单行省略，序号在最前，先保证可见）。
- 右栏：总时长固定右对齐（`formatDurationMs`），长标题只会截断中栏小字，时长永远可见。
- `videoTitle` 为空或与分 P 同名（单 P）时不显示标题；单 P 的 `P1` 也一并隐藏。
- 新增 `showVideoTitle` 参数：BV 收藏页顶部已有 BV 标题，行内传 `false`，只显示 `P1` + 时长。
- 进度行（`正在播放 4:01` / `上次听到 4:01`）改为只显示当前位置，避免与右侧总时长、小字 `P{n}` 重复。

真机验证：收藏夹「胖宝宝」18 集均为 `01 | 1-20 | P1 · 无限流… · 2:08:31` 布局，时长右对齐到删除按钮左侧；BV 收藏页分 P 行为 `P1` + 时长。验证用 BV 收藏已删除。

### 补充：播放列表跟随收藏夹 + 行样式对齐（2026-10-31）

原来的播放队列只是「起播瞬间的快照」：从收藏夹播出去以后，夹内再增删 P / 改名 / 排序，
播放页的章节列表不会变，跟收藏列表对不上。

- **队列记住来源**：`PlayerManager.play(..., sourceFolderId)` 把来源夹 id 写进
  `PlaybackState.sourceFolderId` 与 `QueueManager` 快照（Room v13 加列
  `PlayQueueSnapshotEntity.sourceFolderId`，手写 `MIGRATION_12_13`，不清库）。
- **实时对齐**：`MainViewModel` 订阅 `player.state.sourceFolderId`，用
  `flatMapLatest` 跟 `observeTracks(folderId)` + `observeFolder(folderId)`；
  夹内变化交给 `PlayerManager.syncFolderQueue`：
  - 增删 / 改名：复用 `refreshQueue` 的增量 splice，正在播的 P 不断；
  - 拖拽排序 / 清空：按最终顺序重建 transport 播放列表并回到原进度（会重新缓冲一次，
    但下一首/自动连播顺序正确）；
  - 在播的 P 被移出收藏夹时原位保留，播完再消失；
  - 只有「当前队列来源就是这个夹」才生效，搜索/历史/BV 分 P 等来源不受影响。
- **来源随切歌保留**：`next()` / `previous()` / `PlayerViewModel.playAt()` 都会带上
  `sourceTitle` + `sourceFolderId`（之前 next/prev 会丢掉来源名）。
- **播放页章节重做**：底部「章节」弹层改为「播放列表（N）」+「来自「收藏夹名」」；
  行样式与 `FavoriteTrackRow` 同一套层级：
  `序号 | 主标题 | P{n} · BV 标题 | 时长`，正在播的一行高亮并标「正在播放」。
  小字逻辑抽到 `core:ui.trackMetaLabel / trackPageLabel`，收藏夹与播放列表共用，
  不再依赖 `pageCount`（收藏夹 Track 的 pageCount 常是默认 1）。
- **mini 条同步**：副标题从裸分 P 名改为 `P17 · 295-308（1023）`，与页面行一致。

真机验证（Redmi K70E）：

- v12→v13 升级后收藏数据完整（我的最爱 1 集、胖宝宝 18 集仍在）；
- 从胖宝宝播放 P1，播放列表显示 `01 | 1-20 | P1 · 无限流… | 2:08:31`、`正在播放`；
- 在夹内删除 P19（另一 BV）后，仍播放 P1 不断，播放列表 18 → 17 实时更新；
- mini 副标题为 `P17 · 295-308（1023）`；
- 验证用的临时收藏夹已删除，误删的 P19 已恢复。

### 补充：收藏页统一列表（2026-10-31）

手动收藏夹与 BV 收藏不再分区，合成一个列表：

- 「我的最爱」固定第一、「B站收藏」入口保留在第二。
- 其余手动夹 + BV 收藏按**最近添加时间**倒序混排（收藏夹用 `createdAt`，BV 用 `addedAt`）。
- 每张卡片在副标题行前置一个小 tag：`收藏夹`（`primaryContainer`）/ `BV`（`tertiaryContainer`），
  与集数/作者等信息同一行左对齐，一眼区分类型又不会打断标题排版；
  原来的「收藏夹 / BV 收藏」两个分区标题和空态提示已移除。
- 因为顺序由添加时间决定，**移除「排序收藏夹」拖拽入口**（`FavoriteSortList.kt` 与
  `reorderable` 依赖一并删除）。`FavoriteFolderEntity.sortOrder` 与
  `FavoritesRepository.reorderFolders` 保留：收藏夹选择器仍按它排序，后续要做
  「置顶」类功能可直接复用。
- 真机验证：BV 卡片（较新）排在自建夹「胖宝宝」之前，两张卡片分别显示 `BV` / `收藏夹` tag，
  标题、集数、时长、上次听到均正常。

### 补充：来源收藏夹被清空时的播放语义（2026-10-31）

先查了平台规范：Media3 官方对「删除正在播放的 MediaItem」的定义是——播放器自动播放第一个剩余后继；
没有后继则进入 `ended`，不会继续播放已删除的条目。

按这个语义落地：

- **整个收藏夹被清空/删除**：先 `flush` 一次历史（重新收藏后还能续播当前位置），然后暂停、
  清空 transport 播放列表与 `QueueManager` 快照，UI 回到「未播放」。不再按旧顺序继续播。
- **只删掉在播的这一集、夹里还有别的**：不打断当前声音，播完再按剩余收藏夹顺序继续
  （纯音频章节动辄 1~2 小时，删夹内其它 P 时突然切断体验更差；这是唯一保留的偏离）。
- 不来自收藏夹的队列（搜索/历史/BV 分 P）不受任何收藏夹增删影响。

真机验证：把「我的最爱」清空后重启，底部 mini 播放器消失、`PlayQueueSnapshotEntity` 为空，
没有继续播放已删除的 P。
