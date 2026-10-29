package cn.debubu.tingbili.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * M3 动效（motionDurationLong1 = 300ms）：
 * - 层级进退（详情/搜索/设置/收藏…）：shared-axis X 横滑，前进从右侧进、返回反向。
 * - Tab 切换：fade-through，无位移。
 * - 播放器：纵向 slide-up 进、slide-down 退（与 mini 条同一形态的两种展开），且进出播放器时相邻页面只淡入淡出、不横滑抢戏。
 *
 * 注意 navigation-compose 2.10 的库默认：普通进退 700ms 纯 fade、
 * 预测性返回 scaleOut(0.7) 缩到中间；且手势返回只走 NavHost 层的
 * predictivePop*（无视各 destination 的 pop*），所以 predictive 必须和 pop 镜像，
 * 否则按钮返回和侧滑返回会是两套动作。
 */
internal const val M3_MOTION_DURATION_MS = 300

private fun m3Tween() = tween<Float>(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing)

// —— shared-axis X：二级页默认 ——

internal val SharedAxisXEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
    ) + fadeIn(animationSpec = m3Tween())
}

internal val SharedAxisXExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    // 正进入播放器：播放器从底部升起盖住，底下淡出即可，别跟着横滑（否则双向抢戏）
    if (targetState.destination.hasRoute<PlayerRoute>()) {
        fadeOut(animationSpec = m3Tween())
    } else {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / 4 },
            animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
        ) + fadeOut(animationSpec = m3Tween())
    }
}

internal val SharedAxisXPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 4 },
        animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
    ) + fadeIn(animationSpec = m3Tween())
}

internal val SharedAxisXPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    // 正回到播放器：淡出即可（播放器侧淡入），别横滑抢戏
    if (targetState.destination.hasRoute<PlayerRoute>()) {
        fadeOut(animationSpec = m3Tween())
    } else {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth },
            animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
        ) + fadeOut(animationSpec = m3Tween())
    }
}

// —— 预测性返回：与 pop 镜像，覆盖库默认的缩到中间 ——

internal val PredictiveSharedAxisXPopEnter:
    AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = { _ ->
        // 手势起点是播放器：只淡入（播放器侧纵滑下落），别横滑抢戏
        if (initialState.destination.hasRoute<PlayerRoute>()) {
            fadeIn(animationSpec = m3Tween())
        } else {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth / 4 },
                animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = m3Tween())
        }
    }

internal val PredictiveSharedAxisXPopExit:
    AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { _ ->
        // 手势起点是播放器：纵滑下落（与按钮返回一致），否则横滑
        if (initialState.destination.hasRoute<PlayerRoute>()) {
            slideOutVertically(
                targetOffsetY = { fullHeight -> fullHeight },
                animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = m3Tween())
        } else {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = m3Tween())
        }
    }

// Tab 切换不再走淡入转场：4 个 Tab 由 HorizontalPager 承载，切换即横向位移，
// 点击底栏瞬时切页（M3 同层级视图不做转场动画），故 TabFade* 已删除。

// —— 播放器：纵向 ——
// 从播放器继续前进（点相关视频/设置）时底下淡出；手势返回时若停留在播放器
// 则走 NavHost 层 predictive（横滑镜像），这是库限制（composable 不支持
// predictive 参数），按钮返回仍是纵滑。

internal val PlayerEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInVertically(
        initialOffsetY = { fullHeight -> fullHeight },
        animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
    ) + fadeIn(animationSpec = m3Tween())
}

internal val PlayerExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = m3Tween())
}

internal val PlayerPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = m3Tween())
}

internal val PlayerPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutVertically(
        targetOffsetY = { fullHeight -> fullHeight },
        animationSpec = tween(M3_MOTION_DURATION_MS, easing = FastOutSlowInEasing),
    ) + fadeOut(animationSpec = m3Tween())
}
