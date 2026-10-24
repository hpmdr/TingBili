package cn.debubu.tingbili.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.data.bilibili.dto.AudioQuality

/** 二级页：默认音质（播放设置 → 默认音质进入，唯一入口）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel()
) {
    val audioQuality by vm.audioQuality.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "默认音质",
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
                SettingSectionLabel("新播放默认使用这档")
                SettingsGroup {
                    AudioQuality.values.forEachIndexed { index, q ->
                        if (index > 0) {
                            cn.debubu.tingbili.core.ui.component.SettingDivider()
                        }
                        val selected = q.code == audioQuality
                        cn.debubu.tingbili.core.ui.component.SettingRadioRow(
                            title = q.desc,
                            subtitle = if (q.vipOnly) "需登录/大会员" else if (q.requiresLogin) "需登录" else "免登录",
                            selected = selected,
                            onClick = { vm.setAudioQuality(q.code) },
                            trailing = {
                                QualityBadge(
                                    text = if (q.vipOnly) "大会员" else if (q.requiresLogin) "需登录" else "免登录",
                                    vip = q.vipOnly,
                                    login = q.requiresLogin && !q.vipOnly
                                )
                            }
                        )
                    }
                }
            }
            Text(
                "实际能拿到哪档由登录和会员状态决定，拿不到自动用可用最高档，不断播。切换后下一首生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun QualityBadge(text: String, vip: Boolean, login: Boolean) {
    val (fg, bg) = when {
        vip -> Color(0xFFD97706) to Color(0xFFFFFBEB)
        login -> Color(0xFF06B6D4) to Color(0xFFECFEFF)
        else -> Color(0xFF10B981) to Color(0xFFECFDF5)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}