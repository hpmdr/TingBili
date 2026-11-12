package cn.debubu.tingbili.core.ui.component

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 歌词行的视觉层级：当前行 / 近邻下文 / 已播或远期。小卡与全屏共用同一套判定，避免手工对齐后走样。 */
enum class LyricTone { Current, Near, Faded }

/** 当前行之后仍算“近邻”的行数；已播行与更远的后文同档（都淡化） */
private const val NEAR_COUNT = 3

private fun lyricToneOf(index: Int, currentIndex: Int): LyricTone = when {
    index == currentIndex -> LyricTone.Current
    index in (currentIndex + 1)..(currentIndex + NEAR_COUNT) -> LyricTone.Near
    else -> LyricTone.Faded
}

/** 用户手动接管后，静置这么久再回到高亮行 */
private const val FOLLOW_RESUME_DELAY_MS = 4_000L

/** 小卡窗口：当前行上方保留的行数 */
private const val WINDOW_LEAD = 2

/**
 * 歌词列表：小卡的窗口模式与全屏的跟随模式共用。
 *
 * 两种模式只差“当前行停在哪”：
 * - [windowSize] > 0：只渲染当前行附近的一段窗口，窗口首行恒贴顶，一次 `scrollToItem(0)` 就位。
 * - [windowSize] == 0：全部行参与滚动，当前行**顶边**停在 [anchorRatio] 对应的视口高度上。
 *
 * 跟随滚动必须与高亮换行**同帧**生效，否则会看到“颜色先跳到下一行、位移下一帧才补上”的
 * 两拍错位。要点是位移量在滚动前就已知、不需要先测量：内容上边距恰好等于锚线高度，
 * 于是 `requestScrollToItem(idx, 0)` 直接把当前行顶边放到锚线上，由同一次布局消费。
 *
 * 反过来，任何“先 scrollToItem 粗定位、再读 layoutInfo 算 delta 补一次”的写法都必然慢一帧：
 * 读 layoutInfo 要等布局完成，补偿滚动就落到下一帧，正是那个闪跳的来源。
 */
@Composable
fun <T> TingBiliLyricList(
    items: List<T>,
    currentIndex: Int,
    key: (index: Int, item: T) -> Any,
    modifier: Modifier = Modifier,
    windowSize: Int = 0,
    anchorRatio: Float = 1f / 3f,
    footer: (@Composable () -> Unit)? = null,
    row: @Composable (index: Int, item: T, tone: LyricTone) -> Unit,
) {
    val listState = rememberLazyListState()

    if (windowSize > 0) {
        val windowStart = (currentIndex - WINDOW_LEAD).coerceAtLeast(0)
        LaunchedEffect(windowStart, items.size) {
            if (items.isNotEmpty()) listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(
                items = items.subList(windowStart, minOf(items.size, windowStart + windowSize)),
                key = { idx, item -> key(windowStart + idx, item) },
            ) { idx, item ->
                val globalIdx = windowStart + idx
                row(globalIdx, item, lyricToneOf(globalIdx, currentIndex))
            }
        }
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 锚线高度 = 内容上边距：当前行顶边正好压在这条线上，所以滚动目标无需测量。
        // 按顶边（而非行中心）锚定，位移量就与行高无关——当前行字号比其他行大，
        // 按中心锚定会让位移随行高变化，又要多一次行高差补偿。
        val topPadding = maxHeight * anchorRatio
        val bottomPadding = maxHeight * (1f - anchorRatio)
        // 手指拖动时暂停自动跟随，松手静置几秒后恢复；跟随是程序化滚动，
        // 不产生 DragInteraction，不会和这里互相误触。
        var followSuspended by remember { mutableStateOf(false) }
        LaunchedEffect(listState) {
            var resumeJob: Job? = null
            try {
                listState.interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> {
                            resumeJob?.cancel()
                            resumeJob = null
                            followSuspended = true
                        }
                        is DragInteraction.Stop, is DragInteraction.Cancel -> {
                            resumeJob?.cancel()
                            resumeJob = launch {
                                // 惯性滚动走完再开始计时，避免还在滑就被拽回高亮
                                snapshotFlow { listState.isScrollInProgress }.first { !it }
                                delay(FOLLOW_RESUME_DELAY_MS)
                                followSuspended = false
                            }
                        }
                    }
                }
            } finally {
                resumeJob?.cancel()
            }
        }
        // 跟随滚动必须与高亮换行同帧提交，所以用 SideEffect 而不是 LaunchedEffect：
        // 后者的协程要等 apply 阶段之后才启动，那时本帧 layout 已开始，滚动只能落到下一帧。
        // SideEffect 在组合提交后、layout 之前执行，requestScrollToItem 非挂起，只写待处理位置，
        // 正好被本次布局消费。逐行推进、拖进度、点歌词跳转都走这一条路径，时序一致。
        //
        // lastRequested 刻意不是 snapshot state：在 SideEffect 里写 state 会再触发重组形成循环。
        val lastRequested = remember { intArrayOf(Int.MIN_VALUE) }
        SideEffect {
            if (followSuspended || currentIndex !in items.indices) {
                lastRequested[0] = Int.MIN_VALUE
                return@SideEffect
            }
            // 只在目标行变化时写入，避免每帧重复请求让布局反复失效
            if (lastRequested[0] != currentIndex) {
                listState.requestScrollToItem(currentIndex, scrollOffset = 0)
                lastRequested[0] = currentIndex
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(items = items, key = { idx, item -> key(idx, item) }) { idx, item ->
                row(idx, item, lyricToneOf(idx, currentIndex))
            }
            if (footer != null) {
                item(key = "lyric-footer") { footer() }
            }
        }
    }
}
