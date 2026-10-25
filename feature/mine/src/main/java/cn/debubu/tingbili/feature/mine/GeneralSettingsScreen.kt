package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

/** 二级页：通用设置（B 站图片格式 / 首页推荐词 / 关于）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    vm: GeneralSettingsViewModel = hiltViewModel()
) {
    val imageFormat by vm.imageFormat.collectAsStateWithLifecycle()
    val homeKeywords by vm.homeKeywords.collectAsStateWithLifecycle()
    var showImageDialog by remember { mutableStateOf(false) }
    var showKeywordsDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val darkTheme = LocalDarkTheme.current

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "通用设置",
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

            Column {
                SettingSectionLabel("关于")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Info,
                        iconTint = Color(0xFF06B6D4),
                        iconBg = if (darkTheme) Color(0xFF083344) else Color(0xFFECFEFF),
                        title = "关于",
                        subtitle = "版本信息 · 免责声明",
                        onClick = { showAbout = true }
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
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
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
                SettingDivider()
                SettingRadioRow(
                    title = "WebP",
                    subtitle = "均衡",
                    selected = current == ImageFormat.WEBP,
                    onClick = { onSelect(ImageFormat.WEBP) }
                )
                SettingDivider()
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

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("听视频 v$version", fontWeight = FontWeight.SemiBold)
                Text(
                    "本程序仅供学习测试使用，请在 24 小时内删除，禁止商用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Text(
                    "非哔哩哔哩官方应用，与 B 站无关；内容与账号数据均来自哔哩哔哩，如有侵权请联系删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}