package com.example.tv.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** High-contrast neon cyan glow used for D-pad focus on TV. */
val TvFocusHighlightColor = Color(0xFF00E5FF)

/**
 * TV focus modifier: scales up slightly and draws an animated glowing border when focused.
 * Use on every D-pad navigable element (buttons, cards, list rows).
 */
@Composable
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(16.dp),
    focusBorderWidth: Dp = 4.dp,
    focusColor: Color = TvFocusHighlightColor,
    onClick: (() -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }

    val scaleValue by animateFloatAsState(targetValue = if (isFocused) 1.06f else 1.0f, label = "tvFocusScale")
    val borderWidth by animateDpAsState(targetValue = if (isFocused) focusBorderWidth else 0.dp, label = "tvFocusBorder")

    val focusModifier = this.onFocusChanged { 
        isFocused = it.isFocused 
        onFocusChange?.invoke(it.isFocused)
    }

    val interactiveModifier = if (onClick != null) {
        focusModifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else {
        focusModifier.focusable()
    }

    return interactiveModifier
        .scale(scaleValue)
        .border(width = borderWidth, color = if (isFocused) focusColor else Color.Transparent, shape = shape)
}

/**
 * Manual selection ring for elements driven by an explicit selection index instead of
 * focus traversal (used by the player control bar, where the fullscreen background box
 * would otherwise swallow D-pad navigation).
 */
@Composable
fun Modifier.tvRing(
    selected: Boolean,
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier {
    val scaleValue by animateFloatAsState(targetValue = if (selected) 1.08f else 1f, label = "tvRingScale")
    val borderWidth by animateDpAsState(targetValue = if (selected) 4.dp else 0.dp, label = "tvRingBorder")
    return this
        .scale(scaleValue)
        .border(width = borderWidth, color = TvFocusHighlightColor, shape = shape)
}
