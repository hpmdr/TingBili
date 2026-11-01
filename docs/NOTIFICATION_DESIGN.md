# 通知与媒体会话设计（TingBili）

> 依据：Android 通知渠道/权限规范（Android 13+ 行为）、Media3 `MediaSessionService` 指南、审查报告 A-03/G-13/G-06。
> 目标：**通知栏 / 锁屏 / 耳机线控 / 车机 四处控制入口行为一致，且用户能自己管理**。

## 1. 现状盘点

| 项 | 现状 | 最佳实践 | 结论 |
|---|---|---|---|
| 通知渠道 | ✅ 显式 `tingbili_playback`（名称「播放控制」、IMPORTANCE_LOW、无角标、有 description） | 显式渠道，名字对用户有意义、重要级别不打扰 | 已完成（A-03） |
| 权限被拒 | ✅ 一次性说明 + 「去设置」 | 拒绝后必须解释后果并给入口，不重复打扰 | 已完成（A-03） |
| 权限被用户事后关闭 | ✅ 播放开始时检查 `areNotificationsEnabled()`，静默时弹一次说明（每进程一次） | 播放开始前检查 `areNotificationsEnabled()`，仍然静默就提示一次 | 已完成 |
| 小图标 | ✅ 单色音符 `ic_notification_playback`（`notificationProvider.setSmallIcon`） | 媒体通知应用**单色品牌图标**，锁屏/状态栏才认得出来 | 已完成 |
| 服务可被系统绑定 | ✅ `exported="true"` + `onConnect` 只接受自家/系统信任控制器 | 系统 UI/蓝牙/车机/媒体恢复需要绑定 `MediaSessionService`，官方示例是 `exported="true"` | 已完成 |
| 硬件媒体键（未起播时） | ✅ 声明 `androidx.media3.session.MediaButtonReceiver`，系统按键已实测路由到会话 | 声明 media3 `MediaButtonReceiver`，耳机按键能唤起服务 | 已完成 |
| 通知点击落点 | ⚠️ 回 App 根页（上次所在 tab） | 点播放控制通知应直接进**播放页** | P1（下一轮） |
| 通知动作 | ✅ 上一首/播放暂停/下一首（Media3 默认，`actions=3`） | 保持默认三键，不自定义 | 保持 |
| 常驻不可清除 | ✅ `NO_CLEAR`（播放中不可滑掉） | 播放中常驻、暂停后可清除 | 保持 |
| 锁屏可见性 | ✅ `VISIBILITY_PUBLIC`（Media3 默认） | 展示曲名不泄露敏感信息 | 保持 |
| 播完/退出清理 | ⚠️ 依赖 Media3 默认 `onTaskRemoved` | 未在播时停止前台服务，避免残留 FGS 占用 | 观察，必要时显式处理 |
| 车机/蓝牙断连后恢复 | ❌ 未实现 `onPlaybackResumption` | 系统媒体恢复（重启/断连后）能回到上次队列 | P2（算力/收益不划算，记录） |

## 2. 方案（分优先级）

### P0 已实现（2026-11-01）

1. **单色通知小图标**：`core:media/src/main/res/drawable/ic_notification_playback.xml`（24dp 白色音符），`DefaultMediaNotificationProvider.Builder.setSmallIcon(...)`。
2. **服务可被系统绑定但只信任自己人**：`android:exported="true"` + `MediaSession.Callback.onConnect` 过滤：只接受**本应用进程**与 `MediaSessionManager.isTrustedForMediaControl` 的系统控制器（SystemUI/蓝牙/车机），其余拒绝。
3. **硬件媒体键**：声明 `androidx.media3.session.MediaButtonReceiver`（`MEDIA_BUTTON` intent-filter），服务未起时按键能拉起来。
4. **事后关闭通知也要提示**：播放开始时若 `areNotificationsEnabled() == false` 且本进程未提示过，弹同一个说明框（复用 A-03 的对话框）。

### P1 已实现并真机验证（2026-11-01）

5. **通知点击直达播放页**：`sessionActivity` 的 Intent 带 `EXTRA_OPEN_PLAYER`（`FLAG_ACTIVITY_SINGLE_TOP|CLEAR_TOP`）→ `MainActivity.onCreate/onNewIntent` 读到后置 `openPlayerRequest` → 透传 `AdaptiveMainScaffold → MainTabsScaffold → AppRootNavHost` → 在 **MainTabsRoute 内容内部**的 `LaunchedEffect` 里 `navigate(PlayerRoute)` 并复位标记。
   - 两个坑（都实测踩过）：
     1. effect 放在 `NavHost` **之前**时导航图还没挂上，`navigate` 失败；必须放进 Tab 容器内部。
     2. 把布尔当 Composable 参数一路传下去时，`NavHost` 的 destination lambda 会**捕获旧值**——冷启动能跳、热启动（onNewIntent）不跳。改法：标记放 `MainViewModel.openPlayerRequest`，在 destination 内容里 `collectAsStateWithLifecycle()` 消费。
   - 验证：冷启动带 extra → 播放页；App 在后台时投递同签名 intent（等价通知点击）→ 播放页。

### P2 记录不实现

6. `onPlaybackResumption`（车机/重启媒体恢复）：需要持久化队列 + 云端校验，单人使用场景收益低。
7. 自定义通知按钮/进度条：Media3 默认三键已够，自定义要和 MediaSession 命令同步，回归面大。

## 3. 安全边界

`exported="true"` 不等于对所有人开放：

- `MediaSession.Callback.onConnect` 里判断 `controller.packageName == packageName`（自家）或 `isTrustedForMediaControl`（系统 UI/蓝牙/车机）；
- 被拒绝的控制器只能拿到空会话，无法播放/跳转/改队列；
- 通知点击用的是显式 PendingIntent（`FLAG_IMMUTABLE`），不可被第三方伪造。

## 4. 验证方法

```bash
# 渠道与图标
adb shell dumpsys notification --noredact | Select-String 'tingbili_playback|icon=Icon.*id='
# 硬件媒体键（App 在后台）
adb shell input keyevent KEYCODE_MEDIA_PAUSE ; adb shell dumpsys media_session | Select-String 'state=PlaybackState'
# 事后关权限
adb shell pm revoke cn.debubu.tingbili android.permission.POST_NOTIFICATIONS
# 通知点击落点（P1）
 adb shell cmd statusbar expand-notifications # 实际点击需人工或用 MediaSession launchIntent 验证
```

## 5. 落地验证（真机 2311DRK48C，2026-11-01）

| 验证项 | 命令 | 结果 |
|---|---|---|
| 渠道 | `dumpsys notification --noredact` | `channel=tingbili_playback`；渠道实体 `mName=播放控制`、`mImportance=2`、`mShowBadge=false`、`hasDescription` |
| 小图标 | 同上 `icon=Icon(typ=RESOURCE pkg=cn.debubu.tingbili id=0x7f06000a)` | 从 Media3 默认图标（0x7f060055）换成自己的单色音符；通知 `category=transport actions=3 NO_CLEAR` 不变 |
| 硬件媒体键 | `input keyevent KEYCODE_MEDIA_PAUSE/PLAY`（App 在后台） | `PAUSED → PLAYING`；logcat 见 `MediaSessionService: Sending KeyEvent ... to cn.debubu.tingbili/androidx.media3.session.id./456` |
| 通知被事后关闭 | `pm revoke POST_NOTIFICATIONS` → 起播 | 播放正常（`PLAYING`），同时弹出「通知权限已关闭」说明（uiautomator 抓到标题+两按钮） |
| 权限恢复 | `pm grant` → 重启 | 不再弹框，正常落首屏 |
| 会话白名单 | 自家 `MediaController` 连接 | 播放/暂停/切歌全部正常，说明未误伤自家控制器 |
