package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.MiniPlayerClearance
import cn.debubu.tingbili.core.ui.SubtitleTypography
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/**
 * 二级页：字幕字号（播放设置 → 字幕字号进入，唯一入口）。
 *
 * 顶部预览卡与播放页全屏字幕共用 [SubtitleTypography] 公式，切档即实时变化；
 * 档位点选即时落盘（无「应用」按钮），与播放封面子页面同款交互。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val subtitleScale by vm.subtitleScale.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "字幕字号",
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
                .padding(bottom = MiniPlayerClearance)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                SettingSectionLabel("预览")
                SubtitlePreviewCard(userScale = subtitleScale)
            }

            Column {
                SettingSectionLabel("字号")
                SettingsGroup(modifier = Modifier.selectableGroup()) {
                    SUBTITLE_SCALE_OPTIONS.forEachIndexed { index, (scale, label, desc) ->
                        if (index > 0) SettingDivider()
                        SettingRadioRow(
                            title = label,
                            subtitle = desc,
                            selected = subtitleScale == scale,
                            onClick = { vm.setSubtitleScale(scale) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "全屏与迷你字幕同步缩放，叠加系统字体大小。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 四档字幕字号：相对系数 + 中文名 + 说明（与 [subtitleScaleLabel] 的档位保持一致） */
private val SUBTITLE_SCALE_OPTIONS = listOf(
    Triple(0.85f, "小", "相对系数 0.85"),
    Triple(1f, "标准", "相对系数 1.0，默认"),
    Triple(1.15f, "大", "相对系数 1.15"),
    Triple(1.3f, "特大", "相对系数 1.3"),
)

/**
 * 实时预览卡：深色字幕区模拟播放页，三行样例按全屏公式渲染（当前行高亮）。
 * 倍数公式来自 [SubtitleTypography]，与播放页同源。
 */
@Composable
private fun SubtitlePreviewCard(userScale: Float) {
    val highlight = SubtitleTypography.highlight(userScale)
    val body = SubtitleTypography.body(userScale)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "和喜欢的人一起去看海吧",
            style = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 2,
        )
        Text(
            text = "今晚的月色真美",
            style = highlight,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        Text(
            text = "风也温柔，你也一样",
            style = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 2,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "字幕 · 中文（自动生成）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
