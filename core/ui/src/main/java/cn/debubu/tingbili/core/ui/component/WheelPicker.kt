package cn.debubu.tingbili.core.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * iOS 风格滚轮选择器（从播放页睡眠定时的 MinuteWheel 下沉，通用化）。
 *
 * - [items] 各格文本；[selectedIndex] 当前选中下标（外部状态为准，滚动停稳后经 [onSettled] 回写）。
 * - 视口 3 格高、中心格高亮 + 上下刻度线；[unit] 钉死中间行右侧，不跟滚（对标 iOS 倒计时滚轮）。
 * - 调用方负责 value<->index 映射（如 30 分钟一格、500MB 一档）。
 * - 滚动中冻结上一次的高亮、停稳才结算一次，避免闪烁。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    require(items.isNotEmpty())
    val itemH = 48.dp
    val safeIndex = selectedIndex.coerceIn(items.indices)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)
    // 视口中心项：滚动中每帧都变，只做计算不做高亮避免闪烁
    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) null
            else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull {
                    kotlin.math.abs((it.offset + it.size / 2) - center)
                }?.index
            }
        }
    }
    // 停稳后的选择下标：滚动中冻结在上一次的值高亮不动，只有停稳才结算一次
    var settled by remember(selectedIndex) { mutableIntStateOf(safeIndex) }
    // 外部值变化滚到对应格；自己结算时与列表一致为空操作
    LaunchedEffect(selectedIndex) {
        listState.scrollToItem(safeIndex)
    }
    LaunchedEffect(centerIndex, listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            centerIndex?.let {
                val idx = it.coerceIn(items.indices)
                settled = idx
                if (idx != safeIndex) onSettled(idx)
            }
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            modifier = Modifier
                .fillMaxWidth()
                .height(itemH * 3),
            contentPadding = PaddingValues(vertical = itemH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(items.size, key = { it }) { i ->
                val selected = i == settled
                Text(
                    text = items[i],
                    modifier = Modifier.fillMaxWidth().height(itemH)
                        .wrapContentHeight(Alignment.CenterVertically),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 中线刻度：只画上下两条细线，不给内容铺背景
        Column(modifier = Modifier.fillMaxWidth().height(itemH * 3)) {
            Spacer(modifier = Modifier.height(itemH))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(itemH))
        }
        // 单位钉死在中间行右侧：彻底移出滚动列表，怎么划都不动
        if (unit != null) {
            Text(
                text = unit,
                modifier = Modifier.align(Alignment.Center).offset(x = 34.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}