# TingBili 音频管线设计（DSP offload / 硬解 / 软解三级策略）

> 面向技术审核的纸面材料。实现映射见 §5；行为变更必须同步更新本文。
> 官方口径以 Android Developers（Media3 参考 + 电池/选轨指南）为准；
> SoC 差异 AOSP 未统一暴露，一律运行时探测，不写死厂商分支。

## 1. 背景与目标

- TingBili 是纯音频、锁屏/后台长播应用。官方明确指出：短音频或亮屏播放时音频省电可忽略，
  长播放 + 灭屏才值得做功耗优化。本应用正好落在高收益区。
- 目标 Already：按“为用户省资源”排序解码路径，不兼容时智能降级、不断播，CPU 软解永远兜底。

## 2. 三级路径（官方语义）

### L0 — DSP Audio Offload（功耗最低）

- 意愿三档：`AUDIO_OFFLOAD_MODE_DISABLED`（不考虑 offload 做选轨）、
  `ENABLED`（所选轨道与 renderer 兼容就启用）、`REQUIRED`（组不出兼容组合就无轨可选=无声）。
  本应用只用 `ENABLED`，禁用 `REQUIRED`（省电不能以无声为代价）。
- 两个约束的官方语义：`isGaplessSupportRequired=true` 时，只有设备支持 offload 下无缝
  或当前音频本来就不是 gapless 才启用；`isSpeedChangeSupportRequired=true` 时，
  只有设备支持 offload 下变速才启用。本应用配 `gapless=true / speedChange=false`：
  章节衔接不断、变速时自动回落不断播。
- 下发位置是 `TrackSelectionParameters.AudioOffloadPreferences`，播放前必须下发；
  是否真正生效取决于 renderer + 所选轨道组合，`AudioOffloadListener.onOffloadedPlayback /
  onSleepingForOffload` 是唯一可信的“真进 DSP”信号（本应用已打点到
  `AudioPipelineMonitor`，logcat 搜 `TingBiliAudio`）。

### L1 — 硬件 MediaCodec（功耗居中）

- 从 Android 10 起用 `MediaCodecInfo.isHardwareAccelerated / isSoftwareOnly / isVendor`
  区分加速/纯软/vendor 属性；本应用选择器只按这三个标志排序，不写死厂商字符串。
- 高通（`OMX.qcom.*` / `c2.qti.*`）、联发科（`OMX.MTK.*` / `c2.mtk.*`）各有 vendor
  解码器，`OMX.google.*` / `c2.android.*` 为平台软解。厂商前缀只做日志定位，
  不做分支依据（两家都出过真实初始化/解码失败个案）。

### L2 — CPU 软解 + 扩展解码器（兼容性兜底，功耗最高）

- `DefaultRenderersFactory.setEnableDecoderFallback(true)`：解码器初始化失败时向低
  优先级解码器回落。本应用选择器“硬解在前、软解在后”的列表顺序依赖该机制，必须显式开启并锁单测。
- FFmpeg 扩展解码器（`EXTENSION_RENDERER_MODE_ON`=core 优先，`PREFER`=extension 优先）
  目前不引入：B 站音频（AAC/MP3 渐进式）平台自带解码器，引入 SO 包的体积与合规成本不划算。
  将来若出现平台缺失格式再评估，且只用 `ON`。

### 明确不做

- Tunneling：官方限定为“部分 TV 上的高分辨率视频”路径，纯音频应用不碰。
- `REQUIRED` offload：阉割机上会无声，禁用。

## 3. 高通 vs 联发科差异（运行时探测，不写死）

| 维度 | 高通 Snapdragon | 联发科 Dimensity | 对本应用的含义 |
|---|---|---|---|
| 硬件音频解码器 | 中高端 `c2.qti.*` 覆盖 AAC-LC/MP3 常见格式 | 同级 `c2.mtk.*`；低端机缺 HE-AAC 硬解概率更高 | 不能假设有 HW；无 HW 时原样返回，天然落到 L2 |
| DSP offload | 靠 Audio HAL + ADSP，小厂机可能阉掉 | 靠 AFE/DSP，低端/出海机可能阉掉 gapless offload | `ENABLED` 永远安全（不兼容就不走 DSP） |
| 蓝牙 A2DP | 有独立硬件 offload 开关，机型行为不一；有过骁龙公版 offload 只出左声道的个案 | LDAC/LHPC 走硬还是软各机型不一 | 蓝牙输出时不指望 DSP，只保证不断播 |
| 已知坑位 | `OMX.qcom.*` 初始化失败个案 | `OMX.MTK.*` 解码失败个案、HE-AAC 无缝衔接抱怨更多 | L1→L2 回落默认开 + 解码器名打日志 |

## 4. 优先级状态机（审核口径）

```
L0 DSP offload（ENABLED + gapless要求开 + 变速要求关）
 │  任一不满足 → 静默降 L1（不断播）
 ├─ 用户关 offload 开关
 ├─ 倍速 != 1.0（offload 开启时直接提示不支持倍速，见 §6）
 ├─ 蓝牙 A2DP 输出 / 有 AudioEffect / DRM / 非白名单格式
 └─ onOffloadedPlayback=false（renderer 明确没走 DSP）
L1 硬件 MediaCodec（vendor HW 优先，audio/ 限域）
 │  初始化失败 → 按序自动试下一个（decoderFallback=true）
 └─ 本机无 HW（列表全软解）→ 原样返回，天然就是 L2
L2 CPU 软解（OMX.google.* / c2.android.*）
    永远有声：这是兜底，不再降级；失败则走 Result.Error/UiState，不抛堆栈到界面
```

承诺口径：只承诺“相对排序 + 自动降级”，不承诺具体省电百分比
（官方自己也说因硬件/系统/内容而异，只作指导）。

## 5. 本仓库实现映射

| 决策 | 落点 |
|---|---|
| 音频限域硬解重排（HW > 未知 > SW，同组保系统原序） | `core/media/.../PreferHardwareAudioDecoderSelector.kt` |
| 开关实时镜像（硬解下一首生效；offload 由 Service 实时下发，下一首生效） | core/media/.../AudioPipelinePolicy.kt（镜像收集）+ PlayerManager.kt（facade 转发）+ TingBiliPlaybackService.kt |
| offload 意愿（ENABLED/gapless 开/变速关）+ decoderFallback 显式开 | `core/media/.../ServicePlayerModule.kt` |
| 真进 DSP / 真实解码器名 / 音频格式打点 | `TingBiliPlaybackService` → `AudioPipelineMonitor` |
| 两开关默认开（§6 决策 1；开关住播放与音频设置） | `PreferencesRepository`（缺省 `true`）+ `feature/mine/.../SettingsScreen.kt` / `SettingsViewModel.kt` |
| 音质偏好/面板/qn 下发/rank 精选（详见 AUDIO_QUALITY.md） | `data/bilibili/.../AudioQuality.kt` + `BiliRepository.getPlayUrl/getAudioOptions` + `PlayerManager.refreshCurrentUrl` + `feature/player/.../PlayerViewModel+PlayerScreen` |
| offload 开启时禁用倍速（§6 决策 4） | AudioPipelinePolicy.requestSpeed 决策 + PlayerManager.setSpeed 报提示 |
| 播放页右上 ⋮ → 播放信息（解码器/方式/码率/采样率/声道/offload 态） | `feature/player/.../PlayerScreen.kt` + `PlayerViewModel.pipelineInfo` |
| 其他设置 → 本机音频解码器独立页（`AudioDecodersRoute`） | `core/media/.../AudioDecoderInspector.kt` + `feature/mine/.../AudioDecodersScreen.kt` / `AudioDecodersViewModel.kt` |

## 6. 已拍板的产品决策（2026-10-24）

1. `优先硬件解码` 与 `DSP 音频卸载` 默认开。
2. Offload 运行时生效（Service 侧监听开关 + 下一首生效），不再要求重启 App。
3. 先落本文档再动手。
4. 倍速不是刚需：offload 开启时倍速入口直接提示“DSP 省电开启时不支持倍速”，保持 1.0x。
5. 播放页右上 ⋮ 增加“播放信息”。
6. 其他设置增加“本机音频解码器”实测列表。

## 7. 验证矩阵（审核演示用）

| 场景 | 预期 | 取证 |
|---|---|---|
| 骁龙 8 系 + 有线/扬声器 + 1.0x + offload 开 | `audioDecoder=c2.qti.*`（或 `OMX.qcom.audio.*`），`sleepingForOffload=true` | `adb logcat -s TingBiliAudio` + 播放信息页 |
| 天玑 9xxx 同条件 | `c2.mtk.*`；低端 MTK 允许回落 HW/SW | 同上 |
| offload 开 + 点倍速 | Toast/错误提示“DSP 省电开启时不支持倍速”，保持 1.0x | 录屏 |
| 任一机型 1.5x（offload 关） | 正常变速，不要求休眠 | 日志 `audioDecoder=...` 不中断 |
| 蓝牙耳机 | 不要求休眠，只要求不断播、无声道异常 | 试听记录 |
| 破坏测试：HW 初始化失败 / 无 HW 机型 / 关 offload / 来电打断 | 每次都有声 | `DecoderFallbackTest` 单测 + 真机记录 |

## 8. 参考

- `https://developer.android.com/reference/androidx/media3/common/TrackSelectionParameters.AudioOffloadPreferences`
- `https://developer.android.com/media/media3/exoplayer/battery-consumption`
- `https://developer.android.com/media/media3/exoplayer/track-selection`
- `https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory`
- `https://developer.android.com/reference/android/media/MediaCodecInfo`

## 9. 真机实测记录（2026-10-24，重构后回归）

包：app-debug.apk（5948080 之后）；两台红米测试机，同一 B 站 AAC 内容（mp4a.40.2 / 64kbps / 44.1kHz / 双声道）。

| 机型 | SoC | 解码器（播放信息页） | DSP 卸载 | 结论 |
|---|---|---|---|---|
| Redmi 25053RT47C | 骁龙 SM8735（Android 16） | 未初始化（DSP 接管后无 CPU 侧解码器） | 生效中（DSP 接管），sleepingForOffload=true | L0 真进 DSP，符合预期 |
| Redmi K70E 2311DRK48C | 天玑 8300（Android 16） | c2.android.aac.decoder（软解） | 已开启但未接管 | L0 静默降 L2，不断播，符合预期 |

天玑依据：本机音频解码器页共 34 项，有 MTK 硬解（MP3/ALAC/ADPCM/Dolby），唯独无 AAC 硬解。AAC 只有 c2.android.aac.decoder 可选，选软解是正确结果，不是选择器 bug，这正是第 3 节运行时探测的实证。
回归项（两台机）：倍速互斥提示并保持 1.0x / 睡眠定时倒计时 / 杀进程恢复（暂停态）/ 跨页进度同步 / 我的页累计收听分钟数，均正常；logcat 无崩溃、无 PlaybackException。
蓝牙 A2DP（两台机均接蓝牙音箱，dumpsys audio 确认 Devices: bt_a2dp）：骁龙仍 sleepingForOffload=true（高通 BT offload），天玑保持软解 c2.android.aac.decoder；两台 media_session 均为 PLAYING、无 error、进度推进，不断播。
待补：HE-AAC/MP3 硬解路径（需对应格式内容源）。
