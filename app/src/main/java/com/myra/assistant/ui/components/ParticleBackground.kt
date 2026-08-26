package com.myra.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.myra.assistant.ui.theme.MyraPurple
import com.myra.assistant.ui.theme.MyraRed
import kotlin.random.Random

private data class Particle(val x: Float, val y: Float, val radius: Float, val speed: Float, val red: Boolean)

/**
 * Subtle floating particle field behind the main content, adding depth to the
 * premium futuristic look. Particles drift upward and wrap around.
 */
@Composable
fun ParticleBackground(modifier: Modifier = Modifier, count: Int = 40) {
    val particles = remember {
        List(count) {
            Particle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                radius = Random.nextFloat() * 3f + 1f,
                speed = Random.nextFloat() * 0.4f + 0.1f,
                red = Random.nextBoolean()
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "particles")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing)
        ),
        label = "progress"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        particles.forEach { p ->
            val y = (p.y - progress * p.speed).mod(1f)
            val color = if (p.red) MyraRed else MyraPurple
            drawCircle(
                color = color.copy(alpha = 0.25f),
                radius = p.radius,
                center = Offset(p.x * size.width, y * size.height)
            )
        }
    }
}
