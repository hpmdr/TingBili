package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.model.ImageFormat
import cn.debubu.tingbili.core.ui.component.SettingAccent
import cn.debubu.tingbili.core.ui.component.SettingRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliBottomSheet
import cn.debubu.tingbili.core.ui.component.TingBiliChoiceOption
import cn.debubu.tingbili.core.ui.component.TingBiliChoiceSheet
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/** 二级页：通用设置（B 站图片格式 / 首页推荐词 / 关于）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    vm: GeneralSettingsViewModel = hiltViewModel(),
) {
    val imageFormat by vm.imageFormat.collectAsStateWithLifecycle()
    val homeKeywords by vm.homeKeywords.collectAsStateWithLifecycle()
    var showImageSheet by remember { mutableStateOf(false) }
    var showKeywordsSheet by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "通用设置",
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
                SettingSectionLabel("图片")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Image,
                        accent = SettingAccent.CYAN,
                        title = "B 站图片格式",
                        subtitle = "遇到图片无法显示时可切换",
                        value = imageFormatLabel(imageFormat),
                        onClick = { showImageSheet = true },
                    )
                }
            }

            Column {
                SettingSectionLabel("发现")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Tag,
                        accent = SettingAccent.PURPLE,
                        title = "首页推荐词",
                        subtitle = "首页发现流按这些词轮换搜索",
                        value = "${homeKeywords.size}个",
                        onClick = { showKeywordsSheet = true },
                    )
                }
            }

            Column {
                SettingSectionLabel("关于")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Info,
                        accent = SettingAccent.CYAN,
                        title = "关于",
                        subtitle = "版本信息 · 免责声明",
                        onClick = { showAbout = true },
                    )
                }
            }
        }
    }

    if (showImageSheet) {
        ImageFormatSheet(
            current = imageFormat,
            onApply = {
                vm.setImageFormat(it)
                showImageSheet = false
            },
            onDismiss = { showImageSheet = false },
        )
    }
    if (showKeywordsSheet) {
        KeywordsSheet(
            keywords = homeKeywords,
            onApply = {
                vm.setHomeKeywords(it)
                showKeywordsSheet = false
            },
            onDismiss = { showKeywordsSheet = false },
        )
    }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** 图片格式：统一使用选择 BottomSheet，点选预览、应用后落盘。 */
@Composable
private fun ImageFormatSheet(
    current: ImageFormat,
    onApply: (ImageFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    TingBiliChoiceSheet(
        title = "B 站图片格式",
        subtitle = "遇到图片无法显示时切换，应用后对后续请求生效。",
        options = listOf(
            TingBiliChoiceOption(ImageFormat.AVIF, "AVIF", "省流量，默认"),
            TingBiliChoiceOption(ImageFormat.WEBP, "WebP", "均衡"),
            TingBiliChoiceOption(ImageFormat.JPEG, "JPEG", "兜底，遇到白图时切换"),
        ),
        selected = current,
        onApply = onApply,
        onDismiss = onDismiss,
    )
}

/** 首页推荐词：弹层内编辑草稿，完成时一次性保存。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordsSheet(
    keywords: List<String>,
    onApply: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = remember(keywords) { keywords.toMutableStateList() }
    var newKeyword by remember { mutableStateOf("") }
    val addKeyword = {
        val keyword = newKeyword.trim()
        if (keyword.isNotEmpty() && keyword !in draft) draft.add(keyword)
        newKeyword = ""
    }

    TingBiliBottomSheet(
        title = "首页推荐词",
        subtitle = "在弹层中编辑草稿，点击完成后一次性保存。",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onApply(draft.toList()) }) { Text("完成") }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                draft.forEach { keyword ->
                    InputChip(
                        selected = false,
                        onClick = { if (draft.size > 1) draft.remove(keyword) },
                        label = { Text(keyword) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    placeholder = { Text("添加推荐词") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = addKeyword,
                    enabled = newKeyword.isNotBlank(),
                ) { Text("添加") }
            }
            Text(
                "首页发现流按这些词轮换搜索；点词条删除，至少保留一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        } catch (_: Exception) {
            "1.0"
        }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "非哔哩哔哩官方应用，与 B 站无关；内容与账号数据均来自哔哩哔哩，如有侵权请联系删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
