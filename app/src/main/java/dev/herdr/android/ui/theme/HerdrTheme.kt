package dev.herdr.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class HerdrThemePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val isLight: Boolean
) {
    LINEAR_OBSIDIAN("linear_obsidian", "曜石黑", "Linear 极简深邃", false),
    TOKYO_NIGHT("tokyo_night", "东京夜", "靛蓝霓虹夜间", false),
    CATPPUCCIN_MOCHA("catppuccin_mocha", "摩卡暗", "柔和暖紫暗调", false),
    NORD_FROST("nord_frost", "极地霜", "北欧冷灰冰蓝", false),
    ALABASTER_LIGHT("alabaster_light", "极简白", "日间纯净浅色", true),
    SOLARIZED_WARM("solarized_warm", "羊皮纸", "护眼暖米浅色", true);

    companion object {
        fun fromId(id: String?): HerdrThemePreset {
            return entries.firstOrNull { it.id == id } ?: LINEAR_OBSIDIAN
        }
    }
}

data class HerdrColorPalette(
    val preset: HerdrThemePreset,
    val canvas: Color,
    val chrome: Color,
    val surface: Color,
    val raised: Color,
    val hover: Color,
    val border: Color,
    val inputBorder: Color,
    val foreground: Color,
    val muted: Color,
    val dim: Color,
    val accent: Color,
    val accentSubtle: Color,
    val onAccent: Color,
    val working: Color,
    val workingSubtle: Color,
    val blocked: Color,
    val blockedSubtle: Color,
    val done: Color,
    val doneSubtle: Color,
    val idle: Color
) {
    val isLight: Boolean get() = preset.isLight
}

fun HerdrThemePreset.palette(): HerdrColorPalette = when (this) {
    HerdrThemePreset.LINEAR_OBSIDIAN -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFF0A0B0E),
        chrome = Color(0xFF101217),
        surface = Color(0xFF151820),
        raised = Color(0xFF1D212B),
        hover = Color(0xFF252A37),
        border = Color(0xFF232834),
        inputBorder = Color(0xFF2F3646),
        foreground = Color(0xFFF3F4F6),
        muted = Color(0xFF9CA3AF),
        dim = Color(0xFF6B7280),
        accent = Color(0xFF6366F1),
        accentSubtle = Color(0xFF1E1F3B),
        onAccent = Color(0xFFFFFFFF),
        working = Color(0xFFF59E0B),
        workingSubtle = Color(0xFF2E210B),
        blocked = Color(0xFFF43F5E),
        blockedSubtle = Color(0xFF31131C),
        done = Color(0xFF10B981),
        doneSubtle = Color(0xFF0C2B22),
        idle = Color(0xFF6B7280)
    )

    HerdrThemePreset.TOKYO_NIGHT -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFF16161E),
        chrome = Color(0xFF1A1B26),
        surface = Color(0xFF202330),
        raised = Color(0xFF292E42),
        hover = Color(0xFF333952),
        border = Color(0xFF2C3146),
        inputBorder = Color(0xFF3B4261),
        foreground = Color(0xFFC0CAF5),
        muted = Color(0xFF9AA5CE),
        dim = Color(0xFF7A84B0), // ≥4.5:1 on surface #202330 (was #565F89 ≈2.5:1)
        accent = Color(0xFF7AA2F7),
        accentSubtle = Color(0xFF202A44),
        onAccent = Color(0xFF111420),
        working = Color(0xFFE0AF68),
        workingSubtle = Color(0xFF33291E),
        blocked = Color(0xFFF7768E),
        blockedSubtle = Color(0xFF371F28),
        done = Color(0xFF73DACA),
        doneSubtle = Color(0xFF193132),
        idle = Color(0xFF565F89)
    )

    HerdrThemePreset.CATPPUCCIN_MOCHA -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFF11111B),
        chrome = Color(0xFF181825),
        surface = Color(0xFF1E1E2E),
        raised = Color(0xFF2A2B3D),
        hover = Color(0xFF36384E),
        border = Color(0xFF313244),
        inputBorder = Color(0xFF45475A),
        foreground = Color(0xFFCDD6F4),
        muted = Color(0xFFA6ADC8),
        dim = Color(0xFF8A8FA8), // was #6C7086 ≈3.3:1 on surface #1E1E2E
        accent = Color(0xFFCBA6F7),
        accentSubtle = Color(0xFF2D253E),
        onAccent = Color(0xFF161220),
        working = Color(0xFFFAB387),
        workingSubtle = Color(0xFF362622),
        blocked = Color(0xFFF38BA8),
        blockedSubtle = Color(0xFF361F29),
        done = Color(0xFFA6E3A1),
        doneSubtle = Color(0xFF1E3125),
        idle = Color(0xFF6C7086)
    )

    HerdrThemePreset.NORD_FROST -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFF242933),
        chrome = Color(0xFF292E39),
        surface = Color(0xFF2E3440),
        raised = Color(0xFF3B4252),
        hover = Color(0xFF434C5E),
        border = Color(0xFF3D4555),
        inputBorder = Color(0xFF4C566A),
        foreground = Color(0xFFECEFF4),
        muted = Color(0xFFA9B4C6), // was #D8DEE9, nearly identical to foreground (no hierarchy)
        dim = Color(0xFF7B88A1),
        accent = Color(0xFF88C0D0),
        accentSubtle = Color(0xFF2B3D47),
        onAccent = Color(0xFF182228),
        working = Color(0xFFEBCB8B),
        workingSubtle = Color(0xFF3B362A),
        blocked = Color(0xFFBF616A),
        blockedSubtle = Color(0xFF3B2428),
        done = Color(0xFFA3BE8C),
        doneSubtle = Color(0xFF2A3628),
        idle = Color(0xFF7B88A1)
    )

    HerdrThemePreset.ALABASTER_LIGHT -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFFF4F6F9),
        chrome = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        raised = Color(0xFFEEF1F6),
        hover = Color(0xFFE5E9F0),
        border = Color(0xFFE2E6EE),
        inputBorder = Color(0xFFD0D7E2),
        foreground = Color(0xFF111827),
        muted = Color(0xFF4B5563),
        dim = Color(0xFF6B7280), // was #9CA3AF ≈2.5:1 on white
        accent = Color(0xFF4F46E5),
        accentSubtle = Color(0xFFEEF2FF),
        onAccent = Color(0xFFFFFFFF),
        working = Color(0xFFB45309), // was #D97706 ≈3.2:1 on white; amber-700 ≈5:1
        workingSubtle = Color(0xFFFEF3C7),
        blocked = Color(0xFFE11D48),
        blockedSubtle = Color(0xFFFFE4E6),
        done = Color(0xFF059669),
        doneSubtle = Color(0xFFD1FAE5),
        idle = Color(0xFF6B7280)
    )

    HerdrThemePreset.SOLARIZED_WARM -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFFF5EFE4),
        chrome = Color(0xFFFCF9F2),
        surface = Color(0xFFFFFDF8),
        raised = Color(0xFFEDE5D5),
        hover = Color(0xFFE4DAC6),
        border = Color(0xFFE0D6C3),
        inputBorder = Color(0xFFD1C5AE),
        foreground = Color(0xFF1F292E),
        muted = Color(0xFF586E75),
        dim = Color(0xFF6B7B7D), // was #93A1A1 ≈2.6:1 on surface #FFFDF8
        accent = Color(0xFF0D9488),
        accentSubtle = Color(0xFFCCFBF1),
        onAccent = Color(0xFFFFFFFF),
        working = Color(0xFF9A4A08), // deeper amber for small badge text on warm canvas
        workingSubtle = Color(0xFFFEF3C7),
        blocked = Color(0xFFDC2626),
        blockedSubtle = Color(0xFFFEE2E2),
        done = Color(0xFF15803D),
        doneSubtle = Color(0xFFDCFCE7),
        idle = Color(0xFF839496)
    )
}

/**
 * Dynamic theme-aware color accessors used across UI and Terminal parser.
 */
object HerdrColors {
    var currentPalette by mutableStateOf(HerdrThemePreset.LINEAR_OBSIDIAN.palette())
        internal set

    fun applyPreset(preset: HerdrThemePreset) {
        currentPalette = preset.palette()
    }

    val Canvas: Color get() = currentPalette.canvas
    val Chrome: Color get() = currentPalette.chrome
    val Surface: Color get() = currentPalette.surface
    val Raised: Color get() = currentPalette.raised
    val Hover: Color get() = currentPalette.hover
    val Border: Color get() = currentPalette.border
    val InputBorder: Color get() = currentPalette.inputBorder

    val Foreground: Color get() = currentPalette.foreground
    val Muted: Color get() = currentPalette.muted
    val Dim: Color get() = currentPalette.dim

    val Accent: Color get() = currentPalette.accent
    val AccentSubtle: Color get() = currentPalette.accentSubtle
    val OnAccent: Color get() = currentPalette.onAccent
    val Working: Color get() = currentPalette.working
    val WorkingSubtle: Color get() = currentPalette.workingSubtle
    val Blocked: Color get() = currentPalette.blocked
    val BlockedSubtle: Color get() = currentPalette.blockedSubtle
    val Done: Color get() = currentPalette.done
    val DoneSubtle: Color get() = currentPalette.doneSubtle
    val Idle: Color get() = currentPalette.idle
    val IsLight: Boolean get() = currentPalette.isLight
}

private val HerdrTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = (-0.1).sp
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
fun HerdrAndroidTheme(
    preset: HerdrThemePreset = HerdrColors.currentPalette.preset,
    content: @Composable () -> Unit
) {
    val palette = preset.palette()
    HerdrColors.currentPalette = palette

    val scheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentSubtle,
            onPrimaryContainer = palette.foreground,
            secondary = palette.done,
            onSecondary = Color.White,
            tertiary = palette.working,
            background = palette.canvas,
            onBackground = palette.foreground,
            surface = palette.surface,
            onSurface = palette.foreground,
            surfaceVariant = palette.raised,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = palette.blocked
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.accentSubtle,
            onPrimaryContainer = palette.foreground,
            secondary = palette.done,
            onSecondary = Color(0xFF031F16),
            tertiary = palette.working,
            background = palette.canvas,
            onBackground = palette.foreground,
            surface = palette.surface,
            onSurface = palette.foreground,
            surfaceVariant = palette.raised,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = palette.blocked
        )
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = HerdrTypography,
        content = content
    )
}
