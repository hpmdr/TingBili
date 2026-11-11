package cn.debubu.tingbili.feature.mine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.data.update.UpdateChecker
import cn.debubu.tingbili.core.ui.component.SettingAccent
import cn.debubu.tingbili.core.ui.component.SettingDivider
import cn.debubu.tingbili.core.ui.component.SettingRow
import cn.debubu.tingbili.core.ui.component.SettingSectionLabel
import cn.debubu.tingbili.core.ui.component.SettingSwitchRow
import cn.debubu.tingbili.core.ui.component.SettingsGroup
import cn.debubu.tingbili.core.ui.component.TingBiliMessageBus
import cn.debubu.tingbili.core.ui.component.TingBiliScaffold
import cn.debubu.tingbili.core.ui.component.TingBiliTopAppBar
import cn.debubu.tingbili.core.ui.component.UpdateAvailableDialog
import cn.debubu.tingbili.core.ui.util.openUrlInBrowser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val GITHUB_URL = "https://github.com/hpmdr/TingBili"
private const val RELEASES_URL = "https://github.com/hpmdr/TingBili/releases/latest"

private const val DISCLAIMER =
    "本软件是第三方非官方客户端，与某站官方无任何隶属或授权关系；" +
        "软件内资源均来自互联网，与作者无关，内容版权归原作者及平台所有，" +
        "使用所产生的账号风险由使用者自行承担。\n\n" +
        "本程序仅供学习测试使用，请在 24 小时内删除，禁止商用。请支持正版。"

/** 二级页：关于（版本信息 / 更新检查 / 版权与免责声明 / 项目链接）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    vm: AboutViewModel = hiltViewModel(),
) {
    val autoCheck by vm.autoCheckUpdate.collectAsStateWithLifecycle()
    val lastCheckMs by vm.lastCheckMs.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val updateMessage = when (updateState) {
        is AboutViewModel.UpdateUiState.UpToDate -> "已是最新版本"
        is AboutViewModel.UpdateUiState.Failed -> "检查更新失败，请检查网络"
        else -> null
    }
    // 提示走全局总线：发消息即可，显示位置/队列由壳层唯一宿主负责
    LaunchedEffect(updateMessage) {
        updateMessage?.let {
            vm.consumeUpdateMessage()
            TingBiliMessageBus.show(it)
        }
    }

    TingBiliScaffold(
        topBar = {
            TingBiliTopAppBar(
                title = "关于",
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
            AboutHeader(version = vm.versionLabel)

            Column {
                SettingSectionLabel("更新")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.SystemUpdateAlt,
                        accent = SettingAccent.GREEN,
                        title = "检查更新",
                        subtitle = when {
                            updateState == AboutViewModel.UpdateUiState.Checking -> "正在检查…"
                            lastCheckMs > 0L -> "最近检查：${formatCheckTime(lastCheckMs)}"
                            else -> "尚未检查"
                        },
                        value = vm.versionLabel,
                        onClick = { vm.checkUpdate() },
                    )
                    SettingDivider()
                    SettingSwitchRow(
                        icon = Icons.Filled.Refresh,
                        accent = SettingAccent.CYAN,
                        title = "启动时自动检查",
                        subtitle = "每 12 小时最多一次",
                        checked = autoCheck,
                        onCheckedChange = { vm.setAutoCheckUpdate(it) },
                    )
                }
            }

            Column {
                SettingSectionLabel("版权与声明")
                SettingsGroup {
                    StatementBlock(
                        title = "版权信息",
                        body = "听视频 © 2026 hpmdr\n以 GNU AGPLv3 开源",
                    )
                    SettingDivider()
                    StatementBlock(
                        title = "免责声明",
                        body = DISCLAIMER,
                    )
                }
            }

            Column {
                SettingSectionLabel("链接")
                SettingsGroup {
                    SettingRow(
                        icon = Icons.Filled.Code,
                        accent = SettingAccent.PURPLE,
                        title = "GitHub 仓库",
                        subtitle = "查看源码与更新记录",
                        onClick = { openLink(context, GITHUB_URL) },
                    )
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Filled.Download,
                        accent = SettingAccent.BLUE,
                        title = "下载最新版本",
                        subtitle = "前往 Releases 页",
                        onClick = { openLink(context, RELEASES_URL) },
                    )
                }
            }
        }
    }

    val available = updateState as? AboutViewModel.UpdateUiState.Available
    if (available != null) {
        UpdateAvailableDialog(
            tag = available.tag,
            body = available.body,
            onSkipRelease = { vm.skipRelease() },
            onDismiss = { vm.dismissUpdate() },
            onDownload = {
                vm.dismissUpdate()
                val url = available.apkUrl ?: UpdateChecker.RELEASES_PAGE
                if (!openUrlInBrowser(context, url)) {
                    TingBiliMessageBus.show("无法打开浏览器")
                }
            },
        )
    }
}

@Composable
private fun AboutHeader(version: String) {
    val context = LocalContext.current
    val appIcon = remember(context) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val size = 192
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        appIcon?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("听视频", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            version,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatementBlock(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openLink(context: Context, url: String) {
    if (!openUrlInBrowser(context, url)) {
        TingBiliMessageBus.show("无法打开浏览器")
    }
}

/** 上次检查时间：今天/昨天显示时分，更早显示日期。 */
private fun formatCheckTime(ms: Long): String {
    if (ms <= 0L) return "尚未检查"
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(ms).atZone(zone)
    val today = LocalDate.now(zone)
    val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    return when (time.toLocalDate()) {
        today -> "今天 ${time.format(hourMinute)}"
        today.minusDays(1) -> "昨天 ${time.format(hourMinute)}"
        else -> time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))
    }
}
