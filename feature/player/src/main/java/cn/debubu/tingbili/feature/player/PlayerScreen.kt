package cn.debubu.tingbili.feature.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import cn.debubu.tingbili.core.media.PlayerManager

@Composable
fun PlayerScreen(
    vm: PlayerViewModel = hiltViewModel(),
    player: PlayerManager? = null
) {
    val s by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Auto-scroll to current lyric
    LaunchedEffect(s.currentLyricIndex) {
        if (s.currentLyricIndex >= 0) {
            listState.animateScrollToItem(s.currentLyricIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Rotating cover
        val transition = rememberInfiniteTransition(label = "cover")
        val angle by if (s.isPlaying) {
            transition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
                label = "rot"
            )
        } else {
            androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }.let {
                androidx.compose.runtime.derivedStateOf { 0f }.value.let { v -> androidx.compose.runtime.remember { v } }
                // fallback: no animation when paused
                androidx.compose.runtime.mutableStateOf(0f)
            }
        }
        // Simpler guard: only rotate when playing
        val rotateMod = if (s.isPlaying) {
            val t = rememberInfiniteTransition(label = "cover2")
            val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "a2")
            Modifier.rotate(a)
        } else Modifier

        AsyncImage(
            model = s.track?.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(240.dp).clip(CircleShape).then(rotateMod)
        )
        Spacer(Modifier.height(12.dp))
        Text(s.track?.title ?: "未播放", style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(s.track?.author ?: "", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Spacer(Modifier.height(12.dp))
        // Lyric area
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (s.lyrics.isEmpty()) {
                item { Text("暂无字幕", color = Color.Gray, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            } else {
                itemsIndexed(s.lyrics) { idx, line ->
                    val isCurrent = idx == s.currentLyricIndex
                    Text(
                        text = line.text,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray,
                        style = if (isCurrent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable {
                            // seek to lyric time via PlayerManager exposed via vm
                            // vm does not expose direct seek, use player state position
                        }.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // Progress
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(formatMs(s.positionMs), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f,
                onValueChange = { /* seek handled via PlayerManager in real app */ },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(formatMs(s.durationMs), style = MaterialTheme.typography.bodySmall)
        }
        // Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { /* previous */ }) { Icon(Icons.Default.SkipPrevious, contentDescription = "上一首") }
            IconButton(onClick = { /* toggle */ }) {
                Icon(
                    if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (s.isPlaying) "暂停" else "播放"
                )
            }
            IconButton(onClick = { /* next */ }) { Icon(Icons.Default.SkipNext, contentDescription = "下一首") }
            Text("${s.speed}x", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val sec = (ms / 1000).toInt()
    val m = sec / 60
    val s = sec % 60
    return if (m >= 60) {
        val h = m / 60; val mm = m % 60; "%d:%02d:%02d".format(h, mm, s)
    } else "%d:%02d".format(m, s)
}
