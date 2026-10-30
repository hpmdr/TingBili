package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.SettingAccent
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingSwitchRow
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliBottomSheet
import cn.debubu.tingbili.core.ui.component.TingBiliChoiceOption
import cn.debubu.tingbili.core.ui.component.TingBiliChoiceSheet
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality

/** 二级页：播放设置（步长 / 字幕 / 睡眠偏好 / 解码开关 / 默认音质入口）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAudioDecoders: () -> Unit,
    onOpenQuality: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val step by vm.stepSec.collectAsStateWithLifecycle()
    val subtitleScale by vm.subtitleScale.collectAsStateWithLifecycle()
    val audioHwDecode by vm.audioHwDecode.collectAsStateWithLifecycle()
    val audioOffload by vm.audioOffload.collectAsStateWithLifecycle()
    val audioQuality by vm.audioQuality.collectAsStateWithLifecycle()
    val sleepFinishChapter by vm.sleepFinishChapter.collectAsStateWithLifecycle()
    var showStepSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "播放设置",
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
                SettingSectionLabel("播放")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Timer,
                        accent = SettingAccent.GREEN,
                        title = "前进 / 后退步长",
                        subtitle = "播放页快进或快退一次跳过的秒数",
                        value = "${step}s",
                        onClick = { showStepSheet = true },
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Filled.ClosedCaption,
                        accent = SettingAccent.PURPLE,
                        title = "字幕字号",
                        subtitle = "全屏与迷你字幕同步缩放",
                        value = subtitleScaleLabel(subtitleScale),
                        onClick = { showSubtitleSheet = true },
                    )
                }
            }

            Column {
                SettingSectionLabel("睡眠偏好")
                SettingsGroup {
                    SettingSwitchRow(
                        icon = Icons.Filled.Bedtime,
                        accent = SettingAccent.INDIGO,
                        title = "到点后播完本章再停",
                        subtitle = "下次睡眠定时的默认选项，进行中的定时不受影响",
                        checked = sleepFinishChapter,
                        onCheckedChange = { vm.setSleepFinishChapter(it) },
                    )
                }
            }

            Column {
                SettingSectionLabel("解码（切换后下一首生效）")
                SettingsGroup {
                    SettingSwitchRow(
                        icon = Icons.Filled.Bolt,
                        accent = SettingAccent.AMBER,
                        title = "优先硬件解码",
                        subtitle = "硬解更省电，失败自动回落软解",
                        checked = audioHwDecode,
                        onCheckedChange = { vm.setAudioHwDecode(it) },
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Memory,
                        accent = SettingAccent.CYAN,
                        title = "省电播放（DSP 卸载）",
                        subtitle = "DSP 芯片直接解码，比硬解更省电；开启后倍速不可调，保持 1.0x",
                        checked = audioOffload,
                        onCheckedChange = { vm.setAudioOffload(it) },
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.AutoMirrored.Filled.List,
                        accent = SettingAccent.INDIGO,
                        title = "本机音频解码器",
                        subtitle = "MediaCodec 实测列表，仅问题定位用",
                        onClick = onOpenAudioDecoders,
                    )
                }
            }

            Column {
                SettingSectionLabel("音质")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.HighQuality,
                        accent = SettingAccent.PINK,
                        title = "默认音质",
                        subtitle = "高档需登录/会员，拿不到自动回落",
                        value = AudioQuality.fromCode(audioQuality).shortDesc,
                        onClick = onOpenQuality,
                    )
                }
            }
        }
    }

    if (showStepSheet) {
        StepSheet(
            step = step,
            onApply = {
                vm.setStep(it)
                showStepSheet = false
            },
            onDismiss = { showStepSheet = false },
        )
    }
    if (showSubtitleSheet) {
        SubtitleScaleSheet(
            current = subtitleScale,
            onApply = {
                vm.setSubtitleScale(it)
                showSubtitleSheet = false
            },
            onDismiss = { showSubtitleSheet = false },
        )
    }
}

/** 步长 BottomSheet：滑杆与快捷值只改本地草稿，应用后才落盘。 */
@Composable
private fun StepSheet(
    step: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(step) { mutableFloatStateOf(step.toFloat()) }
    TingBiliBottomSheet(
        title = "前进 / 后退步长",
        subtitle = "拖动滑杆或选择快捷值，点击应用后生效。",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onApply(draft.toInt()) }) { Text("应用") }
        },
    ) {
        Text(
            "当前：${draft.toInt()} 秒",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = draft,
            onValueChange = { draft = it },
            valueRange = 5f..60f,
            steps = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 15, 30, 60).forEach { value ->
                FilterChip(
                    selected = draft.toInt() == value,
                    onClick = { draft = value.toFloat() },
                    label = { Text("${value}s") },
                )
            }
        }
        Text(
            "播放页快进/快退一次跳过的秒数；滑动关闭视为取消。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 字幕字号：选择类弹层，点选预览，应用后生效。 */
@Composable
private fun SubtitleScaleSheet(
    current: Float,
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    TingBiliChoiceSheet(
        title = "字幕字号",
        subtitle = "选择预览，应用后全屏与迷你字幕同步缩放。",
        options = listOf(
            TingBiliChoiceOption(0.85f, "小", "相对系数 0.85，叠加系统字体大小"),
            TingBiliChoiceOption(1f, "标准", "相对系数 1.0，叠加系统字体大小"),
            TingBiliChoiceOption(1.15f, "大", "相对系数 1.15，叠加系统字体大小"),
            TingBiliChoiceOption(1.3f, "特大", "相对系数 1.3，叠加系统字体大小"),
        ),
        selected = current,
        onApply = onApply,
        onDismiss = onDismiss,
    )
}
