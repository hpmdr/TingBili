package cn.debubu.tingbili.feature.mine

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import cn.debubu.tingbili.core.data.model.RandomPlayMode

/** 二级页：播放设置（播放分组：步长 / 字幕 / 随机 / 默认音质 + 睡眠偏好 / 解码开关）。 */
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
    val offloadActive by vm.offloadEverActive.collectAsStateWithLifecycle()
    val decoderName by vm.currentDecoderName.collectAsStateWithLifecycle()
    val audioQuality by vm.audioQuality.collectAsStateWithLifecycle()
    val randomPlayMode by vm.randomPlayMode.collectAsStateWithLifecycle()
    val sleepFinishChapter by vm.sleepFinishChapter.collectAsStateWithLifecycle()
    var showStepSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showRandomModeSheet by remember { mutableStateOf(false) }

    // 通知栏播放控制：媒体通知豁免 POST_NOTIFICATIONS，只有用户手动关闭系统通知才会失效。
    val context = LocalContext.current
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var notificationExempt by remember { mutableStateOf(isMediaNotificationExempt(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        notificationExempt = isMediaNotificationExempt(context)
    }

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
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Filled.Shuffle,
                        accent = SettingAccent.BLUE,
                        title = "随机播放方式",
                        subtitle = "洗牌：每轮每集一次；纯随机：下一首可能重复",
                        value = when (randomPlayMode) {
                            RandomPlayMode.SHUFFLE -> "洗牌"
                            RandomPlayMode.PURE_RANDOM -> "纯随机"
                        },
                        onClick = { showRandomModeSheet = true },
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Filled.HighQuality,
                        accent = SettingAccent.PINK,
                        title = "默认音质",
                        subtitle = "高档需登录/会员，拿不到自动回落",
                        value = AudioQuality.fromCode(audioQuality).shortDesc,
                        onClick = onOpenQuality,
                    )
                    SettingDivider()
                    val notificationsUsable = notificationsEnabled || notificationExempt
                    SettingRow(
                        icon = Icons.Filled.Notifications,
                        accent = SettingAccent.AMBER,
                        title = "通知栏播放控制",
                        subtitle = if (notificationsUsable) {
                            "通知栏和锁屏可暂停、切歌，耳机线控可用"
                        } else {
                            "通知栏和锁屏不会显示播放控制，点击去系统设置"
                        },
                        value = if (notificationsUsable) "已开启" else "已关闭",
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            runCatching { context.startActivity(intent) }
                        },
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
                    // 开关打开但本机始终没接管时给出原因（PERF_MEASUREMENT §11：
                    // MTK/小米机型的音效链会 veto offload，用户会以为开关坏了）
                    val showOffloadHint = audioOffload && !offloadActive && decoderName != null
                    if (showOffloadHint) {
                        Text(
                            text = "本机暂未启用 DSP 卸载：系统音效链不支持压缩直通，当前为软件解码（${decoderName}）。" +
                                "可在系统「声音与振动 → 音效」里关闭音效后重试；开关保持开启，条件满足时自动接管。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
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
    if (showRandomModeSheet) {
        TingBiliChoiceSheet(
            title = "随机播放方式",
            subtitle = "只影响播放列表里打开「随机」后的选曲方式。",
            options = listOf(
                TingBiliChoiceOption(
                    RandomPlayMode.SHUFFLE,
                    "洗牌播放",
                    "把当前列表按随机顺序打散，每轮每集最多播一次",
                ),
                TingBiliChoiceOption(
                    RandomPlayMode.PURE_RANDOM,
                    "纯随机",
                    "每次下一首都从当前列表随机选，可能重复播同一集",
                ),
            ),
            selected = randomPlayMode,
            onApply = {
                vm.setRandomPlayMode(it)
                showRandomModeSheet = false
            },
            onDismiss = { showRandomModeSheet = false },
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

/**
 * 13+ 未授予 POST_NOTIFICATIONS 时，媒体会话通知仍可显示（平台豁免）。
 * 与「用户手动关闭系统通知」区分：只有后者才需要提示/引导。
 */
private fun isMediaNotificationExempt(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
