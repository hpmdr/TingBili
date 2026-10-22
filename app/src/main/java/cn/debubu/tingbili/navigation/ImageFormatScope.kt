package cn.debubu.tingbili.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.debubu.tingbili.core.ui.LocalImageFormat

/**
 * 图片格式的作用域：只包图片密集的页（首页/搜索/详情/歌单/历史/播放/mini 条）。
 *
 * 之前包在 MainActivity 整棵 NavHost 外，切一次格式 15 个 destination 全重组 +
 * 所有 AsyncImage 换 key 重载；现在只重组当前页。未包的页走 AVIF 默认。
 */
@Composable
fun ImageFormatScope(
    viewModel: MainViewModel,
    content: @Composable () -> Unit,
) {
    val imageFormat by viewModel.imageFormat.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalImageFormat provides imageFormat, content = content)
}
