package cn.debubu.tingbili.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.debubu.tingbili.core.data.model.Track
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun demoTrack() = Track(
        bvid = "BV1xx",
        cid = 1L,
        title = "P1 · 测试章节",
        author = "测试UP",
        cover = "",
        durationMs = 60_000L,
        subtitleUrl = null,
        pageCount = 3,
        videoTitle = "测试书籍",
        pageIndex = 1
    )

    @Test
    fun `mini bar renders with track`() {
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = true,
                isLoading = false,
                onToggle = {},
                onOpen = {}
            )
        }
        composeTestRule.onNodeWithTag("miniPlayerBar").assertExists()
        composeTestRule.onNodeWithTag("miniPlayerBar").assertIsDisplayed()
        composeTestRule.onNodeWithText("测试书籍").assertIsDisplayed()
    }

    @Test
    fun `mini bar hidden without track`() {
        composeTestRule.setContent {
            MiniPlayerBar(
                track = null,
                isPlaying = false,
                isLoading = false,
                onToggle = {},
                onOpen = {}
            )
        }
        composeTestRule.onNodeWithTag("miniPlayerBar").assertDoesNotExist()
    }

    @Test
    fun `mini bar shows pause when playing`() {
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = true,
                isLoading = false,
                onToggle = {},
                onOpen = {}
            )
        }
        composeTestRule.onNodeWithContentDescription("暂停").assertIsDisplayed()
    }

    @Test
    fun `mini bar shows play when paused`() {
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = false,
                isLoading = false,
                onToggle = {},
                onOpen = {}
            )
        }
        composeTestRule.onNodeWithContentDescription("播放").assertIsDisplayed()
    }

    @Test
    fun `mini bar toggle fires on pause button click`() {
        var toggled = 0
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = true,
                isLoading = false,
                onToggle = { toggled++ },
                onOpen = {}
            )
        }
        composeTestRule.onNodeWithTag("miniPlayPause").performClick()
        assert(toggled == 1)
    }

    @Test
    fun `mini bar renders progress line`() {
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = true,
                isLoading = false,
                onToggle = {},
                onOpen = {},
                progress = 0.5f
            )
        }
        composeTestRule.onNodeWithTag("miniProgress").assertExists()
    }

    @Test
    fun `mini bar hidden when visible is false`() {
        var visible by mutableStateOf(true)
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = true,
                isLoading = false,
                onToggle = {},
                onOpen = {},
                visible = visible
            )
        }
        composeTestRule.onNodeWithTag("miniPlayerBar").assertIsDisplayed()
        visible = false
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(2000)
        composeTestRule.onNodeWithTag("miniPlayerBar").assertIsNotDisplayed()
    }

    @Test
    fun `mini bar swipe up dismisses`() {
        var dismissed = 0
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = true,
                isLoading = false,
                onToggle = {},
                onOpen = {},
                onDismiss = { dismissed++ }
            )
        }
        // 果断的上滑：超出条高，模拟用户明确的关闭手势
        composeTestRule.onNodeWithTag("miniPlayerBar").performTouchInput {
            swipe(
                start = center,
                end = center.copy(y = center.y - 1200f),
                durationMillis = 300
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(2000)
        assert(dismissed == 1)
    }

    @Test
    fun `mini bar swipe down does not dismiss`() {
        var dismissed = 0
        composeTestRule.setContent {
            MiniPlayerBar(
                track = demoTrack(),
                isPlaying = true,
                isLoading = false,
                onToggle = {},
                onOpen = {},
                onDismiss = { dismissed++ }
            )
        }
        composeTestRule.onNodeWithTag("miniPlayerBar").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(2000)
        assert(dismissed == 0)
        composeTestRule.onNodeWithTag("miniPlayerBar").assertIsDisplayed()
    }
}
