package cn.debubu.tingbili.navigation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun CircularMiniPlayer(
    progress: Float,
    cover: String,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayPause: () -> Unit
) {
    val clamped = progress.coerceIn(0f, 1f)

    // Only create infinite transition when playing — avoids wasteful animation when paused
    val rotateModifier = if (isPlaying) {
        val infiniteTransition = rememberInfiniteTransition(label = "coverRotation")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(animation = tween(durationMillis = 3000, easing = LinearEasing)),
            label = "rotationAngle"
        )
        Modifier.rotate(angle)
    } else {
        Modifier
    }

    // Distinct interaction sources to avoid nested clickable ambiguity
    val outerInteraction = remember { MutableInteractionSource() }
    val innerInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = outerInteraction,
                indication = ripple(bounded = false, radius = 32.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { clamped },
            modifier = Modifier
                .fillMaxSize()
                .testTag("progressRing"),
            strokeWidth = 3.dp
        )
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .then(rotateModifier)
                .testTag("coverImage")
                .clickable(
                    interactionSource = innerInteraction,
                    indication = ripple(bounded = false, radius = 26.dp),
                    onClick = onPlayPause
                )
        )
    }
}
