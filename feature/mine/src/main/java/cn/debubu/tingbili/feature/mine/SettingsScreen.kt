package cn.debubu.tingbili.feature.mine

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality

/** 二级页：播放与音频设置（步长）。主题在「外观」，图片格式与缓存在「其他设置」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val step by vm.stepSec.collectAsStateWithLifecycle()
    val subtitleScale by vm.subtitleScale.collectAsStateWithLifecycle()
    val audioHwDecode by vm.audioHwDecode.collectAsStateWithLifecycle()
    val audioOffload by vm.audioOffload.collectAsStateWithLifecycle()
    val audioQuality by vm.audioQuality.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "播放与音频设置",
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
                // 拖动只改本地预览，松手再落盘：否则每帧一次 DataStore 全量重写
                var sliderValue by remember(step) { mutableFloatStateOf(step.toFloat()) }
                Text("前进 / 后退步长: ${sliderValue.toInt()} 秒", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { vm.setStep(sliderValue.toInt()) },
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

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字幕字号", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "全屏字幕与迷你字幕一起缩放（叠加系统字体大小）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(0.85f to "小", 1f to "标准", 1.15f to "大", 1.3f to "特大").forEach { (scale, label) ->
                    val selected = kotlin.math.abs(subtitleScale - scale) < 0.001f
                    Surface(
                        onClick = { vm.setSubtitleScale(scale) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (selected) {
                                Text(text = "✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 音频解码（播放页右上播放设置唯一入口；缺省开，见 PreferencesRepository）
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
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DSP 音频卸载（省电）")
                        Text(
                            "条件满足时整条管线交 DSP，CPU 休眠；变速或格式不支持时自动回落。切换后下一首生效；开启时倍速不可用（保持 1.0x）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = audioOffload,
                        onCheckedChange = { vm.setAudioOffload(it) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 默认音质：新播放的偏好档（播放页底部音质入口可按本集临时切换）
                var showQualitySheet by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showQualitySheet = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("默认音质：${AudioQuality.fromCode(audioQuality).desc}")
                        Text(
                            "新播放默认用这档；高档需登录（杜比/Hi-Res 另需大会员），拿不到时自动用可用最高档，不断播。切换后下一首生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                if (showQualitySheet) {
                    DefaultQualitySheet(
                        current = audioQuality,
                        onSelect = { vm.setAudioQuality(it); showQualitySheet = false },
                        onDismiss = { showQualitySheet = false },
                    )
                }
            }

        }
    }
}

/** 默认音质档位说明见 AUDIO_QUALITY.md（门槛只做标注，落盘不拦，播放时拿不到自动回落）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultQualitySheet(
    current: Int,
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
            Text(text = "默认音质", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "新播放默认用这档，实际能拿到哪档由登录和会员状态决定，拿不到自动回落。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AudioQuality.values.forEach { q ->
                val selected = q.code == current
                Surface(
                    onClick = { onSelect(q.code) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = q.desc,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                text = if (q.vipOnly) "需登录/大会员" else if (q.requiresLogin) "需登录" else "免登录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Text(text = "✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
