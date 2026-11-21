# TingBili 升级后全面体检报告

体检时间：2026-11-21 · 范围：升级到最新稳定版（Gradle 9.9.2 / AGP 9.5.2 / Kotlin 2.4.21 / Compose BOM 2026.11.01 等）之后的全项目。
结论先行：**构建、lint、单测全绿，没有 Error。共 74 条编译警告 + 9 条 lint Warning，绝大多数是"用新 API 更好"的迁移提示，不阻塞运行；其中 2 条会在 Kotlin 2.5 变成编译错误，建议优先处理。**

---

## 一、健康状态总览

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量重编译 | `clean assembleDebug --no-daemon` | ✅ BUILD SUCCESSFUL（0 error） |
| Lint | `lintDebug --no-daemon` | ✅ BUILD SUCCESSFUL，13 个模块：0 Error / 9 Warning / 3 Hint |
| 核心单测 | core:data / core:media / feature:player `testDebugUnitTest` | ✅ 全部通过（34 个测试类 XML） |
| 编译警告 | 全量清理重编收集 | ⚠️ 74 条（去重后 74 条唯一位置） |

---

## 二、P0 · 会在 Kotlin 2.5 变成编译错误（建议优先修）

**`PlayerManager.kt:726` 和 `:732`** — `return` 用在没有显式返回类型的表达式体函数中：

```
suspend fun setPlayOrder(order: PlayOrder) = playMutex.withLock {
    if (order == queueManager.playOrder) return   // ← 726
    ...
    if (queue.isEmpty()) return                    // ← 732
```

警告原文：*"This will become an error in language version 2.5 (KTLC-288)"*。我们已升到 Kotlin 2.4.21，下一次语言版本升级（2.5）这里会直接编译失败。
修法：把 `= playMutex.withLock { ... }` 改成块体 `= playMutex.withLock { ... }` 并显式写返回类型，或把这两个 `return` 改成 `return@withLock`。工作量极小。

---

## 三、P1 · 迁移成本可控、数量最多的过期 API

**1. `hiltViewModel` 换了包（29 处，涉及 27 个文件）**
旧：`androidx.hilt.navigation.compose.hiltViewModel`
新：`androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel`
新包已确认存在于 hilt-navigation-compose 1.4.0。纯改 import，无行为差异；但注意新包可能在重载签名上有差异，改完全量编译一次即可。覆盖 home/detail/favorites/history/mine/player/space/app 导航等几乎所有页面。

**2. `rememberModalBottomSheetState` 已废弃（7 处）**
提示改用 `rememberBottomSheetState`（initial value = Hidden）。这是 Compose 1.13 新 BottomSheet API，行为模型有变化（Hidden 初始态），**不能纯机械替换**，需要逐个验证弹层打开/关闭动画。集中在 `PlayerScreen.kt`（4 处）和 `TingBiliBottomSheet.kt`（1 处公共封装），其余在 player 相关。建议改公共封装 + 单独验证。

**3. `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth=...)` 废弃（2 处）**
`MainActivity.kt:244`、`MainTabsScaffold.kt:56`。提示改用 V2 版本以支持 L/XL 宽度尺寸类。涉及平板/折叠屏断点判断，改法简单但要确认断点语义。

**4. 图标 AutoMirrored（6 处）**
`Icons.Filled.ManageSearch` / `Icons.Outlined.ManageSearch`（navigation 2 文件各 2 处）、`Icons.Filled.List`（PlayerScreen 1 处）。改成 `Icons.AutoMirrored.Filled.*`。纯 import + 名字替换，RTL 适配收益。

**5. 状态栏/导航栏着色 API 废弃（2 处）**
`MainActivity.kt:178-179`：`window.statusBarColor` / `navigationBarColor` 已废弃，改用 `WindowCompat` / insets 方案。项目已启用 edge-to-edge，这两行可能本就是兼容兜底，需确认是否仍需保留。

---

## 四、P1 · Compose 文本输入新范式（中等成本，值得做）

`BasicTextField(value, onValueChange, ...)` 重载已废弃，需迁移到 `BasicTextField(state: TextFieldState, ...)`：

- `HomeScreen.kt:370` — 首页搜索框（自绘外壳，36dp 高）
- `SpaceScreen.kt:507` — 搜索框
- `LoginScreen.kt:826-827` — `PasswordVisualTransformation` / `VisualTransformation` 废弃，新方案是 `BasicSecureTextField`

迁移要点：`rememberTextFieldState`、`state.edit{}`、`InputTransformation`/`OutputTransformation`、`TextFieldLineLimits`、`KeyboardActionHandler`。这是 Compose 官方推荐的未来方向，但改动涉及输入交互逻辑，需要逐屏回归测试（中文输入法、搜索提交、密码显示切换）。

---

## 五、P2 · 可清理的"假警告"与轻微问题

**1. OkHttp 5 升级带来的 `response.body?.string()` 多余安全调用（9 处）**
`BiliRepository.kt:264/311/510`、`BuvidProvider.kt:102`、`WbiSigner.kt:77` 等：`response.body` 在 OkHttp 5 中是非空类型，`?.` 和 `.orEmpty()` 里的 `?` 已多余。直接改成 `response.body.string()`。另有 `BuvidProvider.kt:62-65` 的 `SharedPreferences?` 多余安全调用。纯清理，无行为变化。

**2. `DetailViewModel.kt:152/170`、`BiliHistoryScreen.kt:60`** — Elvis `?:` 作用在非空 `String` 上（`result.msg ?: "加载失败"`），说明 `Result.Error.msg` 已被声明为非空。冗余但无害，可删。

**3. `PlayerViewModel.kt:236`** — `chosen!!` 多余（前面 `chosen` 已判空且在成功分支必非空）。可改为 `chosen.` 或重构为 `chosen?.let`。

**4. `PlayerViewModel.kt:413`** — `.sample(1_000)` 是 coroutines FlowPreview API，缺 `@OptIn(FlowPreview::class)`。加注解即可（或 `@FlowPreview`）。

**5. `EncryptedAuthStore.kt:26-31`（9 条警告）** — `EncryptedSharedPreferences` / `MasterKey` 在升级到 security-crypto 1.1.0 正式版后整体被标 deprecated。经核查，1.1.0 官方仍未在该包提供等价替代类，**短期内无法消除**，属于库级遗留问题；未来需关注 androidx.security 的新 API 或迁移到其他加密方案（如 Keystore + 自实现），不建议为消除警告而冒险改动登录凭据存储逻辑。

**6. lint 9 条 Warning（低优先）**
- `ExportedService`（core:media Manifest:6）：MediaSessionService 必须 exported 才能被系统/耳机控制，**合理，属误报，无需改**。
- `UseKtx` ×5：`String.toUri`、`SharedPreferences.edit`、`createBitmap`、`toColorInt` 的 KTX 替代，微优化。
- `ModifierParameter`（MiniPlayerBar.kt:84）：Compose 规范要求 modifier 为首个可选参数。
- `ModifierFactoryExtensionFunction`（TingBiliLyricList.kt:50）：建议把 modifier factory 写成 Modifier 扩展。
- `ObsoleteSdkInt`：`mipmap-anydpi-v26` 目录在 minSdk=31 下多余，可合并进 `mipmap-anydpi`。

---

## 六、无法用自动化覆盖、需注意的点

- 本轮全量重编 + lint + 单测全绿，但** Compose 新 BottomSheet / TextFieldState / hiltViewModel 新包的迁移属于行为相关改动**，静态检查过不了"交互是否正确"，改完必须在真机回归（搜索、密码输入、各弹层）。
- 第二台 Android 17 真机上 `android layout` 控件树 server 装不上，UI 自动化验证受限；如需我改这些交互类警告，建议用第一台 Android 16 设备回归。

---

## 七、建议的处理顺序

1. **立刻修**：P0 两条（Kotlin 2.5 会变 error）。
2. **低风险批量修**：hiltViewModel 换包（29 处）、AutoMirrored 图标（6 处）、多余的 `?.` / `!!` / `?:`（约 12 处）、FlowPreview 注解。
3. **单独立项 + 真机回归**：BottomSheet 新 API、TextFieldState 迁移、系统栏着色方案。
4. **保持不动**：security-crypto deprecated（无等价替代）、ExportedService（合理误报）。

如果你认可，我可以按 1→2→3 的顺序分批修改，每批都跑全量构建 + 单测，交互类改动装真机回归。
