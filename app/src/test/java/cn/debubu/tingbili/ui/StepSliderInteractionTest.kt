package cn.debubu.tingbili.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import cn.debubu.tingbili.core.ui.component.TingBiliStepSlider
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 步长滑轨 + 快捷档位：拖动语义走 setProgress，档位 chip 与应用按钮共用同一份草稿。 */
@RunWith(RobolectricTestRunner::class)
class StepSliderInteractionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `slider exposes progress semantics and snaps on set progress`() {
        var applied = 0
        composeTestRule.setContent {
            TingBiliTheme {
                TingBiliStepSlider(value = 30, onValueChange = { applied = it })
            }
        }

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(5f / 11f, 0f..1f)))
            .assertIsDisplayed()

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(5f / 11f, 0f..1f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(60, applied)

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(5f / 11f, 0f..1f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals(5, applied)
    }

    @Test
    fun `disabled slider rejects progress semantics`() {
        composeTestRule.setContent {
            TingBiliTheme {
                TingBiliStepSlider(value = 30, onValueChange = {}, enabled = false)
            }
        }

        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(5f / 11f, 0f..1f)))
            .assertIsNotEnabled()
    }

    @Test
    fun `preset chips drive the same draft as the slider`() {
        composeTestRule.setContent {
            TingBiliTheme {
                var draft by remember { mutableIntStateOf(30) }
                Column(modifier = Modifier) {
                    Text("$draft")
                    TingBiliStepSlider(value = draft, onValueChange = { draft = it })
                    listOf(5, 15, 30, 60).forEach { preset ->
                        FilterChip(
                            selected = draft == preset,
                            onClick = { draft = preset },
                            label = { Text("${preset}s") },
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("5s").performClick()
        composeTestRule.onNodeWithText("5").assertIsDisplayed()

        composeTestRule.onNodeWithText("60s").performClick()
        composeTestRule.onNodeWithText("60").assertIsDisplayed()
    }
}
