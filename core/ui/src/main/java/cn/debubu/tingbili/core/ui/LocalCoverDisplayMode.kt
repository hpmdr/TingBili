package cn.debubu.tingbili.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import cn.debubu.tingbili.core.data.model.CoverDisplayMode

val LocalCoverDisplayMode = staticCompositionLocalOf { CoverDisplayMode.BV_COVER }
val LocalSinglePartUsesBvCover = staticCompositionLocalOf { true }
