# 设置页 IA（2026-10-25，四页重排）

我的 Tab 四个设置入口：外观设置 / 播放设置 / 存储与缓存 / 通用设置。

- 外观（`AppearanceScreen`）：深色模式 / 深色风格 / 主题色。
- 播放（`SettingsScreen`）：步长 / 字幕字号 / 睡眠偏好（到点后播完本章再停默认）/ 解码开关 / 本机解码器入口 / 默认音质入口。
  `QualityScreen` 唯一父入口是播放设置；`AudioDecodersScreen` 同上。
- 存储与缓存（`StorageSettingsScreen` + `StorageSettingsViewModel`，单页）：用量总览 / 分别清理（音频/图片/字幕）/ 音频上限 / 全部清空。
- 通用（`GeneralSettingsScreen` + `GeneralSettingsViewModel`）：B 站图片格式 / 首页推荐词 / 关于。
- 已删除：`OtherSettings*`（名实不符的中转页）、`CacheRoute`（合并进存储单页）。

有意没做的：

- `repeatMode` 仍是 DataStore 死键（无处读取），本次未接线；要加循环模式需新接播放器，另立项。
- 倍速完整控制与睡眠定时倒计时仍在播放页，设置页只放偏好默认（`sleepFinishChapter`）。