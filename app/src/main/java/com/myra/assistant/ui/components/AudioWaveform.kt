package com.myra.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.myra.assistant.ui.theme.MyraPurple
import com.myra.assistant.ui.theme.MyraRed
import kotlin.math.max

/**
 * Scrolling audio waveform driven by the live amplitude. New samples push in
 * from the right, creating a smooth real-time visualization.
 */
@Composable
fun AudioWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 48
) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(barCount) { add(0.05f) } } }
    // Push the newest amplitude into the ring buffer as a *side effect*. Mutating
    // snapshot state directly during composition (as the old code did) causes
    // recomposition loops and dropped frames; keying on amplitude fixes that.
    LaunchedEffect(amplitude) {
        if (bars.size >= barCount) bars.removeAt(0)
        bars.add(max(0.05f, amplitude))
    }

    Canvas(modifier = modifier) {
        val gap = size.width / barCount
        val brush = Brush.horizontalGradient(listOf(MyraRed, MyraPurple))
        bars.forEachIndexed { index, value ->
            val barHeight = value * size.height
            val x = index * gap + gap / 2f
            drawLine(
                brush = brush,
                start = Offset(x, size.height / 2f - barHeight / 2f),
                end = Offset(x, size.height / 2f + barHeight / 2f),
                strokeWidth = gap * 0.5f,
                cap = StrokeCap.Round
            )
        }
    }
}
