package cn.debubu.tingbili.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
