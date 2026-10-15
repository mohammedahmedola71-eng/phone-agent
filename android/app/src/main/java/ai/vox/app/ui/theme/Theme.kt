package ai.vox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Vox design tokens: near-black navy, electric blue and cyan highlights. Vox is always dark - it's a night-sky UI. */
object VoxColors {
    val Bg = Color(0xFF05070D)
    val BgDeep = Color(0xFF0A1226)
    val Surface = Color(0xFF0B1220)
    val SurfaceHigh = Color(0xFF111B2E)
    val Outline = Color(0xFF1F3050)
    val Cyan = Color(0xFF00E5FF)
    val Blue = Color(0xFF2979FF)
    val Violet = Color(0xFF7C4DFF)
    val Text = Color(0xFFE6F1FF)
    val Muted = Color(0xFF8CA3C7)
    val Danger = Color(0xFFFF5370)
    val Ok = Color(0xFF00E676)
    val Warn = Color(0xFFFFB74D)

    val Backdrop = Brush.verticalGradient(listOf(Bg, BgDeep, Bg))
    val Accent = Brush.horizontalGradient(listOf(Cyan, Blue))
}

private val scheme = darkColorScheme(
    primary = VoxColors.Cyan,
    onPrimary = Color(0xFF00222A),
    secondary = VoxColors.Blue,
    onSecondary = Color.White,
    tertiary = VoxColors.Violet,
    background = VoxColors.Bg,
    onBackground = VoxColors.Text,
    surface = VoxColors.Surface,
    onSurface = VoxColors.Text,
    surfaceVariant = VoxColors.SurfaceHigh,
    onSurfaceVariant = VoxColors.Muted,
    outline = VoxColors.Outline,
    error = VoxColors.Danger,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 28.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp),
)

@Composable
fun VoxTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val unused = isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
