package cn.debubu.tingbili.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.debubu.tingbili.core.ui.component.SettingAccent
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.SettingSwitchRow
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsComponentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `radio row exposes selected state and toggles by row click`() {
        composeTestRule.setContent {
            TingBiliTheme {
                var selected by remember { mutableStateOf(false) }
                Column(modifier = Modifier.selectableGroup()) {
                    SettingRadioRow(
                        title = "标准",
                        subtitle = "相对系数 1.0",
                        selected = selected,
                        onClick = { selected = true },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("标准").assertIsNotSelected()
        composeTestRule.onNodeWithText("标准").performClick()
        composeTestRule.onNodeWithText("标准").assertIsSelected()
    }

    @Test
    fun `switch row is the only toggleable semantics node`() {
        composeTestRule.setContent {
            TingBiliTheme {
                var checked by remember { mutableStateOf(false) }
                SettingSwitchRow(
                    icon = Icons.Filled.Bedtime,
                    accent = SettingAccent.INDIGO,
                    title = "到点后播完本章再停",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        composeTestRule.onAllNodes(isToggleable()).assertCountEquals(1)
        composeTestRule.onNodeWithText("到点后播完本章再停").performClick()
    }
}
