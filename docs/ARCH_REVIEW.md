# TingBili 架构评审（整体结构，非单文件性能）

> 方法：模块依赖图（各 `build.gradle.kts`）+ Hilt 组件归属 + 跨层 import + 数据流向，对标标杆：UAMP（Google Media3 官方播放样板）、AntennaPod（音频优先播客，队列/历史/缓存与本项目最像）、Now in Android（Google 官方模块化样板）、NewPipe（第三方客户端网络/签名/缓存）、Auxio/RetroMusic（本地音乐主题/歌单）。
> 结论先行：单文件审计全是“术”，这里 6 条是“道”——前 3 条不改，后面性能优化会反复回潮。

## 0. 依赖图实测（本仓库现状）

```
app → core:ui, core:media, core:data, data:bilibili, feature:{home,playlist,history,player,mine,detail,search}
core:media → core:data + data:bilibili        ← 倒置（core 依赖 data）
core:ui → core:data (api)
data:bilibili → core:data
feature:* → core:ui + core:data + core:media + data:bilibili   ← 每个 feature 直连网络层
```

- `feature` 之间无互引，好，守住了“feature 互不依赖”。
- Hilt 全挤在 `:app` 的 `AppDataModule`（DB/DataStore/OkHttp/Retrofit/BiliApi/Wbi/Buvid 全在这），`core:data`、`data:bilibili` 零 Hilt Module，注释自述“放 app 避免 ksp/hilt churn”。代价：改一行网络配置全工程重编，feature 无法独立测试。
- `MainViewModel` 住在 `navigation/BottomNavWithCenterPlayer.kt`（UI 文件里塞 VM），管 theme/imageFormat/startTab/回顶/播放，神对象前兆。
- `core/media` 直 import `data.bilibili.BiliRepository/biliImage/WbiSigner`，`ServicePlayerModule`（ServiceScoped）import `WbiSigner`。core 不再是平台抽象，换个音源（本地/播客）要改 core。

## 1. 分层倒置：core → data（必改，P0 架构）

现状：`PlayerManager`（core）在调 `BiliRepository.getPlayUrl/getView`（B 站专有），`toMediaItem` 调 `cover.biliImage`（B 站 CDN 专有），`ServicePlayerModule` 读 `WbiSigner.BROWSER_UA/REFERER`。

标杆：
- Now in Android：`core:model + core:data（Repository 接口）← data:*（实现）`，core 永不 import data。feature 只认 `core:data` 接口。
- UAMP：`PlaybackService` 只认 `MusicSource（接口）`，B 站/本地/播客都是实现。
- AntennaPod：`PlaybackController` 与 `Feed/Extractor` 解耦，换源不碰播放器。

改法（大，分三步）：
1. `core:media` 新建 `PlayUrlResolver { suspend fun resolve(bvid,cid): String? }` + `ArtworkUrlResolver { fun artwork(cover: String): Uri? }` 接口，`PlayerManager.toMediaItem` 只认接口。
2. `data:bilibili` 提供 `BiliPlayUrlResolver @Inject` 实现，`AppDataModule`（或新建 `data:bilibili/di`）绑定。
3. `ServicePlayerModule` 的 UA/Referer 改由 `@Provides @Named("mediaHeaders") Map` 从 app 层传入，不再 import `WbiSigner`。`core/media/build.gradle` 删 `implementation(project(":data:bilibili"))`。

收益：core 可单测（Fake Resolver），加本地音频/播客源零改播放器；这也是后面“队列落盘”重构的前提。

## 2. God Module：AppDataModule 聚合一切（必改，P0 架构）

现状：DB（7 个 Migration）+ DataStore + OkHttp + Retrofit + Json + Wbi + Buvid + AuthStore 全在 `:app/di`。`core:data`、`data:bilibili` 无 DI，自测 feature 要拉起整个 app 图。

标杆：Now in Android 拆 `core:database/di + core:datastore/di + core:network/di`，各模块自给自足，`:app` 只做 `ApplicationComponent` 聚合。

改法（中，分模块还家）：
- `core:data/di`: `DatabaseModule（DB+Migrations+Dao）` + `PreferencesModule（DataStore+Repository）`。
- `data:bilibili/di`: `NetworkModule（OkHttp+Retrofit+BiliApi+WbiInterceptor）` + `SessionModule（AuthStore/Buvid）`。
- `:app` 只留 `CacheModule（聚合三缓存）` 或新建 `core:cache` 模块。注释里“避免 ksp churn”用 `api` 依赖 + 预编译解决，别用聚合换构建速度。

## 3. 播放双真相源：PlayerManager.queue vs ExoPlayer timeline（最危险）

现状：`PlayerManager{ queue: List<Track>, currentIndex }` + `transport(ExoPlayer).timeline` 双份队列，`refreshQueue()` 300 行 mirror 对齐（add/remove/replace 逐项，不断播）。`play()` 乐观 update UI → `resolveCid → resolveUrls → setMediaItems`，中间任何失败 UI 与 transport 不一致。`next()/previous()` fire-and-forget `launch{play}`，快速连点与 `playMutex.tryLock` 丢请求叠加，队裂只差一次时序。

标杆：
- UAMP：队列唯一真相 = `MediaSession.timeline`，UI 全读 `MediaController`，`PlayerManager` 只发指令不存队列。
- AntennaPod：队列落 `Queue DB 表`，播放器只渲染，崩溃/杀进程恢复零丢失；本项目 `queue` 纯内存 + `prefs.lastQueue` 快照，进程死即丢（注释自述“内存态，进程死即失效”）。

改法（大，分期）：
- 短期：`queue/currentIndex` 只读 ExoPlayer（`currentMediaItemIndex + mediaItemCount`），`PlayerManager` 删自有 queue，`refreshQueue` 改 `replaceMediaItem` 批量事务，一次 `notify`。
- 长期：新建 `playQueue` Room 表（`order/trackJson`），`play()` 先写表再 `setMediaItems`，冷启动/崩溃从表恢复而非 DataStore 快照。睡眠定时/断点续播同表事务。

## 4. 缓存四国杀：DataStore 存 URL、Room 存大 JSON（中）

现状四块：`SimpleCache audio 500MB LRU（AudioCacheModule，好）` + `Coil 默认（未调）` + `Subtitle LRU20内存+Room磁盘` + `HomeSnapshot 单行 tracksJson 60条` + `DataStore playUrlCache Map`。

错位：
- `playUrlCache: Map<String,CachedPlayUrl>` 住 DataStore（prefs 文件），每次 `first()` 全量读 + `putPlayUrls` 全量写，百条 URL 即百 KB protobuf 重写。DataStore 是偏好，不是缓存。迁 Room `play_url(id,url,fetchedAt)` + TTL 索引。
- `HomeSnapshotEntity.tracksJson` 单行存 60 条 Track JSON，反序列化主线程（P1-H5）。AntennaPod/NewPipe 首页缓存都是分页表 + `updatedAt` 索引。拆 `home_item(keyword,order,trackJson)` 或 Proto DataStore 分条。
- `SubtitleDiskEntity.linesJson` 万行字幕 TEXT，Room 读全表。改文件缓存（`cache/subtitle/{bvid}_{cid}.json`）+ Room 只存索引，`AppCacheManager.stats` 不再 `toByteArray` 逐行。
- `AppCacheManager` 住 `:app` 跨调 media3/Coil/Room，新建 `core:cache` 模块收编，对标 `core:datastore`。

## 5. 主题/图片全局化（小，但与 P0 联动）

- `LocalImageFormat` CompositionLocal 包全 App（P0-1），`ImageFormat` 本是 Coil 请求参数。改调用点 `imageLoader.enqueue(Request.memoryCacheKey...)` 传参，不进 Composition。
- `TingBiliTheme.brandedColorScheme` 每次重算（P2-I4），对标 Now in Android `core:designsystem/ThemeConfig`：`ThemeMode/DarkMode/ImageFormat` 收敛为 `ThemeConfig` 单 StateFlow，`scheme` 一次 `remember`。
- `MainViewModel` 拆：`ThemeViewModel（主题）+ AppState（导航/startTab）`，参考 Now in Android `NiaAppState`（非 VM），导航态不进 VM。

## 6. 网络/会话（沿用 P2-M3，加一层）

- `WbiInterceptor(cookieProvider={sessionFlow.value+buvid.cookie()+joinToString})` 每请求拼串 + 主线程读 `StateFlow.value`。NewPipe 把签名放独立 `Downloader` 层并缓存。改：拦截器只读内存 `volatile cookieHeader`，后台协程 1min 刷新一次。
- `BuvidProvider.execute()` 首请求阻塞 + `BiliRepository.searchMutex 800ms` 全局节流，搜索/首页共用一把锁，首页 append 会饿死搜索。拆 `searchMutex/homeMutex` + 退避对齐（P1 已记）。

## 落地顺序（架构版 Top5）

1. 拆 `AppDataModule` 还家（2 周内，阻塞单测）。
2. `core/media` 解耦 `data:bilibili`（接口化，第 1 步同期）。
3. 队列真相唯一化（短期删 mirror，长期落 Room）。
4. `playUrl/HomeSnapshot/Subtitle` 迁出 DataStore/大 JSON（与 1 同做）。
5. `ThemeConfig + AppState` 拆 `MainViewModel`（UI 卡顿收尾时做）。

> 本文件是结构评审，单点性能见 `docs/PERF_AUDIT.md`，两份配合看：先按本文件动结构，再按 PERF_AUDIT 逐项消 Top10，否则“历史 1s 写”这类根因会换个马甲回来。

## 落地进度（2026-10-22 晚，master @1c058b7）

对照架构 Top5：

1. ✅ 拆 `AppDataModule`（`93713f0`）：`core:data/di`（Database/Preferences）+ `data:bilibili/di`（Network）新建，app 只留 AuthStore 绑定 + ScrollBus。
2. ✅ `core/media` 解耦（`86e58e0`）：`PlayUrlResolver/CatalogResolver/ArtworkResolver` 接口 + `BiliMediaResolvers` 实现 + `ResolverModule` 绑定 + UA/Referer 走 `@Named`；`core/media` 零 `data.bilibili` 引用，165 单测 + 整包 + 真机播放全绿。
3. ⬜ 队列真相唯一化：未动（风险最高，单独开刀）。
4. ⬜ 缓存搬家：未动（playUrl 大 Map 仍在 DataStore，真机 prefs 已见百 KB 级 playurl 缓存）。
5. 🟡 主题/状态：图片格式按页提供 done（`1c058b7`）；`ThemeConfig` 收敛 + 拆 `MainViewModel` 未动。

明日建议顺序：先 P1-H1 同类小项（N 路 getView→按需/懒加载，HistoryViewModel 首当其冲）+ 分P key + mini/详情图片分档（都是小补丁，热身）；再开队列真相唯一化（大手术，单独验证）。

## 落地进度（2026-10-23 上午）

- 队列真相唯一化（第 3 项）完成第一步（`5b25864`）：消灭 mirror 可变副本与双尾分叉，plan 纯函数化；transport 时间线仍是唯一写入方，queue 字段保留为 UI 元数据（彻底 Room 落盘队列待业务需要时再做）。
- 播放器事件链回填（`47226a8`）：拆订阅的副作用教训——无订阅者的 StateFlow 会停在初值，事件读必须走常热源。

## 落地进度（2026-10-23 中午）

- 缓存搬家（第 4 项）完成 playurl 部分（`3b3b573`）：Room 表 + v9 迁移 + DAO + 启动搬运；DataStore 只剩纯偏好。真机 prefs 已无旧键。

## 落地进度（2026-10-23 中午）

- 网络/会话（第 6 项）收尾：view memo + Cookie 头缓存。`WbiInterceptor` 热路径零拼串。

## 落地进度（2026-10-23 下午）

- 队列真相（第 3 项）完成度：transport 时间线唯一写入者不变；元数据队列落 Room 单行快照（原子 Upsert，消灭 DataStore 三键分写错位）。纯 Room 播放队列表（长期）暂缓：当前快照已满足崩溃恢复。
- ThemeConfig/AppState 拆分（第 5 项）评估结论：暂缓。理由：订阅已全粒度化（4 个独立 StateFlow + `remember(scheme)`），单 ThemeConfig 流省不掉任何一次重组；导航态已收敛到 `isMainTabRoute/isMainTabDestination`。拆分只剩文件美学价值，改 6 处调用方的 churn 不值。
