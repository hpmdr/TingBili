package cn.debubu.tingbili.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import cn.debubu.tingbili.feature.history.HistoryScreen
import cn.debubu.tingbili.feature.home.HomeScreen
import cn.debubu.tingbili.feature.mine.MeScreen
import cn.debubu.tingbili.feature.playlist.PlaylistScreen

/**
 * 主页 4 个 Tab 的唯一容器：横向分页，跟手左右滑切换（微信式）。
 *
 * - 底栏不在 pager 内，滑动只动内容区；
 * - 点击底栏用 scrollToPage（瞬时，符合 M3“同层级视图不做转场动画”），
 *   手势拖动才产生横移动画；
 * - 相邻页常驻（beyondViewportPageCount=1），来回滑不重建；
 *   更远的页销毁后由各页的 rememberSaveable 恢复滚动位置。
 */
@Composable
fun MainTabsPager(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
    ) { page ->
        when (page) {
            0 -> ImageFormatScope(mainViewModel) {
                // 搜索已内嵌到首页（顶部输入框 → 首页网格出结果）
                HomeScreen(
                    onTrackToDetail = { bvid -> navController.navigate(VideoDetailRoute(bvid)) },
                    onUpClick = { mid -> navController.navigate(UpSpaceRoute(mid)) }
                )
            }

            1 -> ImageFormatScope(mainViewModel) {
                PlaylistScreen(onPlaylistClick = { id -> navController.navigate(PlaylistDetailRoute(id)) })
            }

            2 -> ImageFormatScope(mainViewModel) { HistoryScreen() }

            else -> ImageFormatScope(mainViewModel) {
                MeScreen(
                    onOpenAppearance = { navController.navigate(AppearanceRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenStorage = { navController.navigate(StorageRoute) },
                    onOpenGeneral = { navController.navigate(GeneralRoute) },
                    onOpenBiliFav = { navController.navigate(BiliFavFoldersRoute) },
                    onOpenBiliHistory = { navController.navigate(BiliHistoryRoute) },
                    onOpenBiliToview = { navController.navigate(BiliToviewRoute) },
                    onOpenLogin = { navController.navigate(LoginRoute) { launchSingleTop = true } },
                )
            }
        }
    }
}
