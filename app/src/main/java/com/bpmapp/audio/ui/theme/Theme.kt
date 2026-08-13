// SPDX-License-Identifier: GPL-3.0-or-later

package com.bpmapp.audio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Music-themed color palette for RunBeat
// Using Material Design 3 tonal palettes with energetic colors suitable for music

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),

    secondary = Color(0xFF03DAC5),
    onSecondary = Color(0xFF003639),
    secondaryContainer = Color(0xFF004E51),
    onSecondaryContainer = Color(0xFF9AF5F2),

    tertiary = Color(0xFF8A4EA7),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF723B8F),
    onTertiaryContainer = Color(0xFFFFD6F4),

    background = Color(0xFF1C1B1F),
    surface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFF252226),
    onBackground = Color(0xFFE6E0E9),
    onSurface = Color(0xFFE6E0E9),
    onSurfaceVariant = Color(0xFFD1CBD7),
    outline = Color(0xFF7C747E),
    outlineVariant = Color(0xFF2F2B30),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6E0E9),
    inverseOnSurface = Color(0xFF1C1B1F),
    inversePrimary = Color(0xFF673AB7),
    surfaceTint = Color(0xFFD0BCFF),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFFFFF),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF381E72),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005E),

    secondary = Color(0xFF006870),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9AF5F2),
    onSecondaryContainer = Color(0xFF001F22),

    tertiary = Color(0xFF723B8F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD6F4),
    onTertiaryContainer = Color(0xFF2A004D),

    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    surfaceVariant = Color(0xFFE7E0EC),
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF322A38),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF1C1B1F),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFD0BCFF),
    surfaceTint = Color(0xFF381E72),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// Custom BPM semantic colors
// BPM Green is used for good speed factor matches (0.9-1.1)
val BpmGreen = Color(0xFF00C853)
val BpmGreenLight = Color(0xFF80E27E)
val BpmGreenDark = Color(0xFF2E7D32)

// Semantic color for BPM display - accessible in both themes
val bpmSemanticColor: Color
    @Composable get() = if (isSystemInDarkTheme()) BpmGreen else BpmGreenDark

// Typography system following Material Design 3
// Removed unused styles: displayLarge, displayMedium, displaySmall, headlineLarge
// as per Proposition 1.2
private val AppTypography = androidx.compose.material3.Typography(
    // Display styles - only headlineMedium, headlineSmall kept
    displayLarge = TextStyle.Default, // Placeholder - unused
    displayMedium = TextStyle.Default, // Placeholder - unused
    displaySmall = TextStyle.Default, // Placeholder - unused
    headlineLarge = TextStyle.Default, // Placeholder - unused
    
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

// Shape system for consistent rounded corners
private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

// Spacing system following 8dp baseline grid
// As per Proposition 1.3 - maintain current system
object AppSpacing {
    val xxxs = 4.dp
    val xxs = 8.dp
    val xs = 12.dp
    val sm = 16.dp
    val md = 24.dp
    val lg = 32.dp
    val xl = 40.dp
    val xxl = 48.dp
    val xxxl = 56.dp
}

// Component-level spacing for internal padding and arrangement
object ComponentSpacing {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val cardElevation = 4.dp
    val buttonHeightLarge = 48.dp
    val progressBarHeight = 4.dp
}

// Touch target modifier extension for MD3 compliance (48dp minimum)
// As per Proposition 4.1
fun Modifier.touchTarget() = this.then(Modifier.size(AppSpacing.xxl))

// Speed factor color coding utility
// As per Proposition 4.2 and 5.1
@Composable
fun speedFactorColor(factor: Float?): Color {
    if (factor == null) {
        return MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    
    return when {
        factor < 0.9f || factor > 1.1f -> MaterialTheme.colorScheme.error
        factor == 1.0f -> bpmSemanticColor
        else -> MaterialTheme.colorScheme.secondary
    }
}

// BPM value color based on speed factor
// Green = good match (within 10%), Red = needs adjustment, Gray = unknown
@Composable
fun bpmColor(speedFactor: Float? = null): Color {
    return if (speedFactor != null) {
        speedFactorColor(speedFactor)
    } else {
        // Default BPM color when speed factor unknown
        bpmSemanticColor
    }
}

@Composable
fun BpmAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColors: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

// Legacy bpmColor function for backward compatibility
@Composable
fun bpmColorLegacy(): Color {
    return bpmSemanticColor
}
