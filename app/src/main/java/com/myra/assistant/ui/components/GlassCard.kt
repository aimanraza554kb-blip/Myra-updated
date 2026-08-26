package com.myra.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.myra.assistant.ui.theme.MyraGlassBorder

/**
 * Glassmorphism container: translucent surface, soft border and rounded corners.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MyraGlassBorder),
        tonalElevation = 0.dp
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}
