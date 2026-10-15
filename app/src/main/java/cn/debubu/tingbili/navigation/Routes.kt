package cn.debubu.tingbili.navigation

import kotlinx.serialization.Serializable

/** 主页 Tab 容器（带底栏） */
@Serializable
object MainTabsRoute

@Serializable
object HomeRoute

@Serializable
object PlaylistRoute

@Serializable
object HistoryRoute

/** 「我的」Tab：用户信息 + 外观/设置入口 */
@Serializable
object MeRoute

/** 二级页：外观设置（主题色 / UI 选项） */
@Serializable
object AppearanceRoute

/** 二级页：常用设置（播放 / 音频） */
@Serializable
object SettingsRoute

/** 二级页：其他设置（图片格式 / 音频缓存） */
@Serializable
object OtherSettingsRoute

/** 独立全屏页：搜索 */
@Serializable
object SearchRoute

/** 二级页：B 站 Web 扫码登录 + Cookie 导入 */
@Serializable
object LoginRoute

@Serializable
object PlayerRoute

/** 视频详情页路由，携带 BV 号 */
@Serializable
data class VideoDetailRoute(val bvid: String)

/** 收藏详情页路由，携带收藏 id */
@Serializable
data class PlaylistDetailRoute(val playlistId: Long)

/** 主页 4 Tab 的集合，用于判断是否显示底栏 */
fun isMainTabRoute(route: Any?): Boolean = when (route) {
    is HomeRoute, is PlaylistRoute, is HistoryRoute, is MeRoute, is MainTabsRoute -> true
    else -> false
}
