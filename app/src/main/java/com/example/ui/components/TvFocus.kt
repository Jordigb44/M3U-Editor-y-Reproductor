package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val TvFocusHighlightColor = Color(0xFF00E5FF) // High-contrast Neon Cyan Glow for TV Focus

@Composable
fun Modifier.dpadFocusable(
    shape: Shape = RoundedCornerShape(16.dp),
    focusBorderWidth: Dp = 4.dp,
    focusColor: Color = TvFocusHighlightColor,
    onFocusChange: ((Boolean) -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }

    val scaleValue by animateFloatAsState(targetValue = if (isFocused) 1.04f else 1.0f, label = "focusScale")
    val borderWidth by animateDpAsState(targetValue = if (isFocused) focusBorderWidth else 0.dp, label = "borderWidth")
    val borderColor = if (isFocused) focusColor else Color.Transparent

    return this
        .onFocusChanged { 
            isFocused = it.isFocused 
            onFocusChange?.invoke(it.isFocused)
        }
        .focusable()
        .scale(scaleValue)
        .border(width = borderWidth, color = borderColor, shape = shape)
}
