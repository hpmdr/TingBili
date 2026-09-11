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

@Serializable
object SettingsRoute

/** 独立全屏页：搜索 */
@Serializable
object SearchRoute

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
    is HomeRoute, is PlaylistRoute, is HistoryRoute, is SettingsRoute, is MainTabsRoute -> true
    else -> false
}
