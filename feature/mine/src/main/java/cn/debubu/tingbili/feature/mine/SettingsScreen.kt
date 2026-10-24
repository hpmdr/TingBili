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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.SettingRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingSwitchRow
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.core.ui.theme.LocalDarkTheme
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality

/** 二级页：播放设置（步长 / 字幕 / 解码开关）。默认音质独立页，主题在「外观」，缓存在「存储与缓存」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAudioDecoders: () -> Unit,
    onOpenQuality: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val step by vm.stepSec.collectAsStateWithLifecycle()
    val subtitleScale by vm.subtitleScale.collectAsStateWithLifecycle()
    val audioHwDecode by vm.audioHwDecode.collectAsStateWithLifecycle()
    val audioOffload by vm.audioOffload.collectAsStateWithLifecycle()
    val audioQuality by vm.audioQuality.collectAsStateWithLifecycle()
    var showStepSheet by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    val darkTheme = LocalDarkTheme.current

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "播放设置",
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
                SettingSectionLabel("播放")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Timer,
                        iconTint = Color(0xFF10B981),
                        iconBg = if (darkTheme) Color(0xFF062B22) else Color(0xFFECFDF5),
                        title = "前进 / 后退步长",
                        subtitle = "点选进入调整",
                        value = "${step}s",
                        onClick = { showStepSheet = true }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Filled.ClosedCaption,
                        iconTint = Color(0xFF8B5CF6),
                        iconBg = if (darkTheme) Color(0xFF231038) else Color(0xFFF3E8FF),
                        title = "字幕字号",
                        subtitle = "全屏与迷你字幕同步缩放",
                        value = subtitleScaleLabel(subtitleScale),
                        onClick = { showSubtitleDialog = true }
                    )
                }
            }

            Column {
                SettingSectionLabel("解码（切换后下一首生效）")
                SettingsGroup {
                    SettingSwitchRow(
                        icon = Icons.Filled.Bolt,
                        iconTint = Color(0xFFF59E0B),
                        iconBg = if (darkTheme) Color(0xFF2E2306) else Color(0xFFFFF7E6),
                        title = "优先硬件解码",
                        subtitle = "硬解更省电，失败自动回落软解",
                        checked = audioHwDecode,
                        onCheckedChange = { vm.setAudioHwDecode(it) }
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Memory,
                        iconTint = Color(0xFF06B6D4),
                        iconBg = if (darkTheme) Color(0xFF083344) else Color(0xFFECFEFF),
                        title = "省电播放（DSP 卸载）",
                        subtitle = "DSP 芯片直接解码，比硬解更省电；开启后倍速不可调，保持 1.0x",
                        checked = audioOffload,
                        onCheckedChange = { vm.setAudioOffload(it) }
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.AutoMirrored.Filled.List,
                        iconTint = Color(0xFF6366F1),
                        iconBg = if (darkTheme) Color(0xFF1E1B4B) else Color(0xFFEEF2FF),
                        title = "本机音频解码器",
                        subtitle = "MediaCodec 实测列表，仅问题定位用",
                        onClick = onOpenAudioDecoders
                    )
                }
            }

            Column {
                SettingSectionLabel("音质")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.HighQuality,
                        iconTint = Color(0xFFFB7299),
                        iconBg = if (darkTheme) Color(0xFF3B1220) else Color(0xFFFFF0F5),
                        title = "默认音质",
                        subtitle = "高档需登录/会员，拿不到自动回落",
                        value = AudioQuality.fromCode(audioQuality).shortDesc,
                        onClick = onOpenQuality
                    )
                }
            }
        }
    }

    if (showStepSheet) {
        StepSheet(
            step = step,
            onSelect = { vm.setStep(it) },
            onDismiss = { showStepSheet = false }
        )
    }
    if (showSubtitleDialog) {
        SubtitleScaleDialog(
            current = subtitleScale,
            onSelect = { vm.setSubtitleScale(it); showSubtitleDialog = false },
            onDismiss = { showSubtitleDialog = false }
        )
    }
}

/** 步长 BottomSheet：拖动只改本地预览，松手落盘；快捷 chips 直接落盘。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepSheet(
    step: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 拖动只改本地预览，松手再落盘：否则每帧一次 DataStore 全量重写
            var sliderValue by remember(step) { mutableFloatStateOf(step.toFloat()) }
            Text(
                "前进 / 后退步长：${sliderValue.toInt()} 秒",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSelect(sliderValue.toInt()) },
                valueRange = 5f..60f,
                steps = 10,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 30, 60).forEach { v ->
                    androidx.compose.material3.FilterChip(
                        selected = sliderValue.toInt() == v,
                        onClick = { onSelect(v) },
                        label = { Text("${v}s") }
                    )
                }
            }
            Text(
                "播放页快进/快退一次跳过的秒数。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 字幕字号 Dialog：四档互斥，点选即生效关闭。 */
@Composable
private fun SubtitleScaleDialog(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字幕字号") },
        text = {
            Column {
                listOf(0.85f to "小", 1f to "标准", 1.15f to "大", 1.3f to "特大").forEach { (scale, label) ->
                    SettingRadioRow(
                        title = label,
                        subtitle = "相对系数 ${scale}，叠加系统字体大小",
                        selected = kotlin.math.abs(current - scale) < 0.001f,
                        onClick = { onSelect(scale) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}