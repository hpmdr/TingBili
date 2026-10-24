# TingBili 音质档位设计（播放页底部音质入口）

> 对标 PiliPlus 音视频设置中的“默认音质 / 蜂窝网络音质”（见 PiliPlus
> `lib/pages/setting/models/video_settings.dart` 的 `_showAudioQaDialog` 与
> `lib/utils/storage_pref.dart` 的 `defaultAudioQa = AudioQuality.hiRes.code` /
> `defaultAudioQaCellular = AudioQuality.k192.code`）。
> 行为变更必须同步更新本文。

## 1. 档位（code 即 playurl dash.audio.id，也可作 qn 下发）

| 本应用展示 | code | PiliPlus | rank | 门槛 |
|---|---|---|---|---|
| Hi-Res无损 | 30251 | `hiRes` rank 60 | 60 | 需登录·大会员 |
| 杜比全景声 | 30250 / 30255 | `dolby_30250` / `dolby_30255` rank 50 | 50 | 需登录·大会员 |
| 高品质 192K | 30280 | `k192` rank 40 | 40 | 需登录 |
| 标准 132K | 30232 | `k132` rank 30 | 30 | 免登录 |
| 流畅 64K | 30216 | `k64` rank 20 | 20 | 免登录 |

档位定义见 `data/bilibili/.../dto/AudioQuality.kt`（id/rank 与 PiliPlus 一一对应）。
默认偏好 132K：免登录可得的最佳档（PiliPlus WiFi 默认 Hi-Res，本应用是纯音频长播，
默认 Hi-Res 费流量，故取 132K；用户登录后可手动上调）。

## 2. 选择语义（照搬 PiliPlus，一字不改规则）

PiliPlus `VideoUtils.findTargetAudio`（`lib/utils/video_utils.dart`）取
`preferredRank`，经 `findTargetMedia`（`lib/utils/extension/iterable_ext.dart`）：
取 rank 最高且 `rank <= 偏好` 的轨；若偏好低于全列表，取 rank 最低轨兜底。
本应用 `pickAudio`（`dto/AudioQuality.kt`）同语义，另加 rank 并列时取 bandwidth
高者（同档多 CDN 镜像时选高码率）。

请求链双保险：`BiliApi.playUrl` 下发 `qn = 偏好 code`（服务端按登录/会员态鉴权
过滤档位）→ 客户端再按偏好 rank 二次精选（`selectAudio`）。两端都不保证高档
一定存在，保证的是永远有声。

## 3. 登录门槛（诚实展示，不欺骗）

- 面板只列服务端本次返回的可用档（动态、分视频），从高到低，✓ 落在有效档；未登录时底部给“登录解锁更高音质”入口（192K/杜比/Hi-Res），列表里不再出现点不了的档。
- `requiresLogin` 档（192K 及以上）未登录点选：Toast“该音质需登录后使用，请先登录”
  并跳登录页；已登录可照常选（无会员时服务端不给杜比/Hi-Res，自动回落，不断播）。
- 与字幕共用同一登录态（`AuthRepository.sessionFlow`）。

## 4. 切换不断播

`PlayerViewModel.selectAudioQuality`：先落盘偏好 → `PlayerManager.refreshCurrentUrl`
（沿用过期重试的原位 replace 模式：重解析→`replaceMediaItem`→回到原进度→保持播/停态）
→ 面板本地按新偏好重算有效档。失败（无在播/解析失败）Toast 并保留当前播放。

## 5. 落点

| 行为 | 落点 |
|---|---|
| 档位/rank/选择语义 | `data/bilibili/.../dto/AudioQuality.kt`（+ `AudioQualityTest`） |
| qn 下发/偏好精选/可用轨查询 | `BiliApi.playUrl(qn)`、`BiliRepository.getPlayUrl/getAudioOptions` |
| 解析器读偏好 | `BiliMediaResolvers`（`PreferencesRepository.audioQuality`） |
| 偏好存取（默认 132K） | `PreferencesRepository.audioQuality/setAudioQuality` |
| 设置页“默认音质”入口 + 六档说明 | `feature/mine/.../SettingsScreen.kt`（`DefaultQualitySheet`）+ `SettingsViewModel.audioQuality/setAudioQuality` |
| 面板态/登录门/换链触发 | `feature/player/.../PlayerViewModel.kt`（audioQuality/audioOptions/selectAudioQuality/qualityLine） |
| 底部第四入口 + 音质面板 + 播放信息“音质”行 | `feature/player/.../PlayerScreen.kt`（`PlayerAuxBar`/`AudioQualitySheet`/`PlaybackInfoSheet`） |

## 6. 参考

- PiliPlus 音质模型：`https://github.com/bggRGjQaUbCoE/PiliPlus/blob/master/lib/models/common/video/audio_quality.dart`
- PiliPlus 音质设置入口与默认：`https://github.com/bggRGjQaUbCoE/PiliPlus/blob/master/lib/pages/setting/models/video_settings.dart`
  与 `https://github.com/bggRGjQaUbCoE/PiliPlus/blob/master/lib/utils/storage_pref.dart`
- PiliPlus 选轨：`https://github.com/bggRGjQaUbCoE/PiliPlus/blob/master/lib/utils/video_utils.dart`
  与 `https://github.com/bggRGjQaUbCoE/PiliPlus/blob/master/lib/utils/extension/iterable_ext.dart`
- B 站音频 id 速查：`id=30216/30232/30280`（`https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/video/video_zone.md`）
  与杜比/Hi-Res `30250/30251`（同仓库 `playurl.md`）。
