package com.example.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Deep OLED dark palette tuned for the 10-foot TV interface.
private val TvDarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF0C4A6E),
    secondaryContainer = Color(0xFF0369A1),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF064E3B),
    tertiaryContainer = Color(0xFF047857),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF080C14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF101828),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1C2740),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    outline = Color(0xFF334155)
)

private val TvLightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF312E81),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569)
)

// Larger typography for readability at 10 feet.
private val TvTypography = Typography(
    titleLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    titleMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
    labelMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    labelSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
)

@Composable
fun TvTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TvDarkColors else TvLightColors,
        typography = TvTypography,
        content = content
    )
}
