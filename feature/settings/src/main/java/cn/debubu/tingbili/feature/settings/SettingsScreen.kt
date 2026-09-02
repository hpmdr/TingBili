package cn.debubu.tingbili.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = hiltViewModel()
) {
    val step by vm.stepSec.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColor.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)

        // 步进
        Column {
            Text("自定义步进秒数: $step 秒", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = step.toFloat(),
                onValueChange = { vm.setStep(it.toInt()) },
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

        // 定时预设
        Column {
            Text("定时预设（分钟）", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 90).forEach { m ->
                    Button(onClick = { /* timer preset handled via PlayerManager in real app */ }) {
                        Text("${m}分")
                    }
                }
            }
        }

        // 主题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("动态取色 (Material3)", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = dynamic, onCheckedChange = { vm.setDynamicColor(it) })
        }

        Spacer(Modifier.height(16.dp))
        Text("预留登录入口 (二期)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Text("登录后可导入 B 站收藏夹为歌单", style = MaterialTheme.typography.bodySmall)
    }
}
