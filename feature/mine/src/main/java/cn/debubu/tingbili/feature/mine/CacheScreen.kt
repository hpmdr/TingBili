package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/** 二级页：缓存管理（存储与缓存 → 缓存管理进入，唯一入口）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheScreen(
    onBack: () -> Unit,
    vm: OtherSettingsViewModel = hiltViewModel()
) {
    val stats by vm.cacheStats.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshCacheStats() }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice!!)
            vm.consumeNotice()
        }
    }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "缓存管理",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                SettingSectionLabel("分别清理")
                SettingsGroup {
                    CacheClearRow(
                        title = "音频缓存",
                        desc = "上次播放的音频本地缓存，二次播放秒开且省流量",
                        value = "${formatCacheSize(stats.audioBytes)} / ${cacheMaxMb}MB",
                        onClear = { vm.clearAudioCache() }
                    )
                    SettingDivider()
                    CacheClearRow(
                        title = "图片缓存",
                        desc = "看过的内容封面都在本地，离线秒显且省流量",
                        value = formatCacheSize(stats.imageBytes),
                        onClear = { vm.clearImageCache() }
                    )
                    SettingDivider()
                    CacheClearRow(
                        title = "字幕缓存",
                        desc = "只留最近 50 集，看过的字幕秒显",
                        value = "${formatCacheSize(stats.subtitleBytes)}（约 ${stats.subtitleCount} 集）",
                        onClear = { vm.clearSubtitleCache() }
                    )
                }
            }

            Column {
                SettingSectionLabel("音频上限（调整后重启生效）")
                SettingsGroup {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            "当前上限 " + if (cacheMaxMb >= 1024) "${cacheMaxMb / 1024}GB" else "${cacheMaxMb}MB",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "只管音频；调小后超量部分在使用中按最近最少使用逐出。总数=音频+图片+字幕磁盘，直链等小量数据不计。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(100, 200, 500, 1024, 2048).forEach { max ->
                                FilterChip(
                                    selected = cacheMaxMb == max,
                                    onClick = { vm.setCacheMaxMb(max) },
                                    label = { Text(if (max >= 1024) "${max / 1024}GB" else "${max}MB") }
                                )
                            }
                        }
                    }
                }
            }

            Column {
                SettingSectionLabel("危险区")
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showClearAll = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "全部清空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFDC2626)
                            )
                            Text(
                                "音频 + 图片 + 字幕，需二次确认",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { vm.refreshCacheStats() },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("刷新统计") }
        }
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("全部清空？") },
            text = { Text("将清除音频 + 图片 + 字幕全部缓存，该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    vm.clearAllCaches()
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CacheClearRow(
    title: String,
    desc: String,
    value: String,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClear)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClear) { Text("清理") }
    }
}

internal fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0L -> "0MB"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}