package cn.debubu.tingbili.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import cn.debubu.tingbili.core.data.model.CoverDisplayMode

val LocalCoverDisplayMode = staticCompositionLocalOf { CoverDisplayMode.BV_COVER }
val LocalSinglePartUsesBvCover = staticCompositionLocalOf { true }
/** 章节行是否带队列序号前缀（播放设置「显示序号」；播放页/迷你条共用，通知栏走 PlayerManager 同一偏好） */
val LocalShowQueueNumber = staticCompositionLocalOf { true }
