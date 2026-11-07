package dev.herdr.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Herdr Desktop Graphite/Obsidian palette paired with high-contrast terminal status colors.
 */
object HerdrColors {
    val Canvas = Color(0xFF090B0E)
    val Chrome = Color(0xFF0E1218)
    val Surface = Color(0xFF131820)
    val Raised = Color(0xFF1A212C)
    val Hover = Color(0xFF212A38)
    val Border = Color(0xFF263142)
    val InputBorder = Color(0xFF33435B)

    val Foreground = Color(0xFFE6EDF5)
    val Muted = Color(0xFF94A3B8)
    val Dim = Color(0xFF64748B)

    val Accent = Color(0xFF38BDF8)
    val AccentSubtle = Color(0xFF0C2D42)
    val Working = Color(0xFFF59E0B)
    val WorkingSubtle = Color(0xFF3B2506)
    val Blocked = Color(0xFFEF4444)
    val BlockedSubtle = Color(0xFF3D1216)
    val Done = Color(0xFF10B981)
    val DoneSubtle = Color(0xFF093124)
    val Idle = Color(0xFF64748B)
}

private val HerdrDarkColorScheme = darkColorScheme(
    primary = HerdrColors.Accent,
    onPrimary = Color(0xFF04131D),
    primaryContainer = HerdrColors.AccentSubtle,
    onPrimaryContainer = HerdrColors.Foreground,
    secondary = HerdrColors.Done,
    onSecondary = Color(0xFF031F16),
    tertiary = HerdrColors.Working,
    background = HerdrColors.Canvas,
    onBackground = HerdrColors.Foreground,
    surface = HerdrColors.Surface,
    onSurface = HerdrColors.Foreground,
    surfaceVariant = HerdrColors.Raised,
    onSurfaceVariant = HerdrColors.Muted,
    outline = HerdrColors.Border,
    error = HerdrColors.Blocked
)

private val HerdrTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp
    )
)

@Composable
fun HerdrAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HerdrDarkColorScheme,
        typography = HerdrTypography,
        content = content
    )
}
