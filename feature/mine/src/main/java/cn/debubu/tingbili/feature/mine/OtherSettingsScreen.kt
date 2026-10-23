package cn.debubu.tingbili.feature.mine
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
/** 二级页：其他设置（图片格式 / 缓存管理） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherSettingsScreen(
    onBack: () -> Unit,
    vm: OtherSettingsViewModel = hiltViewModel()
) {
    val imageFormat by vm.imageFormat.collectAsStateWithLifecycle()
    val stats by vm.cacheStats.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()
    val audioHwDecode by vm.audioHwDecode.collectAsStateWithLifecycle()
    val homeKeywords by vm.homeKeywords.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var newKeyword by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

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
                title = "其他设置",
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
            // 首页推荐词
            Column {
                Text("首页推荐词", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    homeKeywords.forEach { kw ->
                        InputChip(
                            selected = false,
                            onClick = { vm.removeHomeKeyword(kw) },
                            label = { Text(kw) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "删除 $kw",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        placeholder = { Text("添加推荐词") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            vm.addHomeKeyword(newKeyword)
                            newKeyword = ""
                        },
                        enabled = newKeyword.isNotBlank()
                    ) { Text("添加") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "首页发现流按这些词轮换搜索；点词条删除，至少保留一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // 图片格式
            Column {
                Text("B 站图片格式", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageFormat.entries.forEach { format ->
                        FilterChip(
                            selected = imageFormat == format,
                            onClick = { vm.setImageFormat(format) },
                            label = {
                                Text(
                                    when (format) {
                                        ImageFormat.AVIF -> "AVIF 省流量"
                                        ImageFormat.WEBP -> "WebP 均衡"
                                        ImageFormat.JPEG -> "JPEG 兜底"
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "默认 AVIF；若遇到图片无法显示，可切换 WebP 或 JPEG。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // 音频解码
            Column {
                Text("音频解码", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("优先硬件解码")
                        Text(
                            "硬解更省电；机型不支持或硬解失败时自动回落软解。切换后下一首生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = audioHwDecode,
                        onCheckedChange = { vm.setAudioHwDecode(it) }
                    )
                }
            }

            // 缓存管理：音频 / 图片 / 字幕分别统计、分别清理
            Column {
                Text("缓存管理", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "共占用约 ${formatCacheSize(stats.totalBytes)}（音频 ${formatCacheSize(stats.audioBytes)} · 图片 ${formatCacheSize(stats.imageBytes)} · 字幕 ${formatCacheSize(stats.subtitleBytes)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(8.dp))
                CacheRow(
                    label = "音频缓存 ${formatCacheSize(stats.audioBytes)} / ${cacheMaxMb}MB",
                    desc = "上次播放的音频会本地缓存，二次播放秒开且省流量",
                    onClear = { vm.clearAudioCache() }
                )
                Spacer(Modifier.height(8.dp))
                CacheRow(
                    label = "图片缓存（封面/头像） ${formatCacheSize(stats.imageBytes)}",
                    desc = "看过的内容封面都在本地，离线秒显且省流量",
                    onClear = { vm.clearImageCache() }
                )
                Spacer(Modifier.height(8.dp))
                CacheRow(
                    label = "字幕缓存 ${formatCacheSize(stats.subtitleBytes)}（约 ${stats.subtitleCount} 集）",
                    desc = "只留最近 50 集，看过的字幕秒显",
                    onClear = { vm.clearSubtitleCache() }
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "上方上限只管音频，调整后重启应用生效；调小后超量部分会在使用中按最近最少使用逐出。总数=音频+图片+字幕磁盘，直链等小量数据不计。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.refreshCacheStats() }) { Text("刷新") }
                    Button(onClick = { vm.clearAllCaches() }) { Text("全部清空") }
                }
            }
        }
    }
}

@Composable
private fun CacheRow(
    label: String,
    desc: String,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClear) { Text("清理") }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0L -> "0MB"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}
