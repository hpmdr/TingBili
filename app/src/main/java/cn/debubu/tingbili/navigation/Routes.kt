package cn.debubu.tingbili.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
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

/** 二级页：播放设置（步长 / 字幕 / 解码 / 默认音质） */
@Serializable
object SettingsRoute

/** 二级页：其他设置（图片格式 / 音频缓存） */
@Serializable
object OtherSettingsRoute

/** 二级页：本机音频解码器实测列表（MediaCodecList 枚举，仅展示定位用） */
@Serializable
object AudioDecodersRoute

/** 独立全屏页：搜索 */
@Serializable
object SearchRoute

/** 二级页：B 站 Web 扫码登录 + Cookie 导入 */
@Serializable
object LoginRoute

@Serializable
object PlayerRoute

/** 我的页二级页:B站收藏夹列表 */
@Serializable
object BiliFavFoldersRoute

/** 我的页二级页:收藏夹详情 */
@Serializable
data class BiliFavDetailRoute(val mediaId: Long, val title: String = "")

/** 我的页二级页:B站历史 */
@Serializable
object BiliHistoryRoute

/** 我的页二级页:稍后听 */
@Serializable
object BiliToviewRoute

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

/** 主页 Tab 的 destination 判定（底栏/迷你条显隐用）：4 个 Tab + 主容器都算主页，
 * 与 [isMainTabRoute] 同源，改 Tab 时两处一起改，不要在调用方手写 hasRoute 散装判断。 */
fun isMainTabDestination(destination: androidx.navigation.NavDestination?): Boolean =
    destination?.hierarchy?.any {
        it.hasRoute<HomeRoute>() ||
            it.hasRoute<PlaylistRoute>() ||
            it.hasRoute<HistoryRoute>() ||
            it.hasRoute<MeRoute>() ||
            it.hasRoute<MainTabsRoute>()
    } == true
