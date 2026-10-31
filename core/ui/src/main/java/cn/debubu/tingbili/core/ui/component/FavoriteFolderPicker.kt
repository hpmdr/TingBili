package cn.debubu.tingbili.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.debubu.tingbili.core.data.model.Track
import java.util.Locale

data class FavoriteFolderOption(
    val id: Long,
    val name: String,
    val isDefault: Boolean = false,
    val checked: Boolean = false,
)

/**
 * 选 P + 选收藏夹（可多选）。只负责收集选择，落库由调用方处理。
 */
@Composable
fun FavoriteFolderMultiPickerSheet(
    title: String,
    tracks: List<Track>,
    folders: List<FavoriteFolderOption>,
    onDismiss: () -> Unit,
    onConfirm: (tracks: List<Track>, folderIds: Set<Long>) -> Unit,
    onCreateFolder: (name: String, tracks: List<Track>) -> Unit,
    subtitle: String? = null,
    initialSelectedTrackKeys: Set<String> = emptySet(),
) {
    fun keyOf(track: Track) = "${track.bvid}:${track.cid}"
    val multiTrack = tracks.size > 1
    var selectedTrackKeys by remember(tracks, initialSelectedTrackKeys) {
        mutableStateOf(
            if (multiTrack) initialSelectedTrackKeys
            else tracks.map(::keyOf).toSet()
        )
    }
    var selectedFolderIds by remember { mutableStateOf(emptySet<Long>()) }
    var showCreate by remember { mutableStateOf(false) }

    val selectedTracks = tracks.filter { keyOf(it) in selectedTrackKeys }

    TingBiliBottomSheet(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(
                enabled = selectedTracks.isNotEmpty() && selectedFolderIds.isNotEmpty(),
                onClick = { onConfirm(selectedTracks, selectedFolderIds) },
            ) { Text("收藏") }
        },
    ) {
        if (multiTrack) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择分P", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { selectedTrackKeys = emptySet() },
                ) { Text("全不选") }
                TextButton(
                    onClick = { selectedTrackKeys = tracks.map(::keyOf).toSet() },
                ) { Text("全选") }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(tracks, key = { keyOf(it) }) { track ->
                    val key = keyOf(track)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTrackKeys = if (key in selectedTrackKeys) {
                                    selectedTrackKeys - key
                                } else {
                                    selectedTrackKeys + key
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = key in selectedTrackKeys,
                            onCheckedChange = {
                                selectedTrackKeys = if (it) selectedTrackKeys + key else selectedTrackKeys - key
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildString {
                                    append("P").append(track.pageIndex ?: "?")
                                    if (track.title.isNotBlank()) append(" · ").append(track.title)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = formatTrackDuration(track.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        Text("收藏到", style = MaterialTheme.typography.titleSmall)
        if (folders.isEmpty()) {
            Text(
                text = "暂无收藏夹，先新建一个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                items(folders, key = { it.id }) { folder ->
                    val checked = folder.id in selectedFolderIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedFolderIds = if (checked) {
                                    selectedFolderIds - folder.id
                                } else {
                                    selectedFolderIds + folder.id
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selectedFolderIds = if (it) selectedFolderIds + folder.id
                                else selectedFolderIds - folder.id
                            },
                        )
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (folder.isDefault) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "默认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                enabled = selectedTracks.isNotEmpty(),
                onClick = { showCreate = true },
            ) { Text("+ 新建收藏夹") }
        }
    }

    if (showCreate) {
        CreateFolderDialog(
            onConfirm = {
                showCreate = false
                onCreateFolder(it, selectedTracks)
            },
            onDismiss = { showCreate = false },
        )
    }
}

/**
 * 单选收藏夹弹层：点一行切换该 P 的归属并关闭；包含「我的最爱」。
 */
@Composable
fun FavoriteFolderSinglePickerSheet(
    title: String,
    subtitle: String?,
    folders: List<FavoriteFolderOption>,
    onDismiss: () -> Unit,
    onToggle: (folderId: Long) -> Unit,
    onCreateFolder: (name: String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }

    TingBiliBottomSheet(
        title = title,
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        if (folders.isEmpty()) {
            Text(
                text = "暂无收藏夹，先新建一个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(folders, key = { it.id }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(folder.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = folder.checked,
                            onCheckedChange = { onToggle(folder.id) },
                        )
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (folder.isDefault) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "默认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { showCreate = true }) { Text("+ 新建收藏夹") }
        }
    }

    if (showCreate) {
        CreateFolderDialog(
            onConfirm = {
                showCreate = false
                onCreateFolder(it)
            },
            onDismiss = { showCreate = false },
        )
    }
}

private fun formatTrackDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
