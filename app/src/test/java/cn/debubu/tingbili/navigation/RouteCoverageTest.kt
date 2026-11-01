package cn.debubu.tingbili.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 路由守卫（REVIEW_REPORT I-04）：
 * 1) 补齐 NavigationTest 没覆盖的 8 条路由，验证它们能在真实的 Nav 图里解析；
 * 2) 一条元测试：Routes.kt 里新增的 @Serializable 路由必须在测试源码里出现，
 *    否则「改路由必须同步改 NavigationTest」就还只是口头约定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteCoverageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `routes previously missing from navigation test all resolve`() {
        lateinit var navController: TestNavHostController
        composeTestRule.setContent {
            navController = TestNavHostController(LocalContext.current)
            navController.navigatorProvider.addNavigator(ComposeNavigator())
            NavHost(navController = navController, startDestination = HomeRoute) {
                composable<HomeRoute> { Text("home") }
                composable<MainTabsRoute> { Text("main tabs") }
                composable<AppearanceRoute> { Text("appearance") }
                composable<PlayerRoute> { Text("player") }
                composable<UpSpaceRoute> { Text("space") }
                composable<VideoDetailRoute> { Text("detail") }
                composable<FavoriteFolderRoute> { Text("folder") }
                composable<BvFavoriteRoute> { Text("bv fav") }
            }
        }
        composeTestRule.onNodeWithText("home").assertIsDisplayed()

        composeTestRule.runOnIdle { navController.navigate(MainTabsRoute) }
        composeTestRule.onNodeWithText("main tabs").assertIsDisplayed()

        composeTestRule.runOnIdle { navController.navigate(AppearanceRoute) }
        composeTestRule.onNodeWithText("appearance").assertIsDisplayed()

        composeTestRule.runOnIdle { navController.navigate(PlayerRoute) }
        composeTestRule.onNodeWithText("player").assertIsDisplayed()

        composeTestRule.runOnIdle { navController.navigate(UpSpaceRoute(42L)) }
        composeTestRule.onNodeWithText("space").assertIsDisplayed()

        composeTestRule.runOnIdle { navController.navigate(VideoDetailRoute("BV1xx")) }
        composeTestRule.onNodeWithText("detail").assertIsDisplayed()

        composeTestRule.runOnIdle { navController.navigate(FavoriteFolderRoute(7L)) }
        composeTestRule.onNodeWithText("folder").assertIsDisplayed()

        composeTestRule.runOnIdle { navController.navigate(BvFavoriteRoute("BV1xx")) }
        composeTestRule.onNodeWithText("bv fav").assertIsDisplayed()
    }

    @Test
    fun `every route in Routes kt is referenced by some test`() {
        val routesFile = firstFile(
            "src/main/java/cn/debubu/tingbili/navigation/Routes.kt",
            "app/src/main/java/cn/debubu/tingbili/navigation/Routes.kt",
        )
        val testDir = firstDir(
            "src/test/java/cn/debubu/tingbili/navigation",
            "app/src/test/java/cn/debubu/tingbili/navigation",
        )

        val routeNames = Regex("@Serializable\\s+(?:data\\s+)?(?:object|class)\\s+(\\w+)")
            .findAll(routesFile.readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("Routes.kt 里没解析到任何路由，正则可能失效（file=${routesFile.absolutePath}）", routeNames.size >= 15)

        val corpus = testDir.listFiles { f -> f.isFile && f.extension == "kt" }
            .orEmpty()
            .joinToString("\n") { it.readText() }

        val missing = routeNames.filterNot { corpus.contains(it) }
        assertTrue(
            "以下路由在测试源码里没有出现，新增路由必须同步补 NavigationTest/RouteCoverageTest：$missing",
            missing.isEmpty(),
        )
    }

    private fun firstFile(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("找不到文件，候选：${candidates.toList()}，cwd=${File("").absolutePath}")

    private fun firstDir(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("找不到目录，候选：${candidates.toList()}，cwd=${File("").absolutePath}")
}