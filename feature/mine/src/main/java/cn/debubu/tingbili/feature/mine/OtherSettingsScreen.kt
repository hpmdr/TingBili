package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.SettingRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.core.ui.theme.LocalDarkTheme

/** 二级页：存储与缓存（图片格式 / 缓存管理入口 / 首页推荐词）。解码器入口已搬到播放设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherSettingsScreen(
    onBack: () -> Unit,
    onOpenCache: () -> Unit,
    vm: OtherSettingsViewModel = hiltViewModel()
) {
    val imageFormat by vm.imageFormat.collectAsStateWithLifecycle()
    val stats by vm.cacheStats.collectAsStateWithLifecycle()
    val cacheMaxMb by vm.cacheMaxMb.collectAsStateWithLifecycle()
    val homeKeywords by vm.homeKeywords.collectAsStateWithLifecycle()
    var showImageDialog by remember { mutableStateOf(false) }
    var showKeywordsDialog by remember { mutableStateOf(false) }
    val darkTheme = LocalDarkTheme.current

    LaunchedEffect(Unit) { vm.refreshCacheStats() }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "存储与缓存",
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
            Column {
                SettingSectionLabel("用量")
                SettingsGroup {
                    CacheUsageCard(
                        audioBytes = stats.audioBytes,
                        imageBytes = stats.imageBytes,
                        subtitleBytes = stats.subtitleBytes,
                        cacheMaxMb = cacheMaxMb
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Filled.CleaningServices,
                        iconTint = Color(0xFFF59E0B),
                        iconBg = if (darkTheme) Color(0xFF2E2306) else Color(0xFFFFF7E6),
                        title = "缓存管理",
                        subtitle = "分别清理 · 上限设置 · 全部清空",
                        value = formatCacheSize(stats.totalBytes),
                        onClick = onOpenCache
                    )
                }
            }

            Column {
                SettingSectionLabel("图片")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Image,
                        iconTint = Color(0xFF06B6D4),
                        iconBg = if (darkTheme) Color(0xFF083344) else Color(0xFFECFEFF),
                        title = "B 站图片格式",
                        subtitle = "遇到图片无法显示可切换",
                        value = imageFormatLabel(imageFormat),
                        onClick = { showImageDialog = true }
                    )
                }
            }

            Column {
                SettingSectionLabel("发现")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Tag,
                        iconTint = Color(0xFF8B5CF6),
                        iconBg = if (darkTheme) Color(0xFF231038) else Color(0xFFF3E8FF),
                        title = "首页推荐词",
                        subtitle = "首页发现流按这些词轮换搜索",
                        value = "${homeKeywords.size}个",
                        onClick = { showKeywordsDialog = true }
                    )
                }
            }
        }
    }

    if (showImageDialog) {
        ImageFormatDialog(
            current = imageFormat,
            onSelect = { vm.setImageFormat(it); showImageDialog = false },
            onDismiss = { showImageDialog = false }
        )
    }
    if (showKeywordsDialog) {
        KeywordsDialog(
            keywords = homeKeywords,
            onAdd = { vm.addHomeKeyword(it) },
            onRemove = { vm.removeHomeKeyword(it) },
            onDismiss = { showKeywordsDialog = false }
        )
    }
}

@Composable
private fun CacheUsageCard(
    audioBytes: Long,
    imageBytes: Long,
    subtitleBytes: Long,
    cacheMaxMb: Int
) {
    val total = audioBytes + imageBytes + subtitleBytes
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("共占用约 ${formatCacheSize(total)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "上限 ${if (cacheMaxMb >= 1024) "${cacheMaxMb / 1024}GB" else "${cacheMaxMb}MB"}（仅音频）",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            if (audioBytes > 0L) {
                Box(
                    modifier = Modifier
                        .weight(audioBytes.toFloat())
                        .height(8.dp)
                        .background(Color(0xFFFB7299))
                )
            }
            if (imageBytes > 0L) {
                Box(
                    modifier = Modifier
                        .weight(imageBytes.toFloat())
                        .height(8.dp)
                        .background(Color(0xFF06B6D4))
                )
            }
            if (subtitleBytes > 0L) {
                Box(
                    modifier = Modifier
                        .weight(subtitleBytes.toFloat())
                        .height(8.dp)
                        .background(Color(0xFFD97706))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "音频 ${formatCacheSize(audioBytes)} · 图片 ${formatCacheSize(imageBytes)} · 字幕 ${formatCacheSize(subtitleBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

/** 图片格式 Dialog：三档互斥，点选即生效关闭。 */
@Composable
private fun ImageFormatDialog(
    current: ImageFormat,
    onSelect: (ImageFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("B 站图片格式") },
        text = {
            Column {
                SettingRadioRow(
                    title = "AVIF",
                    subtitle = "省流量，默认",
                    selected = current == ImageFormat.AVIF,
                    onClick = { onSelect(ImageFormat.AVIF) }
                )
                SettingRadioRow(
                    title = "WebP",
                    subtitle = "均衡",
                    selected = current == ImageFormat.WEBP,
                    onClick = { onSelect(ImageFormat.WEBP) }
                )
                SettingRadioRow(
                    title = "JPEG",
                    subtitle = "兜底，遇到白图时切这个",
                    selected = current == ImageFormat.JPEG,
                    onClick = { onSelect(ImageFormat.JPEG) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 首页推荐词 Dialog：点词条删除，至少保留一个。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordsDialog(
    keywords: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newKeyword by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("首页推荐词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    keywords.forEach { kw ->
                        InputChip(
                            selected = false,
                            onClick = { onRemove(kw) },
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newKeyword,
                        onValueChange = { newKeyword = it },
                        placeholder = { Text("添加推荐词") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            onAdd(newKeyword)
                            newKeyword = ""
                        },
                        enabled = newKeyword.isNotBlank()
                    ) { Text("添加") }
                }
                Text(
                    "首页发现流按这些词轮换搜索；点词条删除，至少保留一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

private fun imageFormatLabel(format: ImageFormat): String = when (format) {
    ImageFormat.AVIF -> "AVIF"
    ImageFormat.WEBP -> "WebP"
    ImageFormat.JPEG -> "JPEG"
}