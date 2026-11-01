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

---

## 7. 扩展实测（2026-11-01 16:30–16:55，perfDebug 包）

### 7.1 页面级内存（R8 包，冷启动后逐页前进）

| 页面 | TOTAL PSS | Graphics | Views | 增量 |
|---|---|---|---|---|
| 首页（收藏 tab） | **121 MB** | 35 MB | 9 | — |
| 搜索页（空态） | 119 MB | 35 MB | 11 | −2（首页释放） |
| 搜索结果列表 | **125 MB** | 36 MB | 14 | +6 |
| **BV 详情页** | **171 MB** | **81 MB** | 12 | **+46（Graphics +45）** |
| UP 主页 | 172 MB | 83 MB | 12 | +1 |
| 返回首页后 | 200 MB | 109 MB | 12 | 继续持有 GPU 缓存 |
| 10 轮「详情→返回」 | **220 MB** | **105 MB** | 12 | 与 5 轮相同（**平台期**） |
| 退到后台（UI_HIDDEN） | **107 MB** | **18 MB** | 12 | 缓存全部可回收 |

**结论**：

- 内存增长集中在 **Graphics（GPU 缓冲/硬件位图）**，不是 Java/Native 堆；
- 5 轮与 10 轮往返数值相同（106MB vs 105MB）→ **是缓存填到上限，不是泄漏**；
- 退到后台 Graphics 立即从 105MB 掉到 18MB → 系统层面可回收，无泄漏风险；
- 详情页单页 +45MB 偏大：封面 + 相关推荐封面 + 163 分P 列表（lazy，只组合可见项）+ Compose 过渡层缓冲。低端 4GB 机器会更早触发回收，值得做「图片内存缓存上限」。

### 7.2 debug 包 vs R8 包（同一台机、同场景）

| 构建 | 冷启动首页 | 详情页 |
|---|---|---|
| debug（无 R8） | **225 MB** | 292 MB |
| perfDebug（R8 压缩） | **121 MB** | 171 MB |

> 结论：**内存结论只能看 R8 包**。debug 包 Java 堆/Compose 调试开销会放大 ~1.8 倍，之前用 debug 测出的 224MB 不能当成用户侧数据。

### 7.3 为什么 AAC 只有软解（已查实）

设备 `MediaCodecList` 实测（临时打点，已回滚）：

```
c2.mtk.mp3.decoder            | hw=true  | sw=false | vendor=true   (audio/mpeg)
OMX.MTK.AUDIO.DECODER.MP3     | hw=true  | sw=false | vendor=true   (audio/mpeg)
c2.android.aac.decoder        | hw=false | sw=true  | vendor=false  (audio/mp4a-latm)
OMX.google.aac.decoder        | hw=false | sw=true  | vendor=false  (audio/mp4a-latm)
```

vendor 编解码器清单（`/vendor/etc/media_codecs*.xml`）同样显示：MTK 只注册了 **mp3 / alac / wma / adpcm** 的 `c2.mtk.*` 解码器，**没有 AAC 的 MTK 解码器**。

**这不是小米漏适配某个符号，而是 Android 的省电模型决定的**：

- MediaCodec 里的「硬件解码器」主要服务于视频；音频的省电路径是 **audio offload**（把压缩码流直接交给 DSP/ADSP，CPU 不参与解码）；
- 厂商通常只为少数格式注册音频硬解（此机是 MP3 / ALAC / WMA），AAC 一律走 offload 或 CPU 软解；
- 所以「关掉杜比后仍然软解」是**预期行为**——AAC 在这台机器上根本没有 MediaCodec 硬解可用，能省电的只有 offload。

### 7.4 offload 为什么仍然不生效（更新结论）

| 证据 | 结果 |
|---|---|
| `/vendor/etc/audio_policy_configuration.xml` | 存在 `compress_offload` mixPort（COMPRESS_OFFLOAD / DIRECT / GAPLESS_OFFLOAD）→ **平台支持** |
| 播放时 HAL 日志 | `EffectMiSound: MiSound_Command EFFECT_CMD_OFFLOAD` + `setOffloaded_l offloaded 0` → **小米 MiSound 音效仍在下发「禁止 offload」** |
| `dumpsys audio` | `Current active effect: none`（杜比已关，但 MiSound 属于另一条通路） |
| `AudioManager.getAudioOffloadSupport()` | 反射抛 `NoSuchMethodException` → 该 ROM 改过 framework（旁证，不影响 media3 判断） |

**结论**：关闭「杜比」不够，还需要关掉 MIUI 的「音效 / 耳机音效（MiSound）」总开关；若关掉后仍 `offloaded 0`，则属小米对三方应用的 offload 限制（常见策略），应用侧无法绕过。

## 8. 可选优化建议（未实施）

| 项 | 依据 | 预期收益 | 风险 |
|---|---|---|---|
| 给 Coil 内存缓存设显式上限 | 前台 Graphics 峰值 105MB，缓存填满才停 | 低端机峰值内存降 30~60MB | 滚动时图片重解码，略增 CPU |
| 详情页相关推荐用小档封面 | 详情页单页 +46MB | 详情页 −10~20MB | 视觉略糊 |
| offload 未生效时在设置页提示 | MiSound/杜比会静默阻止 offload | 用户可自主选择关音效换续航 | 需要检测 offload 是否真正生效 |

## 9. 补充：图片缓存与封面尺寸（2026-11-01 19:00）

### 9.1 Coil 内存缓存默认值

- 版本：**Coil 3.6.3**；我们只自定义了磁盘缓存（`ImageLoader.Builder.diskCache`），**没有显式设置内存缓存**。
- Coil 3 默认内存缓存 = `maxSizePercent(0.25)`，即 **应用可用内存的 25%**（按 `Runtime.maxMemory()` 计算）。
- 本机 `dalvik.vm.heapsize = 512m` → 默认上限约 **128MB**。这与实测 Graphics 平台期（~105MB）同量级，基本可以认定详情页那 +45MB 的 Graphics 主要就是**图片内存缓存被填满 + 页面切换缓冲**，不是单张封面。

> 若要压峰值：给 `ImageLoader` 显式设 `memoryCache { maxSizeBytes(64.memory) }`（或 `maxSizePercent(0.1)`），代价是滚动时少量重解码。

### 9.2 封面尺寸：详情页与播放页一致，单张只有 2MB

| 位置 | 档位 | 尺寸 | ARGB_8888 大小 |
|---|---|---|---|
| 详情页主封面 | `LargeCover` | 960×540 | **≈2.07MB** |
| 播放页主封面 | `LargeCover` | 960×540 | ≈2.07MB |
| 详情页相关推荐封面 | `ListThumb` | 480×270 | ≈0.52MB |
| UP 头像 | `Avatar` | 96×96 | ≈0.04MB |

所以「详情页 +45MB」不可能是一张封面造成的：单张 2MB，相关推荐可见项几张 0.5MB，剩余全是缓存填充与 Compose/SurfaceFlinger 的缓冲（全屏 1220×2712×4 ≈ 13MB/张）。

### 9.3 音效改「原声」后 offload 仍未生效

用户操作后确认：`UiAutomator` 抓到的 MiSound 页面文本为「音效选择 → **原声**」；HAL 侧 `MisoundEnable=0`。

但播放时日志仍是：

```
DlbVolumeListener: effectCommand :: Command EFFECT_CMD_OFFLOAD offload=0 ioHandle=21
EffectModule: setOffloaded_l offloaded 0 io 21 status 0
EffectMiSound: MiSound_Command EFFECT_CMD_OFFLOAD
TingBiliAudio: audioDecoder=c2.android.aac.decoder
```

即：**效果模块仍挂在输出流上并否决 offload**（原声 ≠ 移除效果模块）。CPU 实测 18.5%~29.6%（5 次采样），未下降。

**更正 §7.4 一处**：之前说「`AudioManager.getAudioOffloadSupport()` 反射不到」是我用错了签名——media3 在 SDK≥33 上用的是**两参重载** `getAudioOffloadSupport(AudioFormat, AudioAttributes)`，我反射的是一参版本。这条不能作为「ROM 删了 API」的证据，撤回。

**结论**：offload 被小米音频效果链阻塞，属系统侧限制；应用侧已正确下发 `AudioOffloadPreferences.ENABLED`。若要进一步确认，可做对照实验：用第三方播放器播同一首歌，看 HAL 是否同样 `offloaded 0`。

## 10. 结论：AAC「无硬解」是平台常态，不是本机/本应用缺陷（2026-11-01 19:10）

### 10.1 本机 SoC

```
ro.soc.model       = MT6897        (MediaTek Dimensity 8300)
ro.soc.manufacturer= Mediatek
```

### 10.2 联发科官方规格页没有「音频解码」这一项

抓取并比对了两代产品页：

- `mediatek.com/products/smartphones/mediatek-dimensity-8300`
- `mediatek.com/products/smartphones/mediatek-dimensity-9400-plus`

两页的规格表只列：CPU/GPU、**视频解码（HEVC/AVC/VP9/AV1）**、视频编码、显示、相机、AI、连接。**没有任何 Audio codec / 音频硬解 / AAC 条目**。

这正好解释了实测现象：音频硬解不由 SoC 规格承诺，MediaCodec 里的音频解码器由厂商按需注册（本机注册了 MP3/ALAC/WMA，没有 AAC），AAC 由 AOSP 软件解码器承担。

### 10.3 跨设备验证

用户另一台 **天玑 9400+** 手机播放同一类内容**同样只有软解**。结合 10.2，可确认：

> **Android 上「AAC 走 CPU 软解」是正常形态；省电只能靠 audio offload（把压缩流交给 DSP），而不是 MediaCodec 硬解。** 本机与 9400+ 表现一致，说明这不是小米/联发科对某个型号的缺陷，更不是 TingBili 的代码问题。

### 10.4 对本项目的可行动项

| 能做 | 说明 |
|---|---|
| 给 Coil 内存缓存设上限 | 压前台峰值内存（§9.1） |
| 设置页提示 offload 未生效 | 当前被 MiSound 效果链阻塞（§9.3），提示用户可关系统音效 |
| 不做 | 无法通过应用代码让 AAC 走「硬解」（硬件路径不存在）；也无法绕过 ROM 对 offload 的 veto |

## 11. 跨机型对照：高通能 offload、MTK 不能（2026-11-01 19:20）

### 11.1 用户侧观察

- 另一台**红米高通机型**：即使开着杜比全景声，也能 DSP offload；
- **多台红米联发科机型**（含本机 D8300、另一台 D9400+）：全部无法 offload，只能软解。

### 11.2 本机更细的证据（`dumpsys media.audio_flinger`）

我们播放会话（session 15497）上挂着**一个效果**：

```
Effect ID 18979
  UUID: 40f66c8b-5aa5-4345-8919-53ec431aaa98
  name: Music Listener
  implementor: Dolby Laboratories
  flags: 00C00290 (conn. mode: insert, insert pref: last, ..., offloadable)  ← 框架层标了 offloadable
  Session State: 15497 ... enabled = y
```

全局（session 0）另有：`Effect ID 43: implementor: MiSound Audio Effect`。

配置文件层面：`/vendor/etc/audio_effects.xml` **确实声明了** `<library name="proxy" path="libeffectproxy.so"/>`（Android 的「可 offload 效果」代理机制），也就是说框架具备「效果跑在 DSP 里、同时 offload」的能力。

但 HAL 实际下发的是：`EFFECT_CMD_OFFLOAD offload=0` → `setOffloaded_l offloaded 0`，即**效果链自报不支持压缩直通**。

### 11.3 为什么高通可以、联发科不行（机制推断）

| 环节 | 高通机型（可 offload） | 本机 MTK 机型（不可） |
|---|---|---|
| 效果位置 | Dolby 处理跑在 **ADSP**，通过 effectProxy 代理 | MiSound/Dolby 监听器实现在 **AP 侧**（`libmisoundfx.so` / `libswdap.so`） |
| EFFECT_CMD_OFFLOAD 回应 | 声明支持 → HAL 允许压缩直通 | 返回 0 → HAL 关闭 offload |
| 结果 | 效果 + offload 并存 | 只能 PCM 直通 + CPU 软解 |

> 关键点：**「原声」只关掉音频处理，不卸载效果模块**。只要模块挂在播放流上且不支持 offload，HAL 就禁 offload —— 这解释了为什么关杜比/选原声都无效。

### 11.4 对 TingBili 的结论

- 这是 **ROM/平台效果链实现差异**，不是芯片没有 DSP（两家都有），也不是应用可配置项；
- 应用侧唯一能做的是**检测 + 告知**：`ExoPlayer.AudioOffloadListener` 若在一首歌内始终没有 `onOffloadedPlayback(true)`，说明本机 offload 不可用，可在设置页显示一行说明（避免用户误以为开关无效）。
