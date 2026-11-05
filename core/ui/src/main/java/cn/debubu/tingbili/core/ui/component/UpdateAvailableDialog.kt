package cn.debubu.tingbili.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 发现新版本弹窗（启动自动检查与设置页手动检查共用）：
 * 标题 tag + 可滚动 body（release notes 全文）+ 三个动作。
 *
 * "不再提醒"只记远端发布时间（不记 tag）：tag 手滑打错重发时时间会变，照样能再弹。
 */
@Composable
fun UpdateAvailableDialog(
    tag: String,
    body: String,
    onSkipRelease: () -> Unit,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 🎉") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tag, fontWeight = FontWeight.SemiBold)
                if (body.isNotBlank()) {
                    Text(
                        body,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onSkipRelease,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(
                        "不再提醒此版",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("立即下载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
