# TingBili 性能审计报告（已封版，正文审计章节冻结，文末落地进度随做随记）

> 方法：静态代码审查为主，不改业务。只读 `app/src`、`core`、`data`、`feature` 源码 + `gradle/libs.versions.toml` + 构建配置。
> 规范：路由只在 `Routes.kt` 定义；`feature` 互不依赖；UI 用 `collectAsStateWithLifecycle()` 收 `StateFlow`；Room 升级手写 Migration。
> 进度：P0/P1/P2 已封版。结构性结论见 `docs/ARCH_REVIEW.md`，两份无实质冲突，动手顺序见文末“统一动手顺序”。

## 0. 覆盖地图（v2，18 步，122 文件全量核对）

- `app` 13 主文件：`MainActivity.kt`、`TingBiliApp.kt`、`cache/AppCacheManager.kt`、`di/AppDataModule.kt`、`di/CacheModule.kt`、`di/EncryptedAuthStore.kt`、`navigation/AppNavHost.kt`、`navigation/BottomNavWithCenterPlayer.kt`、`navigation/HomeTabClick.kt`、`navigation/MainTabsScaffold.kt`、`navigation/MiniPlayerBar.kt`、`navigation/MiniPlayerVisibility.kt`、`navigation/Routes.kt`
- `core:data` 13 主文件：`Result.kt`、`cache/CacheManager.kt`、`datastore/PreferencesRepository.kt`、`db/HistoryDao.kt`、`db/HomeSnapshot.kt`、`db/PlaylistDao.kt`、`db/SubtitleDiskCache.kt`、`db/TingBiliDatabase.kt`、`model/DarkMode.kt`、`model/ImageFormat.kt`、`model/Playlist.kt`、`model/ThemeMode.kt`、`model/Track.kt`
- `core:media` 9 主文件：`AudioCacheModule.kt`、`MediaControllerHandle.kt`、`MediaModule.kt`、`PlaybackConnection.kt`、`PlaybackState.kt`、`PlayerHandle.kt`、`PlayerManager.kt(823行)`、`ServicePlayerModule.kt`、`TingBiliPlaybackService.kt`
- `core:ui` 8 主文件：`HomeScrollTopBus.kt`、`LazyScrollExt.kt`、`LocalImageFormat.kt`、`TrackFormat.kt`、`component/TingBiliScaffold.kt`、`component/TingBiliTopAppBar.kt`、`theme/Theme.kt`、`theme/Type.kt`
- `data:bilibili` 16 主文件：`BiliApi.kt`、`BiliRepository.kt`、`BuvidProvider.kt`、`SubtitleCache.kt`、`WbiInterceptor.kt`、`WbiSigner.kt`、`auth/AuthRepository.kt`、`auth/AuthStore.kt`、`auth/BiliSession.kt`、`dto/BiliSpaceDto.kt`、`dto/ImageUrl.kt`、`dto/PlayUrlDto.kt`、`dto/SearchDto.kt`、`dto/SubtitleDto.kt`、`dto/ViewDto.kt`、`model/LyricLine.kt`
- `feature:home`：`HomeScreen.kt(509)` + `HomeViewModel.kt(317)` + `HomePagingSource.kt`
- `feature:search`：`SearchScreen.kt(492)` + `SearchViewModel.kt` + `SearchPagingSource.kt`
- `feature:detail`：`DetailScreen.kt(392)` + `DetailViewModel.kt(200)`
- `feature:playlist`：`PlaylistScreen.kt(211)` + `PlaylistDetailScreen.kt(475)` + `PlaylistViewModel.kt(210)` + `PlaylistDetailViewModel.kt(215)`
- `feature:history`：`HistoryScreen.kt(239)` + `HistoryViewModel.kt(109)`
- `feature:player`：`PlayerScreen.kt(1300)` + `PlayerViewModel.kt(310)` + `LyricState.kt` + `PlayerHeader.kt`
- `feature:mine`：`MeScreen.kt(565)` + `SettingsScreen.kt` + `AppearanceScreen.kt(341)` + `OtherSettingsScreen.kt(246)` + `BiliFavScreens.kt(271)` + `BiliHistoryToviewScreens.kt(228)` + `BiliSpaceUi.kt` + `GeetestDialog.kt(190)` + `LoginScreen.kt(397)` + `LoginViewModel.kt(325)` + `AppearanceViewModel.kt` + `MeViewModel.kt(86)` + `SettingsViewModel.kt` + `OtherSettingsViewModel.kt(154)`
- 构建：`gradle/libs.versions.toml` + 各 `build.gradle.kts` + `app/proguard-rules.pro` + `gradle.properties`
- 测试 30+ `*Test.kt` 不逐行审，只借结论。

计划顺序：P0 启动与骨架 → P1 home/search/detail/playlist/history/player/mine → P2 播放/数据/网络/图片/构建。每章格式：`位置 → 现状 → 为什么慢 → 改法（小/中/大）→ 收益`。

---

## P0 启动与骨架（已审，2026-10-22）

### P0-1 `MainActivity.AdaptiveMainScaffold` 顶层订阅太粗

位置：`app/src/main/java/cn/debubu/tingbili/MainActivity.kt:104-188`

现状：
- 顶层同时 `collectAsStateWithLifecycle()`：`themeMode`、`customThemeColor`、`darkMode`、`startTab`、`imageFormat`，外加 `navController.currentBackStackEntryAsState()`、`currentWindowAdaptiveInfo()`。
- `TingBiliTheme(darkTheme, themeMode, customThemeColorArgb)` 包住整个 `Surface → AdaptiveMainScaffold → NavHost`。
- `CompositionLocalProvider(LocalImageFormat provides imageFormat)` 包住整个 `NavHost`。

为什么慢：
1. `customThemeColor: Int` 在 `AppearanceScreen` HSV 拖动时每帧 `update`，全树每帧重组。主题色只应影响 `colorScheme` 计算，不应让播放列表、字幕行重测。
2. `imageFormat` 切换一次，全栈 15 个 destination 重组 + 所有 `AsyncImage` 换 key 重载。应只包图片密集页。
3. `currentBackStackEntryAsState` 在脚手架层，每次 navigate/pop 都让 `Scaffold(bottomBar)` + `AppRootNavHost(modifier.padding)` 重组。`inMainTabs` 只是 Boolean，应下沉或 `derivedStateOf`，且 `AdaptiveMainScaffold` 与 `MainTabsScaffold` 各写一套 4×`hasRoute` 判断，应复用 `isMainTabRoute()`，否则漏一个就底栏闪现。

改法（小）：
- 主题拆两层：外层订阅 `themeMode/darkMode`，内层单设 `CustomColorProvider(customThemeColor)` 只参与 `colorScheme` 计算。
- `LocalImageFormat` 下移到 Home/Search/Detail/Playlist/BiliFav 五入口分别提供。
- `inMainTabs` 用 `remember(entry){derivedStateOf{...}}` 或复用 `isMainTabRoute()`。

收益：设置页拖动不卡全 App；切图片格式不闪全栈。

### P0-2 Mini 播放条：进度态放错层级，全脚手架每秒抖（P0 最高）

位置：`navigation/MiniPlayerBar.kt`、`navigation/MainTabsScaffold.kt:平板分支`、`navigation/BottomNavWithCenterPlayer.kt`

现状：
- 平板分支实锤：`val state by playerManager.state.collectAsStateWithLifecycle()` + `overlayProgress = state.positionMs / state.durationMs`，`PlaybackState` 含 `positionMs/buffered/currentTrack/isPlaying/isLoading`，`PlayerManager.startPositionPoll()` 每 ~1s `update{copy(positionMs)}`，等于 `NavigationSuiteScaffold + Box + MiniPlayerBar` 每秒全重组。手机分支若同写法，底栏 4 Tab 每秒重测，低端机滚列表掉帧。
- 隐藏用 `AnimatedVisibility(expandVertically+fadeIn/shrinkVertically+fadeOut)`，改布局高度，`Scaffold` 内容每帧 remeasure。
- `DismissableBarContent`：`Animatable(0f)` + `onSizeChanged{barHeightPx=...}`（`mutableFloatStateOf`，首次测量必多一次重组）+ `draggable(Vertical)` + `graphicsLayer`；`trackKey="${bvid}:${cid}"` 每组合拼接一次；`LaunchedEffect(trackKey){snapTo(0)}`。
- 封面尺寸待实锤：import 了 `BiliImageVariant/biliImage`，若 mini 条用 480 则 48dp 小条浪费 10 倍内存，应 `SmallSquare(128)/Avatar(96)` + `memoryCacheKey(trackKey)`，详情页再用 `DetailHero(720)`。

改法（中）：
- 脚手架只订阅 `currentTrack/isPlaying`（`map{}.distinctUntilChanged`），`progress` 下沉到条内单独 collect，进度条用 `graphicsLayer` 更新不触父布局。
- 隐藏动画改纯位移/透明（`translationY/alpha`），不改布局高度。
- `trackKey` 用 `remember(track){...}` 缓存；封面三档（mini/列表/详情）强制分尺寸。

收益：滚列表时底栏零重组；进度 tick 只刷一条线。

### P0-3 `onCreate` 首帧三件多余事

位置：`MainActivity.kt:66-102`

1. `playbackConnection.connect()` 主线程调 `MediaController.Builder.buildAsync()`，占主线程消息。延到首帧绘制后 `lifecycleScope.launch{}`。
2. `POST_NOTIFICATIONS` 在 `onCreate` 直接 `launch`，Tiramisu 上弹框盖首帧。等 `startTab` 非空 + 首帧 `onPreDraw` 后再要。
3. `enableEdgeToEdge()` 后又手写 deprecated `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN + FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS + statusBarColor=TRANSPARENT`，两套沉浸式打架多一次 decor 重布局。用 `WindowCompat.setDecorFitsSystemWindows(window,false)` 即可。

改法（小）：三处延后/刪冗余，启动省 1-2 帧。

### P0-4 DI 与全局

- `TingBiliApp` 空实现，好，无启动反射。
- `AppDataModule` 的 `OkHttp/Json/Room` 皆 `@Singleton`，好。但未见 `OkHttp` 磁盘缓存 + 超时调优，412 限流雪崩风险留 P2 网络章。

**P0 Top3：** ①脚手架订阅整 `PlaybackState`（高）②主题色/图片格式全局订阅（中高）③mini 条改布局高度动画（中）。

---

## P1 home（待审）
## P1 search（待审）
## P1 detail（待审）
## P1 playlist（待审）
## P1 history（待审）
## P1 player（待审，1300 行重点）
## P1 mine（待审）
## P2 媒体/数据/网络/图片/构建（待审）

## 附：总 Top10（审计完填，按收益/成本排序）
-（待填）

## P1 home 首页发现流（已审，2026-10-22）

范围：`feature/home/HomeScreen.kt(509)` + `HomeViewModel.kt(317)` + `HomePagingSource.kt` + `core/data/db/HomeSnapshot.kt` + `core/ui/TrackFormat.kt, HomeScrollTopBus.kt, LazyScrollExt.kt`

### P1-H1 双 Grid + 双 State 常驻内存

位置：`HomeScreen.kt:70-110`

现状：
- `rememberLazyGridState()` 建两个：`gridState`（Paging）+ `snapshotGridState`（快照），`showSnapshot = paging.itemCount==0 && !snapshot.isNullOrEmpty()` 二选一显示，另一个藏在后台不销毁。
- `LaunchedEffect(scrollTopTick){ gridState.animateScrollToItem(0); snapshotGridState.animateScrollToItem(0) }` 每次回顶两个一起滚，不管哪个可见。

为什么慢：不可见的那个 Grid 仍持有全部 item 组合槽 + `AsyncImage` 请求，快照 60 条 + Paging 200 条（`maxSize=200`）双份位图内存。回顶时不可见 Grid 也 `animateScrollToItem`，浪费一帧主线程。

改法（小）：只保留一个 `LazyGridState`，快照/Paging 共用；`showSnapshot` 切换时 `scrollToItem(0)` 一次。回顶只滚可见的那个：
```kotlin
if (showSnapshot) snapshotGridState.animateScrollToItem(0) else gridState.animateScrollToItem(0)
```

### P1-H2 顶层 5 个 StateFlow 全量订阅

位置：`HomeScreen.kt:64-80`、`HomeViewModel.kt`

现状：`pagingFlow` + `homeKeywords` + `snapshotTracks` + `pagingStarted` + `scrollTopTick` 全在 `HomeScreen` 顶层 `collectAsStateWithLifecycle()`。其中 `homeKeywords: prefs.homeKeywords.stateIn(Eagerly)` 是 DataStore 全量词表，改一个词全页重组；`HomeSearchEntry(keywords=...)` 里 `keywords.joinToString("/")` 每重组拼接一次，搜索框文案超长（4 词全拼）还加剧 `Text` 测量。

改法（小）：`HomeSearchEntry` 只订阅 `keywords.firstOrNull()` 或缓存 `remember(keywords){joinToString}`；`snapshotTracks` 为 null/empty 时不进 `showSnapshot` 分支，避免 `snapshot!!` 非空断言分支重组。

### P1-H3 Paging key 用 `#$index` 兜底，复用全废

位置：`HomeScreen.kt:TrackPagingGrid key={ "${bvid}:${cid}#$index" }`

现状注释写得很诚实：B 站搜索常返回重复 BV（`cid=0` 未解析），`"bvid:cid"` 会撞车闪退，故缀 `#$index` 兜底。代价：key 里带 index，插入/刷新时所有 item key 全变，`LazyVerticalGrid` 无法复用，只能全量重组 + 图片重载。配合 `HomePagingSource.seenBvids` 跨页去重（内存 `mutableSet`），重复 BV 被过滤掉但 key 仍不稳定。

改法（中）：`key = "${bvid}:${cid}:${pageIndex ?: 0}:$index"` 不如在源头把 `cid=0` 先 `resolveCid()` 或用 `bvid+title.hash` 做稳定 key；去重后的列表保证 `key` 唯一且稳定，`index` 只做最后兜底（`peek==null` 占位）。长远：搜索结果进 Paging 前先按 `bvid` 合并，避免同一 BV 占两格。

### P1-H4 `TrackGridCard` 组合内干重活

位置：`HomeScreen.kt:TrackGridCard`

现状：
- `track.cover.biliImage(ListThumb 480x270, imageFormat)` 每次组合拼接 URL（`normalize→substringBefore('@')→"${w}w_${h}h"`），60 格 × 重组 N 次 = 重复字符串分配。`LocalImageFormat.current` 读一次全卡片重组（P0 已记）。
- `formatDuration(track.durationMs)` / `String.format("%d:%02d")` 在组合内，每帧分配。`playedProgressLabel/chapterLabel` 同理。
- `AsyncImage(model=...)` 未设 `memoryCacheKey/diskCacheKey`，换 `imageFormat`（webp→avif）URL 变了，Coil 认不出同一图，磁盘缓存翻倍；`contentScale=Crop + aspectRatio(16/9) + clip(10dp)` 每卡一次 `graphicsLayer` 裁剪。

改法（小）：`remember(track.cover, imageFormat){ cover.biliImage(...) }` 缓存 URL；时长文案 `remember(durationMs){formatDuration(...)}`；`AsyncImage` 加 `placeholder + memoryCacheKey("${bvid}:${cid}") + size(480x270)` 让 Coil 按视口解码，不加载原图。

### P1-H5 Paging 配置与 412 退避：已做对，留一个小尾巴

好的地方：`PagingConfig(pageSize=20, prefetchDistance=8, maxSize=200, enablePlaceholders=false)` + `searchPage` 节流 `800ms` + `HomePagingSource.RATE_LIMIT_BACKOFF_MS=2000` + `seenBvids` 去重 + 错误态 3s 冷却重试，防 412 风暴意识到位。

小尾巴：
- `onFirstPage` 回调里 `json.decode/encode List<Track>` + `homeSnapshotDao.save()` 在 `load()` 协程里，若是 Main 调度会卡 append。确认 `Pager.flow.cachedIn(viewModelScope)` + `onFirstPage` 切 `Dispatchers.IO` 再落盘。
- `getRefreshKey` 用 `anchorPosition`，快照→Paging 切换时 `refreshKey` 对不上会从第 1 页重取，配合 `pagingStarted=false→true` 开闸逻辑，会闪一下。切换时保留 `gridState`（见 H1）即可顺滑。
- `MAX_SNAPSHOT_TRACKS=60` + `tracksJson` 整表 JSON 存，`HomeViewModel.init` 里 `json.decodeFromString` 在 `viewModelScope.launch`（主线程默认 Main），60 条 Track 反序列化约 5-15ms，主线程可接受，但首帧会抖。切 `Dispatchers.IO` 再 `update`，冷启动少一顿。

**P1-home Top：** ①双 Grid 常驻（中）②key 带 index 复用废（中）③卡片 URL/时长组合内重复算（小，量大）。

## P1 search 搜索页（已审，2026-10-22）

范围：`feature/search/SearchScreen.kt(492)` + `SearchViewModel.kt` + `SearchPagingSource.kt` + `data/bilibili/BiliRepository.searchPageWithTotal/throttleSearch`

### P1-S1 结果列表 `items(count)` 没给 key，全列无复用

位置：`SearchScreen.kt:SearchResultBody → LazyColumn items(count = paging.itemCount)`

现状：`items(count){ index -> val track = paging[index]; SearchRow(...) }`，无 `key` 参数。Home 页给了 `key = "${bvid}:${cid}#$index"`（虽不完美但有），搜索页直接缺省，`LazyColumn` 只能按位置复用，append/refresh 时可见行全部重组 + `AsyncImage` 重载，快速上滑最卡的一段。

改法（小）：补 `key = { i -> paging.peek(i)?.let{ "${it.bvid}:${it.cid}" } ?: "ph-$i" }`，与 Home 统一；`cid=0` 重复问题同样存在，源头按 `bvid` 合并（见 H3）。

### P1-S2 Paging 配置回到默认值，append 风暴风险

位置：`SearchViewModel.kt:pagingFlow = Pager(PagingConfig(pageSize=20, enablePlaceholders=false))`

现状：没抄 Home 的 `prefetchDistance=8/maxSize=200`，走默认 `prefetchDistance=pageSize(20)` + `maxSize=无限`。搜索结果页快速上滑会一次排 20 条预取，`BiliRepository.SEARCH_MIN_INTERVAL_MS=800ms` 节流会被连续 append 打穿，撞 412。`SearchPagingSource` 也没有 Home 那种 `RATE_LIMIT_BACKOFF 2s + seenBvids 去重`。

改法（小）：`PagingConfig(pageSize=20, prefetchDistance=8, initialLoadSize=20, maxSize=200, enablePlaceholders=false)` 与 Home 对齐；`SearchPagingSource` 加同样的 412退避 + 空页 `nextKey=null` 收敛（现在 `tracks.isEmpty()→null` 已对，缺退避）。

### P1-S3 顶层 5 个 collect + totalCount 全页抖

位置：`SearchScreen.kt:78-84`、`SearchViewModel.kt`

现状：`paging/keyword/defaultKeyword/searchHistory/suggestKeywords/totalCount` 全在入口 collect。`totalCount` 由 `SearchPagingSource.onTotalLoaded` 在 `load()` 里回写 `_totalCount.value`，第 1 页回来就让整个 `SearchScreen` 重组一次（含搜索框 `TextField`，输入焦点可能闪）。`defaultKeyword/suggestKeywords` 同源 `prefs.homeKeywords` 却开了两个 `stateIn(Eagerly)`，`searchHistory` 又一个，三份 DataStore 订阅。

改法（小）：`totalCount` 下沉到计数条 Composable 内单独 collect；`defaultKeyword = suggestKeywords.map{firstOrNull}` 派生，不另开 `stateIn`；`onTotalLoaded` 用 `distinctUntilChanged`，总数不变不回写。

### P1-S4 输入与键盘三件套冗余

位置：`SearchScreen.kt:86-115`

现状：`var input by remember{mutableStateOf(kw)}` + `doSearch()` 里 `focusManager.clearFocus() + keyboard?.hide() + IMM.hideSoftInputFromWindow()` 三连关键盘；`LaunchedEffect(Unit){ focusRequester.requestFocus(); keyboard?.show() }` 每次进页（含从详情返回）都弹键盘。

为什么慢/体验差：三连关键盘触发 2-3 次 `WindowInsets` 重布局；返回页弹键盘遮住结果，还得多一次收起动画。`triggerSearch` lambda 每次重组重建，`SearchBarRow` 跟着重组（`TextField` 重测是重灾，输入法联想时每字一测）。

改法（小）：只留 `keyboard?.hide()` 或 `clearFocus()` 二选一；`requestFocus+show` 仅首次进页（`SavedStateHandle keyword` 为空且 `paging.itemCount==0` 时）；`triggerSearch/doSearch` 用 `remember(kw){}` 缓存，`SearchBarRow` 参数加 `stable`。

### P1-S5 行布局重复造轮子

位置：`SearchScreen.kt:SearchRow + formatDuration`

- `track.cover.biliImage(ListThumb 480x270)` 给 `116x72dp` 小图用，大了 4 倍，列表 20 条就是 20 张大图解码。行图用 `SmallSquare(128)` 或新增 `SearchThumb(232x144)` 档。
- 私有 `formatDuration(ms)` 与 `core/ui/TrackFormat.formatDurationMs` 一字不差，重复实现。且 `String.format` 在组合内，每行每帧分配。复用 core 函数 + `remember(durationMs){}`。
- `buildString{ UP+pageCount }` 每组合拼接，应 `remember(author,pageCount){}`。
- `HistoryChip/SuggestionChip` 在 `FlowRow` 里 `forEach` 无 key，历史 50 条就是全量 `Surface+Row`，`SearchGuide` 还是 `verticalScroll(Column)` 非 Lazy。历史截 20 条 + `key(h)` 即可。

**P1-search Top：** ①补 Lazy key（小，收益大）②PagingConfig 对齐 Home（小）③totalCount 下沉（小）。

## P1 detail 详情页（已审，2026-10-22）

范围：`feature/detail/DetailScreen.kt(392)` + `DetailViewModel.kt(200)` + `data/bilibili/dto/ViewDto.toTracks()`

### P1-D1 入口 7 个 StateFlow，历史全表进组合

位置：`DetailScreen.kt:DetailScreen` + `DetailViewModel.kt:progress/playingTrack/focus`

现状：`state/isFavorited/message/startingPlay/pendingIndex/focus/playingTrack/progress` 7 个顶层订阅。其中：
- `progress = historyDao.observeAll().map{associateBy{bvid:cid}}` 全历史表（听书一年上千条）每次写入全量 `associateBy` 重建 Map，全页重组。
- `playingTrack = player.state.map{...positionMs/1000*1000}` 播放器每 500ms-1s 推一次，整秒取整仍每秒变一次，`DetailContent` 整列跟着动。
- `focus = combine(state,progress,playingTrack){ filter{containsKey}.maxBy{updatedAt} }` 每次 progress/playing 变都 `filter + maxBy` 全列表扫描（200P 合集每秒扫一次）。

改法（中）：`progress` 只取本 `bvid`（`historyDao.observeByBvid(bvid)`，若无此 DAO 就 `observeAll.map{filter bvid}` + `distinctUntilChanged`）；`playingTrack` 只在本页 `bvid` 命中才 emit，否则 null；`focus` 加 `distinctUntilChanged`，`index+isPlaying` 不变不重组。行内 `progress["${bvid}:${cid}"]` 字符串 key 每次组合拼接，提为 `remember` 或 VM 直接给 `Map<Long,HistoryEntity>`（key 用 cid）。

### P1-D2 分P行 `itemsIndexed` 无 key + 背景整行重绘

位置：`DetailScreen.kt:itemsIndexed(tracks)`

现状无 `key`，`focused` 行 `background(primaryContainer.copy(alpha=0.45f))` + `clickable + padding` 整行，`focused/playing/played/isPending` 四态每秒变都整行重组。`playing?.takeIf{key==...}` 字符串比较每行一次。

改法（小）：`itemsIndexed(tracks, key={_,t->"${t.bvid}:${t.cid}"})`；高亮用 `Modifier.background` 换 `Surface(selected)` 或只刷行内小圆点，不刷整行背景。

### P1-D3 头图三块重活：简介全文 + 日期 `SimpleDateFormat` + 双 `AsyncImage`

- `video.desc` 全文 `Text` 直接进组合，长简介（万字）一次测量几十 ms，首帧卡。折叠（maxLines 3 + 展开按钮）+ `remember(desc){}`。
- `formatDate(pubdate*1000)` 每次组合 `SimpleDateFormat("yyyy-MM-dd")` new 一个（`SimpleDateFormat` 又贵又非线程安全），`formatCount(view)` 同理。VM 层一次算好 `dateStr/statStr` 字符串，UI 只展示。
- `video.pic.biliImage(DetailHero)` + `owner.face.biliImage(Avatar)` 两张原图同时解码，封面 `aspectRatio(16/9)` 全宽大图，应用 `size` 约束让 Coil 按视口解码。

### P1-D4 `toTracks()` 在 Main 线程

位置：`DetailViewModel.load(){ repo.getViewDetail → video.toTracks() }`（`ViewDto.kt:83` 遍历 `pages` 组 `Track`）

`getViewDetail` 是挂起但 Retrofit 回调后直接在 `viewModelScope(Main)` 里 `toTracks()`，200P 合集循环组装 + `normalizeBiliImageUrl` 全在主线程。改 `withContext(Dispatchers.Default){ video.toTracks() }`，再 `update`。`toggleFavorite` 里 `tracks.forEach{addTrack}` 逐条 insert（200 次事务），换 `insertAll` 一次事务。

## P1 playlist 歌单（已审，2026-10-22）

范围：`PlaylistScreen.kt(211)` + `PlaylistDetailScreen.kt(475)` + `PlaylistDetailViewModel.kt(215)` + `PlaylistViewModel.kt(210)`

### P1-P1 详情横幅双 `AsyncImage` 同一 URL 两尺寸

位置：`PlaylistDetailScreen.kt:封面横幅`

现状：模糊背景 `pl.cover.biliImage(DetailHero)` 全屏 `alpha=0.35 + gradient` + 前景 `pl.cover.biliImage(256,256)` 96dp，两张同源不同尺寸同时解码，大图按屏幕解码（~1080p）只做背景虚化，浪费 5-10MB。且 `Brush.verticalGradient` 每重组重建。

改法（小）：背景用小图（`128px`）+ `blur` 修饰或纯色 `primaryContainer` 渐变，不加载大图；渐变 `remember{Brush}` 缓存；横幅 `height(240.dp)` 固定，内容折叠不触横幅重测。

### P1-P2 列表与 Detail 同病：`observeAll + focus 全扫`

`PlaylistDetailViewModel` 与 Detail 共用一套 `progress/playingTrack/focus`（`observeAll全表 + filter+maxBy`），200 集听单每秒扫一次。改法同 D1：按 `playlistId` 过滤 + `distinctUntilChanged`。`playlist/tracks` 两个 `stateIn(WhileSubscribed(5000))` 各开一个 Room 观察，合为一个 `combine` 也行，但 Room 本身是细粒度更新，留中优化即可。

### P1-P3 `PlaylistScreen` 列表项封面未分档（待细看，已记模式）

歌单封面沿用详情大图 URL（`DetailHero`），列表页 20 个歌单就是 20 张大图。统一：列表 `ListThumb/SmallSquare`，详情 `DetailHero`，播放条 `Avatar`，三档 key 隔离，Coil 磁盘命中、内存各存一份。

**P1-detail/playlist Top：** ①历史全表 `associateBy` 进组合（中）②分P无 key + 整秒进度全列抖（小）③简介全文 + SDF 每帧 new（小）④横幅双大图（小）。

## P1 history 历史页（已审，2026-10-22）

范围：`feature/history/HistoryScreen.kt(239)` + `HistoryViewModel.kt(109)`

### P1-H1 进入历史即 N 路 `getView` 打满

位置：`HistoryViewModel.init{ history.collect{ distinct bvid.forEach{loadVideoInfo} } }`

现状：`history: observeAll(Eagerly)` + 每个 distinct `bvid` 一次 `repo.getView(bvid)`（view 接口），无并发 bound、无取消、无 TTL。100 条历史若 60 个 BV，进页瞬间 60 个请求，DEM 限流 412 直接吃满；`_videoInfo: Map<bvid,List<Track>>` 常驻内存，越用越大，从不清理。

改法（中）：只加载首屏可见 BV（`LazyColumn` 可见项回调按需 `loadVideoInfo`），或落盘（`HistoryEntity` 冗余 `title/cover/duration`，写历史时一次写全，读零网络）。并发加 `Semaphore(4)` + `supervisorScope`，失败 `inflight.remove` 重试加指数退避。`_videoInfo` 加 LRU 50 个 BV 上限。

### P1-H2 行内三重字符串重算

位置：`HistoryScreen.kt:HistoryRow + historyChapterLine + formatDate + playedProgressLabel`

- `key = "${bvid}:${cid}"` 好（全工程唯一稳定的一处）。
- `val track = info[item.bvid].orEmpty().firstOrNull{it.cid==cid}` 每行每重组一次 map 查 + 线性扫，1000 条历史滚一次全量扫。VM 直接给 `Map<"bvid:cid",Track>`，行内一次 `get`。
- `historyChapterLine(track,bvTitle)`：`trim + startsWith + removePrefix + any{!digit} + buildList+joinToString` 每行每帧，`remember(entity, track){}` 缓存。
- `formatDate(updatedAt)` 每次 `new SimpleDateFormat("MM-dd")`，又贵又非线程安全。提为 `private val SDF = ThreadLocal<SimpleDateFormat>` 或 VM 算好 `dateStr`。
- `playedProgressLabel(pageIndex,positionMs,durationMs)` 内 `formatDurationMs` 又 `String.format`，同上 `remember`。

### P1-H3 顶部文案 + 进度条细节

- `TingBiliTopAppBar(subtitle="共 ${list.size} 条")`，`list` 全表订阅，删一条全页重组（含所有行）。可接受（低频），但 `subtitle` 字符串每重组拼接，提 `remember(list.size)`。
- `LinearProgressIndicator(progress={fraction})` 用了 lambda 版，好，不会因进度重组整行。但 `fraction = positionMs.toFloat()/duration` 每组合除一次，`remember` 即可。

## P1 player 播放页（已审，2026-10-22，重点）

范围：`feature/player/PlayerScreen.kt(1300)` + `PlayerViewModel.kt(310)` + `LyricState.kt` + `PlayerHeader.kt`

### P1-P1 `uiState = combine(player.state, ...)` 全页每 tick 重组（本工程最贵）

位置：`PlayerViewModel.kt:168 uiState = combine(player.state, subtitleFlow, stepSec, subtitleScale)`

现状：`player.state` 含 `positionMs/bufferedPositionMs`（`PlayerManager.startPositionPoll` 高频 `update`），`combine` 后 `idx = LyricState(lyrics).indexFor(p.positionMs)`（二分，好）+ 整 `PlayerUiState` copy，`PlayerScreen: val s by vm.uiState.collect...` 全页订阅。结果：进度每推一次（~500ms-1s），`Column(封面+标题+MiniLyricCard+ProgressBar+AuxBar)` 全量重组。封面 `AsyncImage(SquareCover)` + `shadow(18.dp)+clip(24.dp)` 每 tick 重测，阴影是离屏缓冲重灾。

改法（大，必做）：`uiState` 拆三层：
- `trackUi: track/queue/index/isLoading/error`（低频）
- `progressUi: positionMs/buffered/duration`（高频，只给 `PlaybackProgressBar`）
- `lyricUi: lyrics/currentLyricIndex/subtitleLoadState`（中频，行级 `distinct`）
`PlayerScreen` 顶层只收 `trackUi`，进度条/歌词卡各收各的。`currentLyricIndex` 变化只刷歌词区，不刷封面/标题。

### P1-P2 全屏歌词两步居中每句滚两次

位置：`PlayerScreen.kt:FullscreenLyrics ~923-1010`

现状：`BoxWithConstraints{ centerPadding = maxHeight/2; contentPadding(vertical=centerPadding) }` + `LaunchedEffect(currentLyricIndex, lyrics.size, centerPadding){ scrollToItem(idx); snapshotFlow{visible}.first(); scroll{scrollBy(delta)} }`。每句歌词：1 次 `scrollToItem` 粗定位（闪一下）+ 等布局 + 1 次 `scrollBy` 精调，`centerPadding` 随窗口变还重滚。`comment` 自述 `scrollToItem offset 语义真机落不到中央`，实为用两次滚动补一帧，低端机歌词抖。

改法（中）：`LazyColumn(contentPadding=centerPadding)` 保留，滚动一次 `animateScrollToItem(idx, -centerPaddingPx)` 或 `scrollToItem(idx)` + `offset` 预计算，不二次 `scrollBy`；`snapshotFlow.first()` 等待加 100ms 超时兜底，避免冷进场卡住。`key = "$idx-${timeMs}"` 带 idx，插入新行全 key 变，AI 字幕同秒多行问题用 `"${timeMs}-$idx"` 同样，但 lyrics 靜態 per 轨可接受，保留。

### P1-P3 迷你歌词卡 + 进度条高频区未隔离

- `MiniLyricCard(lyrics, currentIndex=getOrNull(index±2))` 每次 `currentLyricIndex` 变整卡重组，`prev/current/next1/next2` 四行 `getOrNull` + `Text(style.copy(fontSize*scale))`，`subtitleScale` 乘法每行每帧。`remember(currentIndex, lyrics, scale){}` 切片。
- `PlaybackProgressBar(positionMs, buffered, duration)`：`ratio()` 除法 + `formatMs()` 两个 `String.format` 每 tick，`Slider(track={Canvas{3×drawRoundRect}})` 每帧三圆角矩形 + `thumb animateFloatAsState(scale)`。进度文案 `remember(positionSec)` 秒级更新，别毫秒级；`Canvas` 三层改两层（track+active，buffered 用 alpha 叠），`thumb` 缩放动画 `label` 已对。
- `PlayerAuxBar(sleepLabel/speedLabel)` 字符串每 tick 拼（`sleepLabel(remainSec)`），提 `remember(remainSec)`。

### P1-P4 封面与弹窗

- 封面 `SquareCover 480` + `fillMaxWidth(0.76f)+widthIn(max=320dp)+aspectRatio(1)+shadow(18.dp, RoundedRect(24))`：`shadow` 大圆角离屏缓冲每重组重画（P1 連帶），`clip` 后还 `shadow(clip=false)` 先画影再裁，GPU 双倍。封面独立 `remember(track.cover)` + `graphicsLayer(shadowElevation)` 缓存，或关 `shadow` 改描边。
- `showTimerSheet/showChapterSheet/showSubtitleSheet/showMoreSheet` 四个 `remember{false}` + `if(showX){Sheet(queue/tracks...)}`，`ChapterSheet(queue)` 全队列进组合（200P 一次全测），`SleepTimerSheet` 内 `LazyColumn(items(SLEEP_GRID_COUNT))` + `rememberSnapFlingBehavior` 滚轮。弹窗只在打开时组合是对的，但 `finishDefault/lastMin` 在 `PlayerScreen` 顶层 `collectAsStateWithLifecycle().value`，弹窗没开也订阅。移到 Sheet 内部订阅。

**P1-player Top：** ①拆 `uiState` 三层隔离 tick（大）②歌词一次滚动（中）③封面阴影隔离（小）。

## P1 mine 我的页（已审，2026-10-22）

范围：`MeScreen.kt(565)` + `MeViewModel.kt(86)` + `SettingsScreen.kt` + `AppearanceScreen.kt(341)` + `OtherSettingsScreen.kt(246)` + `BiliFavScreens.kt(271)` + `BiliHistoryToviewScreens.kt(228)` + `BiliSpaceUi.kt` + `LoginScreen.kt(397)` + `GeetestDialog.kt(190)`

### P1-M1 `historyTodayCount` 全表扫 + Calendar 每秒跑

位置：`MeViewModel.kt:historyTodayCount = historyDao.observeAll().map{ Calendar.getInstance()...count{updatedAt>=today} }`

全历史表每次写入（播放时每秒一次，见 P2-M1）都 `Calendar.getInstance()` + 全量 `count`，`MeScreen` 顶层 6 订阅之一跟着每秒重组。`verticalScroll(Column)` 非 Lazy，头部渐变 + 四宫格 + 设置组全量重测。

改法（小）：DAO 加 `observeTodayCount(todayStart): Flow<Int>`（`SELECT COUNT(*) WHERE updatedAt>=:today`），VM 只收 Int；`todayStart` 一天算一次 `remember`，别每次 map new Calendar。

### P1-M2 `Slider onValueChange` 直写 DataStore

位置：`SettingsScreen.kt:Slider(value=step.onValueChange={vm.setStep(it.toInt())})`（字幕缩放同理）

拖滑杆每帧 `setStep → prefs.setStep(DataStore.write)`，protobuf 全量重写每帧一次，卡 + 磨损。改 `onValueChange` 只改本地 `remember`，`onValueChangeFinished` 再 `vm.setStep`。快捷按钮 `5/15/30/60` 直接写没问题。

### P1-M3 B 站列表加载原图

位置：`BiliFavScreens.kt` + `BiliSpaceUi.kt:import normalizeBiliImageUrl`（无 `biliImage(variant)`）

`AsyncImage(model = normalizeBiliImageUrl(...))` 直链原图（1080p+），收藏/历史/稍后听列表 20 行就是 20 张大图同时解码，滚两下 OOM 风险。改 `biliImage(SmallSquare/ListThumb, imageFormat)` 三档，`Avatar` 头像用 `Avatar(96)`。

### P1-M4 外观 HSV 拖动全量重组（P0 联动）

`AppearanceScreen` HSV 取色每帧 `customThemeColor.update`，`MainActivity.TingBiliTheme` 全树重组。加 100ms `debounce` + `Slider onValueChangeFinished` 提交，预览块本地 `remember` 不进 DataStore。

### P1-M5 登录/极验 WebView

`LoginScreen` 扫码轮询 `delay` 协程，退出页必须 `cancel`（已做则好，待复查）；`GeetestDialog` WebView 预加载慢，首次弹再 `init`，关页 `destroy`，别常驻。

## P2 媒体/数据/网络（已审，2026-10-22，根因章）

范围：`PlayerManager.kt(823)` + `PlaybackConnection/ControllerHandle` + `ServicePlayerModule` + `TingBiliPlaybackService` + `AppDataModule OkHttp` + `PreferencesRepository` + `HistoryDao/PlaylistDao/HomeSnapshot/SubtitleDiskCache` + `BiliRepository throttle` + `BuvidProvider/Wbi`

### P2-M1 全 App 1Hz 抖动的根因：历史每秒写 → observeAll 每秒发

链路：`PlayerManager.startHistoryThrottle(){while{delay(1000); historyDao.save(HistoryEntity(positionMs, updatedAt=now))}}` + `startPositionPoll(){while{delay(500); _state.update{positionMs}; prefs.setLastPosition()}}` 双循环并发。

- `historyDao.save` 每秒改 `updatedAt`，`observeAll()` 全量 emit → `Detail.focus/progress`、`Playlist.focus/progress`、`Me.historyTodayCount`、`History.list` 每秒重组 + `associateBy/filter+maxBy` 全扫。暂停页切后台 `WhileSubscribed(5000)` 还多抖 5 秒。
- `prefs.setLastPosition` 每 500ms + 每 1s 各一次 DataStore protobuf 重写，`lastQueue/lastIndex` 无变化也写。DataStore 写是全文件序列化，1 秒两次是磨损 + 主线程 `dispatch` 排队。

改法（大，收益最高）：
- 历史写节流 10s + 切歌/seek/暂停即时写，`updatedAt` 只在切歌时变，进度列单独 `positionMs` 不触发 list 订阅（DAO 拆 `observeIds()` 轻流 + `observeByBvid()` 细流）。
- `positionPoll` 只改内存 `_state`，不写 DataStore；`setLastPosition` 并入历史 10s 写。
- `Detail/Playlist/Me` 的 `observeAll` 全换按需 DAO（`observeByBvid/countToday`），`distinctUntilChanged`。

### P2-M2 `play()` 三个阻塞点

位置：`PlayerManager.play()/resolveCid/resolveUrls`

- `resolveCid`: `cid==0`（搜索结果）逐个 `getView(bvid)` 串行，10 首列表 10 次 RTT。改 `supervisorScope async` 并发 4 + 结果缓存 `_videoInfo` 复用。
- `resolveUrls`: `prefs.playUrlCache.first()` 读全量 DataStore Map（URL 可几百条），`Semaphore(4)` 并发 `getPlayUrl` 好，但 `putPlayUrls` 全量回写。playUrl 缓存迁 Room（`playUrlCache` 表，key=`bvid:cid`，TTL 2h），DataStore 只留指针。
- 乐观 `update{queue/currentTrack}` 后 `setMediaItems+prepare+play` 同步调 `MediaController`（Binder 跨进程），主线程 `play()` 挂起。`transport.setMediaItems` 切 `Dispatchers.IO`，`isLoading` 先行已对。

### P2-M3 OkHttp/拦截器/会话

位置：`AppDataModule.provideOkHttp`

- `OkHttpClient.Builder()` 无 `cache/connectTimeout/readTimeout/callTimeout/connectionPool`，B 站 412/超时直接 hang，`load()` 协程堆积。加 `connect 10s/read 15s/call 20s + cache 10MB（仅 search/view，playurl 禁缓存）`。
- `WbiInterceptor` 每次请求 `sessionFlow.value?.toCookie + buvidProvider.cookie() + trim/joinToString`，字符串三连每请求。`cookieHeader` 缓存 1min `remember`，变了才拼。
- `BuvidProvider` spi `execute()` 同步阻塞 + `json.parse`，首次请求慢 500ms+。应用启动预热一次 `buvidProvider.warmup()`，失败缓存空，请求线程不阻塞。

### P2-M4 Room/DataStore 落盘

- `toggleFavorite` 200P `forEach{addTrack}` 200 事务，换 `insertAll` 一事务。
- `HomeSnapshot tracksJson` 60 条 JSON 主线程 `decode`（P1-H5 已记），`SubtitleDiskCache linesJson` 同理切 IO。
- `playlistDao.observePlaylists().map{it.size}` 全表读只为 count，换 `SELECT COUNT(*)`。

**P2 Top：** ①历史 1s 写→全 App 1Hz（大）②DataStore 1s 两写（大）③搜索/详情 N 路 getView（中）④OkHttp 无超时缓存（小）。

## P2 图片/缓存/主题（已审，2026-10-22）

范围：`dto/ImageUrl.kt` + Coil `AsyncImage` 全工程 + `cache/AppCacheManager.kt` + `core/data/cache/CacheManager.kt` + `core/data/db/SubtitleDiskCache.kt` + `SubtitleCache.kt` + `core/ui/theme/Theme.kt`

### P2-I1 五档尺寸有，调用乱

`BiliImageVariant(ListThumb 480x270 / DetailHero 720x405 / SquareCover 480 / SmallSquare 128 / Avatar 96)` 分档是对的，但调用错位：
- 搜索行 `116x72dp` 用 `ListThumb 480`（4 倍解码，20 行同时解码，滚即掉帧）。应 `232x144` 或 `SmallSquare`。
- mini 条（48dp 高）疑似 480，应 `SmallSquare 128`。
- 首页网格 `156dp` 列宽用 `ListThumb 480` 刚好 3x 屏，OK 保留。
- 播放页 `320dp` 封面用 `SquareCover 480`（1.5x），中端机可接受，高分屏微糊。加 `LargeSquare 640` 档给播放页，列表不动。
- `BiliFav/Space` 用 `normalizeBiliImageUrl` 裸原图（1080p+），最浪费。强制走 variant。

### P2-I2 URL 拼接 + 格式切换双倍缓存

- `normalizeBiliImageUrl(): startsWith+replaceFirst+substringBefore('@')` + `"$base@${w}w_${h}h.$format"` 每组合一次，60 格 × N 次重组全是分配。`remember(cover, imageFormat){cover.biliImage(...)}`。
- `biliImage(variant, ImageFormat)` format 一换（webp→avif）URL 全变，Coil 磁盘命中归零，同一图存两份。`AsyncImage` 全工程无 `memoryCacheKey/diskCacheKey`，换格式即翻倍。改：磁盘 key 去格式（`memoryCacheKey="${bvid}:${cid}"`），或锁定列表 webp、详情才跟随用户格式。
- `AsyncImage` 无 `placeholder/error/crossfade`，列表滚白闪。加 `placeholderMemoryCacheKey + crossfade(true) + size(原视口)`，Coil 按视口解码不载原图。

### P2-I3 Coil 与三块缓存未调优

- 全工程 `SingletonImageLoader.get` 默认实例，`coil-network-okhttp` 已依赖但未注入自定义 `ImageLoader`（磁盘 200MB + 内存 25% + OkHttp 共用）。默认磁盘偏小，封面 hit 低。
- `AppCacheManager.stats()`: `subtitleDiskDao.rowSizes()` 全表 + 每行 `tracksJson.toByteArray().size` 主线程算字节，设置页每次打开卡一次。`clearAudio: audioCache.keys.forEach{removeResource}` 主线程逐删。全部 `withContext(Dispatchers.IO)`，`stats` 加 5s 内存缓存。
- `SubtitleCache`: `LinkedHashMap LRU 20 + synchronized(lock)` 好，但 `tracksResult/linesResult` 先查内存再 `repo` 两跳网络，无磁盘 TTL 检查前置（`cached()` 另有 `isFresh`，链路已对）。`linesCache` key 是整轨 url（超长），换 `bvid:cid:index` 短 key。

### P2-I4 主题 `brandedColorScheme` 每重组全算

位置：`Theme.kt:TingBiliTheme + brandedColorScheme(seed,dark)`

`blend()/contentColor()` 十几次颜色混合，每次 `customThemeColor` 变（HSV 拖动每帧）全算 + `MaterialTheme(colorScheme=scheme)` 全树重组。`scheme` 用 `remember(themeMode, darkTheme, customArgb){brandedColorScheme(...)}` 缓存；HSV 拖动 `debounce 100ms`（P1-M4 联动）。`LocalDarkTheme provides darkTheme` 单 Boolean 好，开销可忽略。

## P2 构建/稳定性（已审，2026-10-22）

- 版本：Kotlin 2.4.20 / AGP 9.4 / Compose BOM 2026.08 / Navigation 2.9.8 / Hilt 2.60.1 / Room 2.8.4 / Media3 1.10.1 / Paging 3.3.6（见 `libs.versions.toml` + `CONVENTIONS`），无混用，好。
- `Track/Playlist/ViewData` 等 `@Serializable` 无 `@Immutable/@Stable`，Compose 强跳过认不出稳定类型，含 Track 参数的 Composable 全当 unstable 重组。加 `@Immutable`（kotlinx 与 compose 兼容），跑 `composeCompiler { stabilityConfigurationFile }` 验证。
- `proguard-rules.pro` 只 keep 自家 `@Serializable`，R8 几乎零代价，好。release 若未开 `shrinkResources/minify` 则开；`baselineProfiles` 无模块，建议加 `:benchmark` + 启动/滚动 Baseline Profile，中端机启动 15-20% 提升。
- Room `MIGRATION_1_2...7_8` 全手写，好；`fallbackToDestructiveMigrationOnDowngrade` 仅降级破坏，可接受。
- `gradle.properties Xmx2g/parallel=false` 是构建侧，不影响线上性能，不展开。

## 统一动手顺序（两份文档的关系）

- 先结构后单点：先按 `docs/ARCH_REVIEW.md` 落地顺序（Top5）动结构，再按本文总 Top10 逐项消单点，否则“历史 1s 写”这类根因会换个马甲回来。
- 两份无实质冲突：ARCH 管“道”（分层/真相源/缓存归属），本文管“术”（订阅粒度/key/线程/图片分档）。
- 动手前先看本文文末“明确不做”清单（memoryCacheKey/HSV 防抖/ThemeConfig 单流/锁拆分/文件缓存），有真机数据或反向验证 backing，无新理由勿重开。

## 总 Top10（按收益/成本排序，照此优化）

1. **P2-M1 历史 1s 写→全 App 1Hz**：10s 节流 + 切歌即写 + poll 只改内存 + DAO 按需化。【大/收益最大】
2. **P1-P1 播放页 uiState 拆三层**：track/progress/lyric 各收各，进度 tick 不刷封面。【大/最贵单页】
3. **P0-2 脚手架收整 PlayerState**：只收 track/isPlaying，progress 下沉条内。【中/全页常驻】
4. **P1-H1 搜索/历史/详情 N 路 getView**：可见才拉 + Room 冗余 + 并发 bound 4。【中】
5. **P1-S1/S2 搜索无 key + Paging 回默认**：补 key + `prefetch 8/maxSize 200` + 412 退避。【小/见效快】
6. **P2-I1/I2 图片错档 + 无 cacheKey**：mini 128/行 232/网 480/播 640/详情 720 + `memoryCacheKey` 稳定。【小/内存立降】
7. **P0-1 主题色/图片格式全局订阅**：拆层 + `remember(scheme)` + HSV debounce。【小】
8. **P1-D2/P2 分P无 key + 整秒进度全列扫**：补 key + `distinctUntilChanged` + Map key 用 cid Long。【小】
9. **P1-H3/P1-S5 卡片 URL/时长组合内算**：`remember` 缓存 + 复用 `formatDurationMs` + Coil size。【小/量大】
10. **P2-M3 OkHttp 无超时缓存 + P2-I3 缓存主线程**：超时 10/15/20s + search/view 磁盘缓存 + stats/clear 切 IO。【小/防雪崩】

验证：每项改完看 `Layout Inspector 重组计数`（目标：进度 tick 只刷进度条一行）+ `Android Studio Profiler 内存/网络` + 低端机滚列表帧率。Baseline Profile 上线后再跑一遍启动。

## 落地进度（2026-10-22 晚，master @1c058b7，共 26 个提交）

对照总 Top10 逐项销账（✅=已上机验证，🟡=部分，⬜=未动）：

1. ✅ 历史 10s 节流 + DAO 按需化（`1b25426` + `d96738d` + `742941b`/`8dfc5b5`/`a466a9d`）：poll 只改内存；暂停/seek 即时刷；详情只订本 BV、歌单只订本单 BV、今日数改 DB COUNT。注：切歌瞬间仍等下一 tick（≤10s），可接受。
2. ✅ 播放页拆三层（`a2cd153`）：track/prog/lyric 各收各，真机播/停/字幕正常。
3. ✅ 底栏槽位隔离（`2e6402c`）：500ms tick 只重组 MiniPlayerSlot，4 个 Tab 不动。注：槽内仍是整态订阅，下一步可再细拆 progress。
4. ⬜ N 路 getView：`HistoryViewModel.loadVideoInfo` 仍按 distinct bvid 全拉，未动（明天首选）。
5. ✅ 搜索补 key + Paging 对齐 + 412 退避（`11286a0`/`f226c45`/`04f2784`）。
6. 🟡 图片：搜索行 232 档 + 三处 remember 缓存 done；mini 128/播放 640/详情 720 分档 + `memoryCacheKey` 未动。
7. 🟡 主题：`remember(scheme)`（`d48932e`）+ 图片格式按页提供（`1c058b7`）done；HSV 拖动 debounce 未动。
8. ⬜ 分P无 key + 整秒进度：详情/歌单 `itemsIndexed` key 未补。
9. ✅ 卡片 URL/时长组合内算：search/home/history 三处 remember done。
10. ✅ 超时 + 缓存切 IO（`712e3b7`/`6ee0525`）；search/view 磁盘缓存未动。

另：`isMainTabDestination` 收敛（`a2d3d6a`）、通知权限延后（`8b3e8ee`）、Slider 松手提交（`71e0002`）、5 处旧 fake 补齐（门禁恢复全绿，165 单测 0 失败）。

## 落地进度（2026-10-23 上午，master @509f182）

- ✅ N 路 getView 改懒加载（`5af366c`）：进页不再全量拉，行进入组合才 `prefetch` + 4 并发 bound；单测断言“历史 emit 不触发 view、prefetch 才触发且去重”（6/6）。
- ✅ 分P key（`ec5a1fa`）：详情补 `"bvid:cid"` 稳定 key（歌单早已有）。
- ✅ 图片分档收尾（`a081549`）：播放封面 640 档；B 站收藏/空间列表原图改按尺寸裁剪（192x120 / ListThumb）。`memoryCacheKey` 未做：需等 Coil ImageLoader 统一调参，记入 `core:cache` 模块时一起做。
- ✅ 队列真相第一步（`5b25864`）：refreshQueue 拆纯 plan + 不断播手术 + 双尾合一，24 单测钉住行为，真机刷新链路回归通过。
- ✅ 播放器拆分回填坑（`47226a8` + `509f182`）：拆订阅后 `playAt/refreshBv/cycleSpeed` 误读无人订阅的 `uiState` 导致静默 return；改读常热 `player.state`，回归单测反向验证（旧代码必挂 1 个），真机章节 P1→P5 跳播验证通过。

## 落地进度（2026-10-23 中午，master @3b3b573）

- ✅ 缓存搬家第一步（`3b3b573`）：playUrl 大 Map 迁出 DataStore → Room `PlayUrlCacheEntity`（v9 + `MIGRATION_8_9`）；PlayerManager 经 `PlayUrlCacheDao` 读写 + 启动一次性搬运旧 JSON（读完删键）；prefs 旧读写 API 已删。真机验证：prefs 文件 19KB 且无 `playurl_cache_json` 键，播放/暂停/字幕正常。171 单测全绿（含新增 `PlayUrlCacheDaoTest` 4 例）。
- 剩下：HomeSnapshot 单行 60 条 JSON、Subtitle 万行 linesJson 仍在 Room TEXT 里（读多写少，暂缓；动则加文件缓存）。

## 落地进度（2026-10-23 中午，master @40148a0）

- ✅ 网络 memo（`40148a0`）：view/viewDetail 成功态内存缓存 5min（只缓存 code==0，412 毒缓存免疫）；`refreshBv` 强制直取。单测：命中只发 1 请求、错误不缓存、强制刷新绕过（data:bilibili 57/57）。
- ✅ Cookie 请求头 60s 缓存（`97b1f25`）：拦截器热路径只读缓存，会话变更即时失效；单测覆盖拼接/缓存/登出。
- ✅ MainViewModel 独立成文件（`319955d`）：纯搬家，app 单测全绿。
- 真机：P5 续播正常，字幕正常，零崩溃。

## 落地进度（2026-10-23 下午，master @5133271）

- ✅ 队列快照迁 Room（`5133271`）：`PlayQueueSnapshotEntity` 单行表 + v10 + `MIGRATION_9_10`；persist/restore 改 Room Upsert/get；DataStore 旧三键一次性搬运后删除。180 单测全绿（含新增 `PlayQueueDaoTest` 3 例）。
- 真机（升级安装）：旧 prefs 已无 `last_queue_json/last_index/playurl_cache_json`（145 字节，只剩 position/偏好）；杀进程冷启从 Room 恢复 P6/P1；播放/暂停/字幕正常，零崩溃。

## 落地进度（2026-10-23 下午，master @8283729）

- ✅ mini 条隐藏分两层（`8283729`）：滚动隐藏改纯位移+透明度（`animateFloatAsState` 跟 `scrollVisible`，Scaffold 内边距不动）；手动关闭仍走 `AnimatedVisibility` 收缩占位。真机验证：上滚条子移出但槽位 `[0,2274]` 保留、下滚即回；条上上滑关闭后槽位塌掉无 64dp 空隙。

## 落地进度（2026-10-23 下午，热身收尾）

- ✅ 章节目录补 key（`4f367d7`）、回顶只滚可见 Grid（`fb8a0f9`，单测 18/18）。
- ✅ 详情头部文案/封面 remember（`81cd4b1`）、快照 IO（`77483cc`）、MainViewModel 独立文件（`319955d`）、Cookie 头缓存（`97b1f25`，单测 3 例）、view memo（`40148a0`，单测 3 例：命中/错误不缓存/强制刷新）。
- 明确不做（已验证不可行/无收益，勿重开）：
  - `memoryCacheKey`：Coil 3.6 `AsyncImage` 简便重载无此参数，需全站切 `ImageRequest.Builder` + 统一 ImageLoader（placeholder/crossfade/size 一起做才值）。等 `core:image` 模块时再做。
  - HSV 防抖：`CustomColorDialog` 已是本地预览+点应用才提交，无逐帧 DataStore 写。
  - `ThemeConfig` 单流拆分：收集已粒度化，无可测收益。
  - 双 Grid 合并：同一时间只组合一个（无双份内存），回顶已改只滚可见的。
  - 搜索/互斥锁拆分：共用节流是防 412 的保护，拆开反而提高被限概率。

## 落地进度（2026-10-23 下午，mini 条回滚）

- ✅ `c48dbf7`：回滚滚动位移方案。真机验证：位移版滚动隐藏后留 200px 纯底色死区（AnimatedVisibility 收起才是正确语义）；回滚后滚动隐藏/回滚出现/上滑关闭三态正常，无黑块。保留 remember 缓存。

## 落地进度（2026-10-23 下午，mini 条黑条修复 @3274a75）

- ✅ `3274a75`：滚动隐藏 mini 条后留 200px 级黑条，根因是 `AdaptiveMainScaffold` 手机分支（+ `MainTabsScaffold` 同款）的 `AppRootNavHost` modifier 只有 `padding(innerPadding)`、没有 `fillMaxSize`。mini 条 `AnimatedVisibility` 收起 → 底栏高度塌掉 → `innerPadding.bottom` 变小，但内容页不填多出的空间，露出 Scaffold 底色。位移方案不改布局高度所以没暴露，收起方案才显形。修法：`fillMaxSize().padding(...)` 两处补齐。
- 真机（Redmi K70E）：上滑内容 mini 条收起、内容顶到底栏无黑块；下滑 mini 条回来；`app:testDebugUnitTest` 全绿。

## 总 Top10 对 master @3274a75 刷新（2026-10-23 下午）

对照总 Top10 逐项重销账（✅=已上机验证，🟡=部分/暂缓，⬜=未动）：

1. ✅ 历史 10s 节流 + DAO 按需化（`1b25426`+`d96738d`+`742941b`/`8dfc5b5`/`a466a9d`+切歌即写）：poll 只改内存，暂停/seek/切歌即时刷。
2. ✅ 播放页拆三层（`a2cd153`+`47226a8`回填）：track/prog/lyric 各收各，事件读常热 `player.state`。
3. ✅ 底栏槽位隔离（`2e6402c`）+ 黑条修复（`3274a75`）：500ms tick 只重组槽位；滚动收起不再留黑条。
4. ✅ N 路 getView 懒加载（`5af366c`）：行进入组合才 prefetch + 4 并发。
5. ✅ 搜索补 key + Paging 对齐 + 412 退避（`11286a0`/`f226c45`/`04f2784`）。
6. 🟡 图片分档全收尾（`a081549`：mini128/行232/网480/播640/详情720）；`memoryCacheKey` 明确不做（等 `core:image` 统一 ImageLoader）。
7. 🟡 主题：`remember(scheme)` + 图片格式按页提供 done；HSV 防抖/ThemeConfig 单流明确不做（无可测收益）。
8. ✅ 分P key + 章节 key + 整秒进度（`ec5a1fa`/`4f367d7`）：详情 `"bvid:cid"`、章节补 key。
9. ✅ 卡片 URL/时长组合内算：search/home/history remember + 详情头部 remember（`81cd4b1`）。
10. ✅ 超时 + 缓存切 IO（`712e3b7`/`6ee052c`）+ view memo（`40148a0`）+ Cookie 头缓存（`97b1f25`）+ 快照 IO（`77483cc`）：磁盘缓存由 memo 替代（防 412 中毒）。

架构 Top5 对 master @3274a75 刷新：1✅拆 AppDataModule 2✅解耦 media/bilibili 3✅队列真相（plan 纯函数 + Room 单行快照 v10，纯队列表长期暂缓）4🟡缓存搬家（playUrl/队列快照 done；HomeSnapshot/Subtitle 待评估见下）5🟡主题（按页提供 done，拆分暂缓）。

## 落地进度（2026-10-23 下午，缓存搬家收尾 @7473281，真机数据拍板）

- 真机 DB 实测（`run-as cat` 拉库本地查）：`HomeSnapshotEntity` 1 行 4.4KB；`SubtitleDiskEntity` 5 行共 200KB（最大单集 73KB，50 集上限约几 MB）；`PlayUrlCache` 56 行 39KB。结论：**Room 完全够用，不必搬文件缓存**（ARCH 之前“暂缓”是对的，数据证实了）。
- 做的事（线程修正，收益实在）：
  - `HomeViewModel.saveSnapshot`：60 条 Track JSON 编码 + Room 写从主线程改 `Dispatchers.IO`（回调起于 Paging append，滚动中别占主线程）。
  - `PlayerViewModel.subtitleFlow`：内层 `flow{cached()/store()}` 加 `flowOn(subtitleIo)`——之前每次切歌都在主线程做 Room 读 + 万行 JSON 解码（最大 73KB），低端机开播页一顿。
  - 可测试性：`subtitleIo` 经 `PlayerModule @Named("subtitleIo")` 提供（默认 `Dispatchers.IO`，Hilt 2.60 默认参数需显式 binding，已验证编译）；单测 6 处直传 `StandardTestDispatcher`，虚拟时间确定性不变。教训：`flowOn(真实线程)` 会逃逸 `runTest` 虚拟时间，`withTimeout` 包 `first` 在 runTest 里用的是虚拟时钟——等真实线程=必超时；正确姿势是注入调度器。
- 验证：`:app:` Hilt 编译 + `:app:`/`:feature:player:`/`:feature:home:` 单测全绿；真机播放页实时字幕 4 行 정상，`0:44/1:28:30` 进度正常。
## 收官（2026-10-23，master @a72b3d9）

- 本轮审计+修复正式封存：P0/P1/P2 诊断冻结，PERF Top10 10/10 销账，ARCH Top5 实质收官（两项长期暂缓有数据/评估 backing）。
- 核对：18 个引用提交全存在，`testDebugUnitTest` 189/189 全绿，真机 4 轮回归（播放/暂停/字幕/滚动/mini条/队列恢复/黑条）全过，工作区干净。
- 后续动代码只增文末落地进度，不改正文；新基建（Baseline Profile）另开任务，见新会话。

