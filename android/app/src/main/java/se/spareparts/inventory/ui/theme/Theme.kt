package se.spareparts.inventory.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fallback palette: deep petrol surfaces with a safety-amber accent.
private val DarkFallback = darkColorScheme(
    primary = Color(0xFF7FD3E0),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF0F4E57),
    onPrimaryContainer = Color(0xFFA6EEFA),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF462A00),
    secondaryContainer = Color(0xFF643F00),
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = Color(0xFFB9C8FF),
    onTertiary = Color(0xFF1D2D61),
    background = Color(0xFF0E1416),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0E1416),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CA),
    surfaceContainerLowest = Color(0xFF090F11),
    surfaceContainerLow = Color(0xFF161D1F),
    surfaceContainer = Color(0xFF1A2123),
    surfaceContainerHigh = Color(0xFF252B2D),
    surfaceContainerHighest = Color(0xFF2F3638),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightFallback = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EEFFD),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF855400),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB3),
    onSecondaryContainer = Color(0xFF2A1800),
    background = Color(0xFFF5FAFB),
    surface = Color(0xFFF5FAFB),
    surfaceContainerLow = Color(0xFFEFF5F6),
    surfaceContainer = Color(0xFFE9EFF0),
    surfaceContainerHigh = Color(0xFFE3E9EA),
    surfaceContainerHighest = Color(0xFFDEE3E5),
)

/** Stock status colours that stay readable on both schemes. */
@Immutable
data class StatusColors(
    val ok: Color, val onOk: Color,
    val low: Color, val onLow: Color,
    val out: Color, val onOut: Color,
    val none: Color, val onNone: Color,
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF1E4D2B), onOk = Color(0xFF9CF0A8),
    low = Color(0xFF5C3F00), onLow = Color(0xFFFFD08A),
    out = Color(0xFF6B1A1A), onOut = Color(0xFFFFB4AB),
    none = Color(0xFF2F3638), onNone = Color(0xFFBFC8CA),
)
private val LightStatus = StatusColors(
    ok = Color(0xFFC8F2CF), onOk = Color(0xFF0B5321),
    low = Color(0xFFFFE0B0), onLow = Color(0xFF6A4300),
    out = Color(0xFFFFDAD6), onOut = Color(0xFF93000A),
    none = Color(0xFFE3E9EA), onNone = Color(0xFF3F484A),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val Mono = FontFamily.Monospace

val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Monospace style for part numbers and codes. */
val CodeStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)

@Composable
fun SparePartsTheme(themeMode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
