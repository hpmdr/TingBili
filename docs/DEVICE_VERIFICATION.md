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