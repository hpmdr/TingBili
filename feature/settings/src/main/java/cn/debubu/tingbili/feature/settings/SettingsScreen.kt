package cn.debubu.tingbili.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/** 二级页：常用设置（播放 / 缓存 / 关于）。主题相关在「外观」页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val step by vm.stepSec.collectAsStateWithLifecycle()
    val imageFormat by vm.imageFormat.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheSizeBytes.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshCacheSize() }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "设置",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 步进
            Column {
                Text("前进 / 后退步长: $step 秒", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = step.toFloat(),
                    onValueChange = { vm.setStep(it.toInt()) },
                    valueRange = 5f..60f,
                    steps = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 60).forEach { v ->
                        Button(onClick = { vm.setStep(v) }) { Text("${v}s") }
                    }
                }
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

            // 定时预设
            Column {
                Text("定时预设（分钟）", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 90).forEach { m ->
                        Button(onClick = { /* timer preset handled via PlayerManager in real app */ }) {
                            Text("${m}分")
                        }
                    }
                }
            }

            // 音频缓存
            Column {
                Text("音频缓存", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "已缓存约 ${formatCacheSize(cacheBytes)} / ${cacheMaxMb}MB，上次播放的音频会本地缓存，二次播放秒开且省流量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
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
                    "调整上限后重启应用生效；调小后超量部分会在使用中按最近最少使用逐出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.refreshCacheSize() }) { Text("刷新") }
                    Button(onClick = { vm.clearCache() }) { Text("清空缓存") }
                }
            }

            // 关于
            Column {
                Text("关于", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "TingBili · B 站专用音频播放器（非官方，仅供学习交流）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0L -> "0MB"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}
