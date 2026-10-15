package cn.debubu.tingbili.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.theme.LocalDarkTheme

// 品牌色（B 站粉），暗色头部渐变按参考设计走深邃低饱和色
private val BiliPink = Color(0xFFFB7299)
private val BiliPinkLight = Color(0xFFFF69B4)
private val BiliBlue = Color(0xFF00A1D6)

/**
 * 「我的」Tab（B 站风格）：
 * 沉浸式渐变头部（未登录用户卡）→ 悬浮四宫格数据卡 → 分组设置列表 → 登录按钮。
 * 登录暂未开放（扫码登录单独立项），相关交互统一 Toast 占位。
 */
@Composable
fun MeScreen(
    onOpenAppearance: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOtherSettings: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLogin: () -> Unit = {},
    vm: MeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val darkTheme = LocalDarkTheme.current
    val playlistCount by vm.playlistCount.collectAsStateWithLifecycle()
    val historyToday by vm.historyTodayCount.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val totalListenMs by vm.totalListenMs.collectAsStateWithLifecycle()
    val darkMode by vm.darkMode.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }

    val placeholder: (String) -> Unit = { msg ->
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── 1. 沉浸式渐变头部 ─────────────────────────────
        MeHeader(
            darkTheme = darkTheme,
            totalListenMs = totalListenMs,
            session = session,
            onBadgeClick = { placeholder("听书专属版：敬请期待") },
            onToggleDark = { vm.toggleDarkMode(darkTheme) },
            onUserClick = { if (session == null) onOpenLogin() },
        )

        // ── 2. 悬浮四宫格数据卡（叠在头部下缘） ──────────────
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            DashboardGrid(
                playlistCount = playlistCount,
                historyToday = historyToday,
                cacheBytes = cacheBytes,
                onPlaylists = onOpenPlaylists,
                onHistory = onOpenHistory,
                onLaterListen = { placeholder("稍后听：暂未开放") },
                onCache = onOpenOtherSettings
            )
        }

        // ── 3. 分组设置列表 ──────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
            SectionLabel("个性化与播放")
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Filled.Palette,
                    iconTint = Color(0xFF6366F1),
                    iconBg = if (darkTheme) Color(0xFF1E1B4B) else Color(0xFFEEF2FF),
                    title = "外观设置",
                    subtitle = "主题色、界面选项",
                    trailing = { Text("主题适配", color = BiliPink, fontSize = 12.sp) },
                    onClick = onOpenAppearance
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    iconTint = Color(0xFF10B981),
                    iconBg = if (darkTheme) Color(0xFF062B22) else Color(0xFFECFDF5),
                    title = "播放与音频设置",
                    subtitle = "步长、定时关闭",
                    onClick = onOpenSettings
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionLabel("系统与账户")
            SettingsGroup {
                SettingsRow(
                    icon = Icons.Filled.Tune,
                    iconTint = Color(0xFF8B5CF6),
                    iconBg = if (darkTheme) Color(0xFF231038) else Color(0xFFF3E8FF),
                    title = "其他设置",
                    subtitle = "图片格式、缓存清理",
                    onClick = onOpenOtherSettings
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                SettingsRow(
                    icon = Icons.Filled.Info,
                    iconTint = Color(0xFF06B6D4),
                    iconBg = if (darkTheme) Color(0xFF083344) else Color(0xFFECFEFF),
                    title = "关于与帮助",
                    subtitle = "版本信息 · 开源与免责声明",
                    onClick = { showAbout = true }
                )
            }

            // 登录入口 / 退出
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (session != null) vm.logout() else onOpenLogin()
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (darkTheme) Color(0xFF2A1218) else Color(0xFFFFF1F4)
                )
            ) {
                Text(
                    text = if (session != null) "退出登录（${session?.userName?.ifBlank { "UID " + session?.uid }})" else "登录哔哩哔哩账号",
                    color = BiliPink,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

// ── 头部 ─────────────────────────────────────────────

@Composable
private fun MeHeader(
    darkTheme: Boolean,
    totalListenMs: Long,
    session: cn.debubu.tingbili.data.bilibili.auth.BiliSession?,
    onBadgeClick: () -> Unit,
    onToggleDark: () -> Unit,
    onUserClick: () -> Unit,
) {
    val gradient = if (darkTheme) {
        Brush.verticalGradient(listOf(Color(0xFF251A20), Color(0xFF1E1E1E), Color(0xFF1A1A1A)))
    } else {
        Brush.linearGradient(listOf(BiliPinkLight, BiliPink, Color(0xFFF45C8A)))
    }
    val onHeaderColor = if (darkTheme) Color(0xFFE5E5E5) else Color.White

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(gradient, RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)
    ) {
        // 顶部快捷操作行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (darkTheme) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.2f))
                    .clickable(onClick = onBadgeClick)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = null,
                    tint = if (darkTheme) BiliPink else Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("听书专属版", color = onHeaderColor, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 明暗快捷切换：亮色显示月亮（点击切深色），深色显示太阳（点击切浅色）
                HeaderIconButton(
                    icon = if (darkTheme) Icons.Filled.WbSunny else Icons.Filled.DarkMode,
                    darkTheme = darkTheme,
                    onClick = onToggleDark
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 用户信息（已登录显示头像昵称，未登录显示引导；未登录时点击进登录页）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(
                enabled = session == null,
                onClick = onUserClick,
            ),
        ) {
            if (session != null && session.face.isNotBlank()) {
                coil3.compose.AsyncImage(
                    model = session.face,
                    contentDescription = "用户头像",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                )
            } else {
                // 默认头像：白圈 + 人形
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (darkTheme) BiliPink.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = "未登录头像",
                        tint = BiliPink,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = session?.userName?.ifBlank { null } ?: "未登录",
                    color = onHeaderColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (session != null) "UID ${session.uid}" else "登录后同步你的收藏与历史",
                    color = onHeaderColor.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = Color(0xFFFCD34D),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (totalListenMs > 0L) {
                            val hours = totalListenMs / 3600000.0
                            if (hours >= 1.0) "已累计听视频 %.1f 小时".format(hours)
                            else "已累计听视频 ${totalListenMs / 60000} 分钟"
                        } else {
                            "开始收听，积累你的收听时长"
                        },
                        color = onHeaderColor.copy(alpha = 0.9f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderIconButton(icon: ImageVector, darkTheme: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (darkTheme) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

// ── 四宫格数据卡 ────────────────────────────────────────

@Composable
private fun DashboardGrid(
    playlistCount: Int,
    historyToday: Int,
    cacheBytes: Long,
    onPlaylists: () -> Unit,
    onHistory: () -> Unit,
    onLaterListen: () -> Unit,
    onCache: () -> Unit
) {
    val darkTheme = LocalDarkTheme.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = (-16).dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (darkTheme) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            GridItem(
                icon = Icons.Filled.Star, tint = BiliPink,
                bg = if (darkTheme) Color(0xFF3B1220) else Color(0xFFFFF0F5),
                title = "我的收藏", value = "$playlistCount 个", onClick = onPlaylists, weight = 1f
            )
            GridItem(
                icon = Icons.Filled.History, tint = BiliBlue,
                bg = if (darkTheme) Color(0xFF0A2933) else Color(0xFFE5F6FB),
                title = "播放历史", value = if (historyToday > 0) "今天 $historyToday 条" else "今天还没听", onClick = onHistory, weight = 1f
            )
            GridItem(
                icon = Icons.Filled.BookmarkBorder, tint = Color(0xFFA855F7),
                bg = if (darkTheme) Color(0xFF231038) else Color(0xFFF3E8FF),
                title = "稍后听", value = "敬请期待", onClick = onLaterListen, weight = 1f
            )
            GridItem(
                icon = Icons.Filled.Download, tint = Color(0xFFF59E0B),
                bg = if (darkTheme) Color(0xFF2E2306) else Color(0xFFFFF7E6),
                title = "离线缓存", value = formatCacheGb(cacheBytes), onClick = onCache, weight = 1f
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.GridItem(
    icon: ImageVector,
    tint: Color,
    bg: Color,
    title: String,
    value: String,
    onClick: () -> Unit,
    weight: Float
) {
    Column(
        modifier = Modifier
            .weight(weight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(1.dp))
        Text(value, fontSize = 10.sp, color = Color.Gray)
    }
}

// ── 分组设置列表 ────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Gray,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    val darkTheme = LocalDarkTheme.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (darkTheme) Color(0xFF1E1E1E) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = Color.Gray)
        }
        trailing?.invoke()
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ── 关于对话框 ────────────────────────────────────────

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于与帮助") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TingBili v$version", fontWeight = FontWeight.SemiBold)
                Text(
                    "B 站专用音频播放器（非官方）。仅供学习交流，禁止商用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Text(
                    "内容与账号数据均来自哔哩哔哩，如有侵权请联系删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

private fun formatCacheGb(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    else -> String.format("%.0f MB", bytes / (1024.0 * 1024.0))
}
