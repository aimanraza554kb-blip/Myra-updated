package com.myra.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.myra.assistant.ui.theme.MyraPurple
import com.myra.assistant.ui.theme.MyraRed
import kotlin.math.min

/**
 * Glowing animated orb that pulses with the live audio amplitude. It breathes
 * continuously and reacts instantly to speech, giving MYRA a living presence.
 */
@Composable
fun AnimatedOrb(
    amplitude: Float,
    speaking: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = modifier) {
        val base = min(size.width, size.height) / 2f
        val pulse = base * (0.55f + amplitude * 0.45f) * breathe
        val center = Offset(size.width / 2f, size.height / 2f)

        // Outer glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(MyraRed.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = pulse * 1.9f
            ),
            radius = pulse * 1.9f,
            center = center
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(MyraPurple.copy(alpha = 0.30f), Color.Transparent),
                center = center,
                radius = pulse * 1.5f
            ),
            radius = pulse * 1.5f,
            center = center
        )
        // Core orb with a sweep gradient for a futuristic sheen. Slowly rotating
        // the gradient makes the sheen feel alive (previously `rotation` was
        // computed but never used).
        rotate(degrees = rotation, pivot = center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(MyraRed, MyraPurple, MyraRed),
                    center = center
                ),
                radius = pulse,
                center = center,
                alpha = if (speaking) 1f else 0.85f
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(center.x - pulse * 0.25f, center.y - pulse * 0.25f),
                radius = pulse * 0.8f
            ),
            radius = pulse * 0.8f,
            center = Offset(center.x - pulse * 0.15f, center.y - pulse * 0.15f)
        )
    }
}
