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

// Deep OLED, high-contrast palette tuned for the 10-foot TV interface.
// Background is near-black navy; surfaces are gently elevated; the accent family is
// indigo (primary), cyan/teal (secondary) and emerald (tertiary) for clear hierarchy.
private val TvDarkColors = darkColorScheme(
    primary = Color(0xFF6D5EF0),            // indigo - main actions / focus
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4F46E5),    // selected chips, cards, active states
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF22D3EE),           // cyan accent
    onSecondary = Color(0xFF062A33),
    secondaryContainer = Color(0xFF155E75),  // teal secondary buttons / external
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = Color(0xFF34D399),            // emerald - demo / success
    onTertiary = Color(0xFF052E1B),
    tertiaryContainer = Color(0xFF047857),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFECF1F9),
    surface = Color(0xFF0F1626),
    onSurface = Color(0xFFECF1F9),
    surfaceVariant = Color(0xFF1A2338),
    onSurfaceVariant = Color(0xFFA0ACC2),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    outline = Color(0xFF38435C),
    outlineVariant = Color(0xFF2A3348),
    inverseSurface = Color(0xFF1A2338),
    inverseOnSurface = Color(0xFFECF1F9),
    inversePrimary = Color(0xFF8B7CF7),
    surfaceTint = Color(0xFF6D5EF0)
)

private val TvLightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF312E81),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF065F46),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    surfaceTint = Color(0xFF4F46E5)
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
