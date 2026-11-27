package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.model.CoverDisplayMode
import cn.debubu.tingbili.core.ui.component.SettingAccent
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRadioRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.SettingSwitchRow
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/**
 * 二级页：播放封面（播放设置 → 播放封面进入，唯一入口）。
 *
 * 来源模式用单选而非选择弹层：依赖型子项「单 P 仍用 BV 封面」要与父项同屏、
 * 随选随变（Android/Microsoft 设置指南的渐进披露），弹层选完再回外层会把操作链拆断。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoverSettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val mode by vm.coverDisplayMode.collectAsStateWithLifecycle()
    val singlePartUsesBvCover by vm.singlePartUsesBvCover.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "播放封面",
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
                // 底部留出悬浮 mini 的高度
                .padding(bottom = cn.debubu.tingbili.core.ui.MiniPlayerClearance)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                SettingSectionLabel("封面来源")
                SettingsGroup(modifier = Modifier.selectableGroup()) {
                    SettingRadioRow(
                        title = "BV 封面",
                        subtitle = "所有分 P 使用稿件封面",
                        selected = mode == CoverDisplayMode.BV_COVER,
                        onClick = { vm.setCoverDisplayMode(CoverDisplayMode.BV_COVER) },
                    )
                    SettingDivider()
                    SettingRadioRow(
                        title = "BV 首帧",
                        subtitle = "多 P 视频随分 P 展示不同首帧；缺失时自动回退 BV 封面",
                        selected = mode == CoverDisplayMode.FIRST_FRAME,
                        onClick = { vm.setCoverDisplayMode(CoverDisplayMode.FIRST_FRAME) },
                    )
                    // 依赖型子项：紧贴父项、仅首帧模式下展示，选完即生效
                    if (mode == CoverDisplayMode.FIRST_FRAME) {
                        SettingDivider()
                        SettingSwitchRow(
                            icon = Icons.Filled.Image,
                            accent = SettingAccent.CYAN,
                            title = "单 P 仍用 BV 封面",
                            subtitle = "开启时单 P 视频继续用稿件封面，仅多 P 用首帧",
                            checked = singlePartUsesBvCover,
                            onCheckedChange = vm::setSinglePartUsesBvCover,
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
                    text = "只影响播放页、迷你播放器与系统媒体封面；详情、收藏、历史等列表仍用 BV 封面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
