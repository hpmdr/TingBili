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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar

/** 二级页：本机音频解码器（MediaCodecList 实测，只展示 audio/ 解码器，供问题定位）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDecodersScreen(
    onBack: () -> Unit,
    vm: AudioDecodersViewModel = hiltViewModel()
) {
    val decoders by vm.decoders.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "本机音频解码器",
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "MediaCodec 实测的 audio/ 解码器，按硬解优先排序，仅供问题定位（厂商前缀只看不判）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            if (loading) {
                Text("正在读取本机解码器...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else if (decoders.isEmpty()) {
                Text("暂无数据，点下方重读。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            } else {
                Text(
                    "共 ${decoders.size} 项（硬解优先排序）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                decoders.take(200).forEach { d ->
                    DecoderRow(name = d.name, tag = d.kindLabel + " · " + d.vendorLabel, mime = d.mimeType)
                }
                if (decoders.size > 200) {
                    Text("仅显示前 200 项。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.refresh() }) { Text("重新读取") }
        }
    }
}

@Composable
private fun DecoderRow(name: String, tag: String, mime: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium)
            Text(text = mime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.width(8.dp))
        Text(text = tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
