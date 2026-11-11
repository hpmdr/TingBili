package cn.debubu.tingbili.feature.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.backup.BackupPreview
import cn.debubu.tingbili.core.data.backup.ImportMode
import cn.debubu.tingbili.core.ui.component.SettingAccent
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingSwitchRow
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliMessageBus
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 二级页：备份与恢复（导出 JSON / 导入预览 / 合并或替换）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    vm: BackupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var includeAuth by remember { mutableStateOf(true) }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { vm.export(it, includeAuth) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.parseImport(it) }
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is BackupViewModel.UiState.Exported -> {
                TingBiliMessageBus.show("已导出：${s.fileName}")
                vm.reset()
            }
            is BackupViewModel.UiState.Imported -> {
                TingBiliMessageBus.show(
                    "导入完成：收藏 +${s.result.tracksAdded}，历史 +${s.result.historyAdded}，设置已恢复"
                )
                vm.reset()
            }
            is BackupViewModel.UiState.Error -> {
                TingBiliMessageBus.show(s.message)
                vm.reset()
            }
            else -> Unit
        }
    }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "备份与恢复",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = cn.debubu.tingbili.core.ui.MiniPlayerClearance)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                SettingSectionLabel("备份内容")
                SettingsGroup {
                    Text(
                        "收藏夹、BV 收藏、播放历史、搜索历史与用户设置会写入一个 JSON 文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Column {
                SettingSectionLabel("导出")
                SettingsGroup {
                    SettingSwitchRow(
                        icon = Icons.Filled.Key,
                        accent = SettingAccent.PURPLE,
                        title = "包含登录信息",
                        subtitle = "明文保存 SESSDATA；文件泄露后他人可登录你的账号，请妥善保管",
                        checked = includeAuth,
                        onCheckedChange = { includeAuth = it },
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Filled.Backup,
                        accent = SettingAccent.AMBER,
                        title = "导出备份",
                        subtitle = "选择保存位置，生成 JSON 文件",
                        onClick = { exportLauncher.launch(backupFileName()) },
                    )
                }
            }

            Column {
                SettingSectionLabel("恢复")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Restore,
                        accent = SettingAccent.BLUE,
                        title = "导入备份",
                        subtitle = "先预览内容，再选择合并或替换",
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }

            if (state == BackupViewModel.UiState.Working) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "处理中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val preview = state as? BackupViewModel.UiState.Preview
    if (preview != null) {
        ImportPreviewDialog(
            preview = preview.preview,
            onMerge = { vm.confirmImport(ImportMode.MERGE) },
            onReplace = { showReplaceConfirm = true },
            onDismiss = { vm.dismissPreview() },
        )
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text("替换导入？") },
            text = { Text("将先清空本地收藏夹、BV 收藏和播放历史，再写入备份内容。此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReplaceConfirm = false
                        vm.confirmImport(ImportMode.REPLACE)
                    }
                ) {
                    Text("确认替换", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: BackupPreview,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "导出时间：${formatExportTime(preview.exportedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "App 版本：${preview.appVersion}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (preview.includesAuth) {
                    Text(
                        "包含登录账号：导入后将覆盖当前登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    "收藏夹 ${preview.folderCount} 个 · 收藏 ${preview.trackCount} 首",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "BV 收藏 ${preview.bvFavoriteCount} 个 · 历史 ${preview.historyCount} 条",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "搜索历史 ${preview.searchHistoryCount} 条 · 设置 ${preview.settingsCount} 项",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onMerge) { Text("合并导入") }
                TextButton(onClick = onReplace) { Text("替换导入") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun backupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
    return "tingbili-backup-$stamp.json"
}

private fun formatExportTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
