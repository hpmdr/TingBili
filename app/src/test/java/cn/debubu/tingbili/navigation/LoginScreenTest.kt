package cn.debubu.tingbili.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import cn.debubu.tingbili.feature.mine.LoginScreen
import cn.debubu.tingbili.feature.mine.LoginViewModel
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 登录页测试：只覆盖零网络路径（空输入校验、非法 Cookie 解析），扫码联调走真机。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun viewModel() = LoginViewModel(
        AuthRepository(
            Json { ignoreUnknownKeys = true },
            object : AuthStore {
                override fun load(): BiliSession? = null
                override fun save(session: BiliSession?) = Unit
            },
        ),
    )

    @Test
    fun `cookie tab rejects empty input`() {
        composeTestRule.setContent {
            LoginScreen(onBack = {}, onSuccess = {}, vm = viewModel(), autoStartQr = false)
        }
        composeTestRule.onNodeWithText("Cookie").performClick()
        composeTestRule.onNodeWithText("保存并验证").performScrollTo().performClick()
        composeTestRule.onNodeWithText("请先粘贴 Cookie").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `cookie tab rejects garbage without network`() {
        composeTestRule.setContent {
            LoginScreen(onBack = {}, onSuccess = {}, vm = viewModel(), autoStartQr = false)
        }
        composeTestRule.onNodeWithText("Cookie").performClick()
        composeTestRule.onNodeWithText("SESSDATA=xxx; bili_jct=yyy; DedeUserID=zzz")
            .performTextInput("hello world")
        composeTestRule.onNodeWithText("保存并验证").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("没找到 SESSDATA", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `cookie tab explains sessdata comes from devtools not document cookie`() {
        composeTestRule.setContent {
            LoginScreen(onBack = {}, onSuccess = {}, vm = viewModel(), autoStartQr = false)
        }
        composeTestRule.onNodeWithText("Cookie").performClick()
        composeTestRule.onNodeWithText("Cookie 导入").performScrollTo().assertIsDisplayed()
        // 普通用户能看懂的复制路径：电脑登录 → F12 → 任意请求 → 右键 Copy value
        composeTestRule.onNodeWithText("任意一个请求", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("复制值", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("整段粘贴", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("手机上取不到", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
