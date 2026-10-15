package cn.debubu.tingbili.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cn.debubu.tingbili.data.bilibili.auth.AuthRepository
import cn.debubu.tingbili.data.bilibili.auth.AuthStore
import cn.debubu.tingbili.data.bilibili.auth.BiliSession
import cn.debubu.tingbili.feature.settings.LoginScreen
import cn.debubu.tingbili.feature.settings.LoginViewModel
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
        composeTestRule.onNodeWithText("Cookie 导入").performClick()
        composeTestRule.onNodeWithText("保存并验证").performClick()
        composeTestRule.onNodeWithText("请先粘贴 Cookie").assertIsDisplayed()
    }

    @Test
    fun `cookie tab rejects garbage without network`() {
        composeTestRule.setContent {
            LoginScreen(onBack = {}, onSuccess = {}, vm = viewModel(), autoStartQr = false)
        }
        composeTestRule.onNodeWithText("Cookie 导入").performClick()
        composeTestRule.onNodeWithText("SESSDATA=xxx; bili_jct=yyy; DedeUserID=zzz")
            .performTextInput("hello world")
        composeTestRule.onNodeWithText("保存并验证").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("没找到 SESSDATA / DedeUserID，请粘贴完整 Cookie")
            .assertIsDisplayed()
    }
}
