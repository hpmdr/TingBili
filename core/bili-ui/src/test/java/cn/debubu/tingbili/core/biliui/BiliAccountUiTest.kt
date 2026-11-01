package cn.debubu.tingbili.core.biliui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `core:bili-ui` 此前零测试（REVIEW_REPORT I-03）。
 * 覆盖纯格式化函数与两个最常用的状态组件（错误重试 / 未登录门）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BiliAccountUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `formatSpaceDuration handles zero and formats`() {
        assertEquals("--:--", formatSpaceDuration(0))
        assertEquals("--:--", formatSpaceDuration(-5))
        assertEquals("00:59", formatSpaceDuration(59))
        assertEquals("01:00", formatSpaceDuration(60))
        // 小时不补零（与 B 站时长格式一致）：1:02:03
        assertEquals("1:02:03", formatSpaceDuration(3723))
        assertEquals("10:00:00", formatSpaceDuration(36000))
    }

    @Test
    fun `error box shows message and retry callback fires`() {
        var retried = 0
        composeTestRule.setContent {
            BiliErrorBox(message = "加载失败，请重试", onRetry = { retried++ })
        }
        composeTestRule.onNodeWithText("加载失败，请重试").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `no auth gate shows title and login callback fires`() {
        var loggedIn = 0
        composeTestRule.setContent {
            BiliNoAuthGate(title = "我的收藏", onLogin = { loggedIn++ })
        }
        composeTestRule.onNodeWithText("未登录哔哩哔哩").assertIsDisplayed()
        composeTestRule.onNodeWithText("登录后查看你的B站数据").assertIsDisplayed()
        composeTestRule.onNodeWithText("去登录").performClick()
        assertEquals(1, loggedIn)
    }
}