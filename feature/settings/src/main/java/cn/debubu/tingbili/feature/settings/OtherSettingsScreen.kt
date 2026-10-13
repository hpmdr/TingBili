package cn.debubu.tingbili.feature.settings
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
/** 二级页：其他设置（图片格式 / 音频缓存） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherSettingsScreen(
    onBack: () -> Unit,
    vm: OtherSettingsViewModel = hiltViewModel()
) {
    val imageFormat by vm.imageFormat.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheSizeBytes.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()
    val homeKeywords by vm.homeKeywords.collectAsStateWithLifecycle()
    var newKeyword by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshCacheSize() }

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
        }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0L -> "0MB"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}
