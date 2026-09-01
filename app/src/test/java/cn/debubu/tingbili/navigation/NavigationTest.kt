package cn.debubu.tingbili.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    @Test
    fun `circular player renders progress ring`() {
        composeTestRule.setContent {
            CircularMiniPlayer(progress = 0.5f, cover = "", isPlaying = true, onClick = {}, onPlayPause = {})
        }
        composeTestRule.onNodeWithTag("progressRing").assertExists()
        composeTestRule.onNodeWithTag("progressRing").assertIsDisplayed()
    }

    @Test
    fun `circular player renders cover image`() {
        composeTestRule.setContent {
            CircularMiniPlayer(progress = 0.3f, cover = "https://example.com/cover.jpg", isPlaying = false, onClick = {}, onPlayPause = {})
        }
        composeTestRule.onNodeWithTag("progressRing").assertExists()
        composeTestRule.onNodeWithTag("coverImage").assertExists()
    }

    @Test
    fun `circular player progress updates`() {
        composeTestRule.setContent {
            CircularMiniPlayer(progress = 0.75f, cover = "", isPlaying = true, onClick = {}, onPlayPause = {})
        }
        composeTestRule.onNodeWithTag("progressRing").assertExists()
    }

    @Test
    fun `circular player handles zero progress`() {
        composeTestRule.setContent {
            CircularMiniPlayer(progress = 0f, cover = "", isPlaying = false, onClick = {}, onPlayPause = {})
        }
        composeTestRule.onNodeWithTag("progressRing").assertExists()
    }
}
