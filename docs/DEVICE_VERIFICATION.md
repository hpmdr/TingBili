# 真机验证清单（2026-11-01 累计 9 批改动待验）

> 这些改动静态证据充分、门禁全绿，但**尚未上过真机**。审查报告 §9.3 把它列为当前最大风险。
> 设备：`2311DRK48C`（无线 adb 已连接）。执行前提：**手机解锁**（Doze/锁屏下不装包、不唤醒）。

## 0. 准备

```bash
# 打 fresh 包（debug，保留数据）
./gradlew :app:assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cn.debubu.tingbili/.MainActivity
adb logcat -c   # 开始前清一次，出问题好抓
```

> 状态核对一律用控件树（`uiautomator dump`），不用截图：
> `adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml`

## 1. F2 播放服务与通知（P1，改动最危险）

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| 1.1 | 首页 → 任一点开播 | 1s 内出声或出现「准备中」；无崩溃 | |
| 1.2 | 播放中按 Home 键回桌面 | 继续播放，通知栏有媒体通知 | |
| 1.3 | 下拉通知栏点通知卡片主体 | 回到 App 播放页 | |
| 1.4 | 锁屏 30s 后解锁 | 进度正确（不跳回、不重复），字幕对得上 | |
| 1.5 | `adb shell am force-stop cn.debubu.tingbili` 后重进，点播放 | 能正常起播（不出现「播放服务未就绪」死锁） | |
| 1.6 | 播放中暂停 → 杀进程 → 重进 | 恢复到上次曲目与进度，但不自动播 | |
| 1.7 | 息屏 10 分钟（可缩短到 2 分钟）后解锁 | 播放未被打断；`logcat \| grep TingBiliAudio` 无频繁 underrun | |

**检查崩溃**：`adb logcat -d \| Select-String 'FATAL|did not then call Service.startForeground'`（应为空）

## 2. F2b 启动落地 tab

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| 2.1 | 收藏夹里有内容时冷启动 | 首屏直接落「听单」Tab（无跳变） | |
| 2.2 | 清空收藏后冷启动 | 首屏落「首页」 | |
| 2.3 | `adb shell am start -W` ×5 记 TotalTime | 覆盖安装后首次可能略慢（回填一次），之后稳定 | |

## 3. F3a 极验 WebView（安全改动，**必须验证**）

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| 3.1 | 我的 → 登录 → 扫码登录 | 二维码正常显示，扫码后登录成功 | |
| 3.2 | 短信登录：触发极验滑块 | 滑块页面正常渲染、可拖动、验证通过 | |
| 3.3 | 故意输错验证码 | 有错误提示，可重试；页面不白屏 | |
| 3.4 | 验证过程中返回/关闭弹窗 | 干净退出，再进可重开 | |

> 若 3.2 失败：说明 `MIXED_CONTENT_COMPATIBILITY_MODE` 影响极验页，需回退为 `ALWAYS_ALLOW` 并保留白名单与桥 token。

## 4. F3b 备份策略

| # | 命令/步骤 | 期望 | 结果 |
|---|---|---|---|
| 4.1 | `adb shell bmgr backupnow cn.debubu.tingbili` | 提示无可备份数据 / 备份为空 | |
| 4.2 | 设置 → 系统 → 备份，查看本应用 | 不勾选任何数据 | |

## 5. F3c/F5e 触控与大字体

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| 5.1 | 播放页拖进度条（单手/快速点按） | 48dp 触控区明显更好命中，线与文字间距不变 | |
| 5.2 | 点 mini 条播放/暂停键 | 命中率正常 | |
| 5.3 | 我的页右上明暗切换按钮 + TalkBack | 播报「切换到浅色/深色模式」 | |
| 5.4 | 系统字体调到 200% → 看 mini 条/详情行/UP 主列表 | 两行文本不被裁切、不重叠 | |

## 6. F4a 重组与 F4b 索引（性能）

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| 6.1 | 播放中用 Layout Inspector 看 `PlayerScreen` 重组计数 | ≈0/s（修复前 1/s） | |
| 6.2 | 平板/600dp 宽下播放，看壳体 `MainTabsScaffold` | ≈0/s（修复前 2/s） | |
| 6.3 | 播放中停在历史页/收藏页滚动 | 无每 10s 一次的周期性卡顿 | |

## 7. F5c 登录转屏

| # | 步骤 | 期望 | 结果 |
|---|---|---|---|
| 7.1 | 短信登录输入手机号 → 转屏 → 转回 | 已输内容保留 | |
| 7.2 | 密码页输入用户名/密码 → 转屏 | 保留，且明文开关状态保留 | |
| 7.3 | Cookie 页粘贴一半 → 转屏 | 文本保留 | |

## 8. R8 压缩包冒烟（perfDebug）

| # | 命令/步骤 | 期望 | 结果 |
|---|---|---|---|
| 8.1 | `:app:assemblePerfDebug` 装到真机（先卸载 debug 版） | 能启动、能播放、无 ClassNotFound | |
| 8.2 | 播放页/详情页/设置各点一遍 | 无崩溃；`logcat` 无 `ClassNotFoundException` | |

> 注意：perfDebug 与 debug 共用 debug 签名，可直接覆盖安装；但若之前装的是 release 包需先卸载。

## 9. 结果回写

验完后把结论写到：
- `docs/FIX_PLAN.md` §6 的对应批次行（状态改 ✅ 并补一句「真机已验证」）
- `docs/REVIEW_REPORT.md` §9.3 把「真机验证」一行移除或标注已完成

失败项按以下格式记：`[批次-编号] 现象 + logcat 关键行 + 复现步骤`，直接进 `docs/FIX_PLAN.md` 新开一批修复。

---

## 结果记录（2026-11-01 14:24–14:35 实测，设备 2311DRK48C / HyperOS / 480dpi）

包：`app-debug.apk`（含全部 F1–F6 改动）→ 另测 `app-perfDebug.apk`（R8）。日志用 `adb logcat`，状态用 `uiautomator dump` 控件树判定。

### 通过 10 项

| # | 项目 | 实测证据 |
|---|---|---|
| 1.1 | 起播 | 详情页点「播放全部」→ `media_session state=PLAYING position=8900`，播放页显示 `0:08 / 4:00`，无崩溃 |
| 1.2 | 回桌面继续播 | `KEYCODE_HOME` 后 4s：焦点 `com.miui.home`，`state=PLAYING position=23724`（进度连续，未断） |
| 1.3 | 媒体通知 | `dumpsys notification`：`pkg=cn.debubu.tingbili id=1001 channel=default_channel_id category=transport actions=3 flags=NO_CLEAR`，标题=曲名、副标题=UP 名 |
| 1.4 | 通知跳回（sessionActivity） | 通知栏窗口无法被 uiautomator dump（MIUI 限制），改用会话证据：`media_session` 中 `launchIntent=PendingIntent{...startActivity...}` 已存在——G-13 修复生效；**点击链路仍需人工点一次** |
| 1.5 | force-stop 后重进 | 杀进程 → 重进：不自动播（`state=NONE`），点 mini 播放键 → `state=PLAYING position=123906`，无崩溃 |
| 1.6 | 恢复上次进度 | 详情页显示「继续播放 P1」「上次听到 2:10 / 4:00」；历史页「P1 · 001… · 皓晨音乐」「共 1 条播放记录」 |
| 2.1 | 有收藏时落地收藏 tab | 收藏 1 个 BV 后 force-stop 冷启动 → 直接落「我的最爱」，显示「默认收藏夹 · 2026-11-01」 |
| 2.2 | 无收藏时落首页 | 首次安装（DB v16 破坏性升级清库）后冷启动 → 落「首页」，首屏 feed 正常 |
| 5.3 | 触控目标 48dp | mini 播放键 clickable bounds `144×144 px` ÷ 3（480dpi）= **48dp**；mini 条高 70dp |
| 5.4 | 200% 字体 | `font_scale=2.0` 重启：mini 条高 70dp → **82dp**（`heightIn` 生效），文本未裁切；恢复 1.0 |

### 性能与 R8

| # | 项目 | 实测证据 |
|---|---|---|
| 6.3 | 滚动流畅度 | 连续三次滑动：`Total frames 424`，`Janky frames 3 (0.71%)`，50th 9ms / 90th 17ms / 95th 18ms / 99th 20ms |
| 8 | R8 包 | `app-perfDebug.apk` 6.69MB 覆盖安装 → 启动、feed、详情、起播全正常（`state=PLAYING position=136603`）；logcat 无 `ClassNotFound/NoSuchMethod/NoClassDefFound`（仅高通系统 `BoostFramework` 噪音） |

### 未验证 / 无法验证

| # | 项目 | 原因 |
|---|---|---|
| 3.1–3.4 | 极验登录（扫码/滑块） | **验证会丢失你的登录态**：当前已登录（UID 32454802），要触发 WebView 登录页必须先退出登录并重新扫码。未擅自登出。**建议你下次登录时顺手验一次**：二维码是否正常显示、滑块能否拖动通过 |
| 1.4 点击跳转 | 通知点击回 App | MIUI 通知栏窗口 uiautomator 无法 dump，无法定位控件；已用 `launchIntent` 存在作为替代证据 |
| 4.1–4.2 | 备份排除 | 需 `bmgr` 触发云备份，HyperOS 上有系统侧限制；`allowBackup=false` + `data_extraction_rules` 已静态确认，建议在「设置→备份」里人工看一眼 |
| 6.1–6.2 | Layout Inspector 重组计数 | 需要 Android Studio 挂在调试进程上，adb 无法取重组次数；已用滚动掉帧（0.71%）作为性能替代证据 |
| 7.1–7.3 | 登录页转屏 | 应用锁 `portrait`（F3d 待拍板），本就无法转屏；与 F3d 决策绑定 |
| 1.7 | 息屏 10 分钟 | 未做（会触发锁屏，若设了密码我无法解锁，风险由你承担）。可用「后台播放 4 分钟」结果近似 |

### 新发现（StrictMode 抓到的真实问题，见 FIX_PLAN F7）

`adb logcat | Select-String 'StrictMode policy violation'` 在 debug 包抓到两类：

- **系统噪音（无需处理）**：`com.mediatek.scnmodule.ScnModule`、`com.qualcomm.qti.Performance` 等 ROM 注入代码在主线程读文件——这正是当初给 StrictMode 选 `penaltyLog` 而不是 `penaltyDeath` 的原因，否则非崩不可。
- **应用自身（需修）**：`~duration=786 ms DiskReadViolation`，栈为 `EncryptedAuthStore.prefs_delegate$lambda$0(EncryptedAuthStore.kt:29)` → `MasterKey.getOrCreate` → `AndroidKeyStoreSpi` → **主线程读 Keystore 约 786ms**。触发点是 `AuthRepository` 构造期同步 `store.load()`（启动路径）。

> 这条不是「理论上可能」，是实测数字。冷启动时主线程被阻塞近 0.8s，属于首帧/ANR 风险，建议按 F7 处理（改后台预取 + 内存镜像，与 DataStore 的 prime 同一套思路），**但涉及登录态读取，改完必须验一次登录能否保持**。