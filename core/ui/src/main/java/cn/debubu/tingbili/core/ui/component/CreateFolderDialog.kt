package cn.debubu.tingbili.core.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
fun CreateFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "新建收藏夹",
    initialName: String = "",
    confirmLabel: String = "创建",
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = {
            keyboard?.hide()
            onDismiss()
        },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("收藏夹名称") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty(),
                onClick = {
                    keyboard?.hide()
                    onConfirm(trimmed)
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    keyboard?.hide()
                    onDismiss()
                },
            ) { Text("取消") }
        },
    )
}
