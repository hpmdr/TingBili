package cn.debubu.tingbili.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 设置类 BottomSheet 的统一样式：
 * 标题左对齐、正文统一边距、固定底部操作区；关闭视为取消，只有主按钮落盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TingBiliBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                content = content,
            )
            if (actions != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.End,
                    content = actions,
                )
            }
        }
    }
}

data class TingBiliChoiceOption<T>(
    val value: T,
    val title: String,
    val supportingText: String? = null,
)

/** 选择类弹层的统一操作：点选预览，取消丢弃，应用才提交。 */
@Composable
fun <T> TingBiliChoiceSheet(
    title: String,
    options: List<TingBiliChoiceOption<T>>,
    selected: T,
    onApply: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    confirmLabel: String = "应用",
) {
    var draft by remember(selected) { mutableStateOf(selected) }
    TingBiliBottomSheet(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onApply(draft) }) { Text(confirmLabel) }
        },
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            options.forEachIndexed { index, option ->
                SettingRadioRow(
                    title = option.title,
                    subtitle = option.supportingText,
                    selected = draft == option.value,
                    onClick = { draft = option.value },
                )
                if (index != options.lastIndex) SettingDivider()
            }
        }
    }
}
