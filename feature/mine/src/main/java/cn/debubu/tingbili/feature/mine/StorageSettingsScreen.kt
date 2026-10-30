package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.datastore.PreferencesRepository
import cn.debubu.tingbili.core.ui.component.SettingAccent
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliChoiceOption
import cn.debubu.tingbili.core.ui.component.TingBiliChoiceSheet
import cn.debubu.tingbili.core.ui.component.TingBiliConfirmDialog
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliSnackbarHost
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/** 二级页：存储与缓存（用量总览 / 分别清理 / 音频上限 / 全部清空，单页）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    vm: StorageSettingsViewModel = hiltViewModel(),
) {
    val stats by vm.cacheStats.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearAll by remember { mutableStateOf(false) }
    var showLimitSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshCacheStats() }
    LaunchedEffect(notice) {
        notice?.let {
            vm.consumeNotice()
            snackbarHostState.showSnackbar(it)
        }
    }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "存储与缓存",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { TingBiliSnackbarHost(snackbarHostState) },
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
                SettingSectionLabel("用量")
                SettingsGroup {
                    CacheUsageCard(
                        audioBytes = stats.audioBytes,
                        imageBytes = stats.imageBytes,
                        subtitleBytes = stats.subtitleBytes,
                        cacheMaxMb = cacheMaxMb,
                    )
                }
            }

            Column {
                SettingSectionLabel("分别清理")
                SettingsGroup {
                    CacheClearRow(
                        title = "音频缓存",
                        desc = "上次播放的音频本地缓存，二次播放秒开且省流量",
                        value = "${formatCacheSize(stats.audioBytes)} / ${cacheMaxMb}MB",
                        onClear = { vm.clearAudioCache() },
                    )
                    SettingDivider()
                    CacheClearRow(
                        title = "图片缓存",
                        desc = "看过的内容封面都在本地，离线秒显且省流量",
                        value = formatCacheSize(stats.imageBytes),
                        onClear = { vm.clearImageCache() },
                    )
                    SettingDivider()
                    CacheClearRow(
                        title = "字幕缓存",
                        desc = "只留最近 50 集，看过的字幕秒显",
                        value = "${formatCacheSize(stats.subtitleBytes)}（约 ${stats.subtitleCount} 集）",
                        onClear = { vm.clearSubtitleCache() },
                    )
                }
            }

            Column {
                SettingSectionLabel("缓存上限（调整后重启生效）")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Storage,
                        accent = SettingAccent.BLUE,
                        title = "共用上限",
                        subtitle = "图片按上限四分之一自动配给",
                        value = StorageSettingsViewModel.limitLabel(cacheMaxMb),
                        onClick = { showLimitSheet = true },
                    )
                }
            }

            Column {
                SettingSectionLabel("危险区")
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showClearAll = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "全部清空",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "清除音频 + 图片 + 字幕全部缓存，该操作不可撤销",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = { vm.refreshCacheStats() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("刷新统计") }
        }
    }

    if (showLimitSheet) {
        CacheLimitSheet(
            currentMb = cacheMaxMb,
            onApply = {
                vm.setCacheMaxMb(it)
                showLimitSheet = false
            },
            onDismiss = { showLimitSheet = false },
        )
    }

    if (showClearAll) {
        TingBiliConfirmDialog(
            title = "全部清空？",
            text = "将清除音频 + 图片 + 字幕全部缓存，该操作不可撤销。",
            confirmLabel = "确认清空",
            destructive = true,
            onConfirm = {
                showClearAll = false
                vm.clearAllCaches()
            },
            onDismiss = { showClearAll = false },
        )
    }
}

@Composable
private fun CacheLimitSheet(
    currentMb: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = StorageSettingsViewModel.LIMIT_OPTIONS_MB.map { mb ->
        val imageMb = PreferencesRepository.imageCacheMaxMb(mb)
        TingBiliChoiceOption(
            value = mb,
            title = StorageSettingsViewModel.limitLabel(mb),
            supportingText = "音频 ${StorageSettingsViewModel.limitLabel(mb)} · 图片 ${StorageSettingsViewModel.limitLabel(imageMb)}" +
                if (imageMb >= PreferencesRepository.IMAGE_CACHE_MAX_MB) "（封顶）" else "",
        )
    }
    TingBiliChoiceSheet(
        title = "缓存上限",
        subtitle = "音频缓存上限；图片按上限四分之一自动配给，上限在启动时读取。",
        options = options,
        selected = currentMb,
        onApply = onApply,
        onDismiss = onDismiss,
    )
}

@Composable
private fun CacheUsageCard(
    audioBytes: Long,
    imageBytes: Long,
    subtitleBytes: Long,
    cacheMaxMb: Int,
) {
    val total = audioBytes + imageBytes + subtitleBytes
    val audioColor = SettingAccent.PINK.colors().tint
    val imageColor = SettingAccent.CYAN.colors().tint
    val subtitleColor = SettingAccent.AMBER.colors().tint
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "共占用约 ${formatCacheSize(total)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
            Text(
                "音频 ${StorageSettingsViewModel.limitLabel(cacheMaxMb)} · 图片 ${StorageSettingsViewModel.limitLabel(PreferencesRepository.imageCacheMaxMb(cacheMaxMb))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (audioBytes > 0L) {
                Box(Modifier.weight(audioBytes.toFloat()).height(8.dp).background(audioColor))
            }
            if (imageBytes > 0L) {
                Box(Modifier.weight(imageBytes.toFloat()).height(8.dp).background(imageColor))
            }
            if (subtitleBytes > 0L) {
                Box(Modifier.weight(subtitleBytes.toFloat()).height(8.dp).background(subtitleColor))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "音频 ${formatCacheSize(audioBytes)} · 图片 ${formatCacheSize(imageBytes)} · 字幕 ${formatCacheSize(subtitleBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CacheClearRow(
    title: String,
    desc: String,
    value: String,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
