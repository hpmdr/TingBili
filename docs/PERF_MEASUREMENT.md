# 性能实测报告（内存 / CPU / 帧率）

> 设备：2311DRK48C（HyperOS，480dpi）；包：**`app-perfDebug.apk`（R8 压缩 + 资源收缩，6.71MB）**；
> 方法：`adb top` / `dumpsys meminfo` / `dumpsys gfxinfo` / `dumpsys cpuinfo` / `dumpsys power` / `dumpsys alarm` / logcat，全部为连续采样（非单点）。
> 时间：2026-11-01 16:20–16:35。

## 1. 内存

| 场景 | TOTAL PSS | Java Heap | Native Heap | Graphics | AppContexts | Views |
|---|---|---|---|---|---|---|
| 冷启动空闲 12s | **119.9 MB** | 17.3 MB | 37.6 MB | 34.7 MB | 6 | 10 |
| 播放中 | 131.8 MB | 14.8 MB | 39.3 MB | 35.0 MB | 6 | 10 |
| 4 轮 tab 切换 | 143.6 MB | — | — | — | 9 | 13 |
| **16 轮 tab 切换** | 146.5 MB | 18.0 MB | 53.0 MB | 35.1 MB | **6** | **13** |
| 触发 `am send-trim-memory RUNNING_LOW` 后 | **129.1 MB** | — | — | — | 6 | 13 |

**结论：无内存泄漏。** 判据不是「PSS 涨了 26MB」，而是：

- 16 轮切换后 PSS 只比 4 轮多 3MB（趋平，不是线性增长）；
- `AppContexts` 回落到 6、`Views` 稳定 13（每轮不累积）；
- 系统回收后掉回 129MB —— 涨的部分是**可回收的图片/位图缓存**，不是泄漏对象；
- GC 频率正常：观察窗口内 3 次显式 GC，Java 堆峰值 33MB、70% 空闲。

## 2. CPU

| 场景 | `top` 采样（5 次） | 结论 |
|---|---|---|
| App 空闲（前台/后台） | 0.0 / 0.0 / 0.0 / 0.0 / 0.0 % | 无后台轮询、无定时器 |
| 播放中（前台） | 14.2 / 14.2 / 22.2 / 7.1 / 11.1 % | 均值 ≈ 13% |
| **播放中（后台/桌面）** | 7.1 / 14.2 / 14.8 / 18.5 / 14.8 % | **均值 ≈ 14%，持续** |
| 暂停后 | 0.0 ×5 | 暂停即停 CPU |

### 发现：音频 offload 在这台机器上**没生效**，所以播放要走软件解码

三条独立证据：

1. 解码器日志：`TingBiliAudio: audioDecoder=c2.android.aac.decoder` → **软件 AAC 解码**（offload 下不应出现 MediaCodec 解码器日志）；
2. 系统音频策略：`EFFECT_CMD_OFFLOAD offload=0`、`EffectModule: setOffloaded_l offloaded 0` → **HAL 拒绝 offload**；原因也打出来了：`isNonOffloadableEffectEnabled: non offloadable effect DAP ... enabled on session 0`；
3. `dumpsys audio`：`Current active effect: dolby` → 系统级杜比音效处于开启状态，而**带非 offload 效果时整条管线不能走 DSP**。

也就是说：应用侧 `audioOffload=true` 的意愿已经正确下发给 ExoPlayer（`TingBiliAudio: audioOffload pref=true`），是**系统音效挡住了 offload**，不是代码 bug。

**影响**：后台播放持续约 14% 单核 CPU（相当于整机 2~4% 功耗），小时级听歌会明显多耗电；关掉系统音效后 offload 才有机会接管。

> 这一条是「性能优化」与「音质偏好」的冲突：杜比音效本身要消耗 CPU，而它又禁止 offload。属于用户可感知但不该由应用单方面决定的事。

## 3. 帧率与启动

| 指标 | 实测 |
|---|---|
| 首页连扫 5 次 | 922 帧，**Janky 0（0.00%）**，50th 6ms / 90th 8ms / 95th 9ms |
| 冷启动 `am start -W`（perfDebug，3 次） | **289 / 313 / 335 ms** |

## 4. 后台行为

| 检查项 | 结果 |
|---|---|
| 唤醒锁 | `dumpsys power`：暂停后 `Wake Locks: size=0`；播放期只有 `ExoPlayer:WakeLockManager`（预期），**无泄漏** |
| 闹钟 / JobScheduler | 无本应用注册的定时任务（只有系统侧 `TopAppTimer` 记账） |
| 网络 | 空闲时 0% CPU，无轮询；Wi-Fi 收发集中在播放/首屏 |

## 5. 结论与建议

**健康项**：内存无泄漏、帧率 0 掉帧、冷启动 < 350ms、暂停即 0% CPU、无后台定时器、无唤醒锁泄漏。

**唯一异常项**：offload 被系统杜比音效阻断 → 后台播放 14% CPU。

可选改进（需产品决策）：

1. **设置页给一句提示**：检测 `AudioOffloadListener` 从未收到 `onOffloadedPlayback(true)` 时，在「音频卸载」开关下加一行「当前系统音效/杜比已开启，卸载无法生效，可在系统声音设置里关闭音效」。应用侧无法自动关闭系统音效。
2. 不做：为省电去强制关效果/换解码策略，会改变音质与兼容性，收益不抵风险。

## 6. 复现命令

```bash
# 内存
adb shell dumpsys meminfo cn.debubu.tingbili | Select-String 'TOTAL PSS|AppContexts|Views'
# CPU（连续采样，别只看一次）
1..5 | % { adb shell top -b -n 1 -o PID,%CPU,NAME | Select-String tingbili }
# 帧率
adb shell dumpsys gfxinfo cn.debubu.tingbili reset ; <滑动若干次> ; adb shell dumpsys gfxinfo cn.debubu.tingbili
# 冷启动
adb shell am force-stop cn.debubu.tingbili ; adb shell am start -W -n cn.debubu.tingbili/.MainActivity
# offload 证据
adb logcat | Select-String 'TingBiliAudio|EFFECT_CMD_OFFLOAD|setOffloaded'
adb shell dumpsys audio | Select-String 'active effect'
```
