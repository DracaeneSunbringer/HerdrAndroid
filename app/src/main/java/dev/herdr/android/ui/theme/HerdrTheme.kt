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

/**
 * Herdr design system (v0.3 visual reset).
 *
 * Principles:
 *  - Neutral, low-chroma surfaces. Chrome == canvas, so headers/bars melt into the page instead of
 *    forming hard bands. Elevation is expressed by a slightly lighter surface, not by borders.
 *  - Exactly one brand accent per theme, used for the primary action and the current selection only.
 *  - Status colors (blocked / done / working) are medium-chroma and appear as dots, thin text or
 *    10% tints — never as large saturated fills or thick outlines.
 */
enum class HerdrThemePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val isLight: Boolean
) {
    LINEAR_OBSIDIAN("linear_obsidian", "石墨", "中性深灰 · 默认", false),
    TOKYO_NIGHT("tokyo_night", "夜蓝", "低饱和蓝调", false),
    CATPPUCCIN_MOCHA("catppuccin_mocha", "摩卡", "柔和暖紫", false),
    NORD_FROST("nord_frost", "极地", "北欧冷灰", false),
    ALABASTER_LIGHT("alabaster_light", "纸白", "日间浅色", true),
    SOLARIZED_WARM("solarized_warm", "米黄", "护眼暖色", true);

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
        canvas = Color(0xFF0C0C0E),
        chrome = Color(0xFF0C0C0E),
        surface = Color(0xFF151518),
        raised = Color(0xFF1D1D21),
        hover = Color(0xFF26262B),
        border = Color(0xFF212125),
        inputBorder = Color(0xFF2E2E34),
        foreground = Color(0xFFECECEE),
        muted = Color(0xFFA1A1AA),
        dim = Color(0xFF75757E),
        accent = Color(0xFF8B8DF7),
        accentSubtle = Color(0xFF1F1F36),
        onAccent = Color(0xFF0D0D1C),
        working = Color(0xFFE5B266),
        workingSubtle = Color(0xFF2A2316),
        blocked = Color(0xFFF07A80),
        blockedSubtle = Color(0xFF2D1719),
        done = Color(0xFF62CC94),
        doneSubtle = Color(0xFF14291E),
        idle = Color(0xFF6E6E77)
    )

    HerdrThemePreset.TOKYO_NIGHT -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFF14151C),
        chrome = Color(0xFF14151C),
        surface = Color(0xFF1B1C25),
        raised = Color(0xFF23252F),
        hover = Color(0xFF2B2D3A),
        border = Color(0xFF262834),
        inputBorder = Color(0xFF333647),
        foreground = Color(0xFFCDD3EE),
        muted = Color(0xFF9AA2C4),
        dim = Color(0xFF7A82AA),
        accent = Color(0xFF82A6F5),
        accentSubtle = Color(0xFF1E2740),
        onAccent = Color(0xFF0E1222),
        working = Color(0xFFDDB072),
        workingSubtle = Color(0xFF2A2419),
        blocked = Color(0xFFF2808F),
        blockedSubtle = Color(0xFF2D1A21),
        done = Color(0xFF7FD3C3),
        doneSubtle = Color(0xFF152D2A),
        idle = Color(0xFF656C92)
    )

    HerdrThemePreset.CATPPUCCIN_MOCHA -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFF121219),
        chrome = Color(0xFF121219),
        surface = Color(0xFF1A1A24),
        raised = Color(0xFF22222E),
        hover = Color(0xFF2B2B39),
        border = Color(0xFF262633),
        inputBorder = Color(0xFF34344A),
        foreground = Color(0xFFD5DBF2),
        muted = Color(0xFFA7ADC6),
        dim = Color(0xFF8A8FA8),
        accent = Color(0xFFC6A8F2),
        accentSubtle = Color(0xFF2A2340),
        onAccent = Color(0xFF181226),
        working = Color(0xFFF2B48E),
        workingSubtle = Color(0xFF2E231C),
        blocked = Color(0xFFF08FA8),
        blockedSubtle = Color(0xFF2E1A23),
        done = Color(0xFFA2DB9E),
        doneSubtle = Color(0xFF1C2B20),
        idle = Color(0xFF70748C)
    )

    HerdrThemePreset.NORD_FROST -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFF22262E),
        chrome = Color(0xFF22262E),
        surface = Color(0xFF2A2F39),
        raised = Color(0xFF323844),
        hover = Color(0xFF3A414F),
        border = Color(0xFF343A46),
        inputBorder = Color(0xFF434B5B),
        foreground = Color(0xFFE9EDF3),
        muted = Color(0xFFA9B4C6),
        dim = Color(0xFF8592AA),
        accent = Color(0xFF8DC3D2),
        accentSubtle = Color(0xFF2C3C46),
        onAccent = Color(0xFF16222A),
        working = Color(0xFFE6C88E),
        workingSubtle = Color(0xFF39352A),
        blocked = Color(0xFFE38790),
        blockedSubtle = Color(0xFF3A2B30),
        done = Color(0xFFA9C793),
        doneSubtle = Color(0xFF2E372B),
        idle = Color(0xFF7F8BA2)
    )

    HerdrThemePreset.ALABASTER_LIGHT -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFFF5F5F7),
        chrome = Color(0xFFF5F5F7),
        surface = Color(0xFFFFFFFF),
        raised = Color(0xFFEFEFF2),
        hover = Color(0xFFE7E7EB),
        border = Color(0xFFE5E5EA),
        inputBorder = Color(0xFFD9D9DF),
        foreground = Color(0xFF18181B),
        muted = Color(0xFF52525B),
        dim = Color(0xFF6E6E78),
        accent = Color(0xFF5856D6),
        accentSubtle = Color(0xFFEDEDFB),
        onAccent = Color(0xFFFFFFFF),
        working = Color(0xFFAD5A07),
        workingSubtle = Color(0xFFFBF1E3),
        blocked = Color(0xFFD1344A),
        blockedSubtle = Color(0xFFFCEBED),
        done = Color(0xFF1C7C43),
        doneSubtle = Color(0xFFE6F4EB),
        idle = Color(0xFF8A8A94)
    )

    HerdrThemePreset.SOLARIZED_WARM -> HerdrColorPalette(
        preset = this,
        canvas = Color(0xFFF5F0E6),
        chrome = Color(0xFFF5F0E6),
        surface = Color(0xFFFFFCF6),
        raised = Color(0xFFEEE7D9),
        hover = Color(0xFFE6DDCB),
        border = Color(0xFFE6DDCC),
        inputBorder = Color(0xFFD7CCB6),
        foreground = Color(0xFF2A2924),
        muted = Color(0xFF5D594F),
        dim = Color(0xFF6E695D),
        accent = Color(0xFF117A70),
        accentSubtle = Color(0xFFDDEFEA),
        onAccent = Color(0xFFFFFFFF),
        working = Color(0xFF975008),
        workingSubtle = Color(0xFFF7EBD7),
        blocked = Color(0xFFBE3A33),
        blockedSubtle = Color(0xFFF7E2DE),
        done = Color(0xFF2F7A34),
        doneSubtle = Color(0xFFE2EFDC),
        idle = Color(0xFF8C8676)
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

/**
 * Five-step type scale. Every screen should pick from these instead of ad-hoc sizes.
 */
object HerdrType {
    /** Screen title (inbox "牧群", workbench agent name). */
    val Title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, lineHeight = 28.sp)

    /** Card title / section emphasis. */
    val Heading = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, lineHeight = 22.sp)

    /** Reading body (agent prose, headlines). */
    val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp)

    /** Secondary text, buttons. */
    val Label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp)

    /** Metadata, captions, section eyebrows. */
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp)

    /** Monospace for ids, paths, code. */
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)
}

private val HerdrTypography = Typography(
    titleLarge = HerdrType.Title.copy(fontFamily = FontFamily.SansSerif),
    titleMedium = HerdrType.Heading.copy(fontFamily = FontFamily.SansSerif),
    bodyLarge = HerdrType.Body.copy(fontFamily = FontFamily.SansSerif),
    bodyMedium = HerdrType.Label.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal),
    labelLarge = HerdrType.Label.copy(fontFamily = FontFamily.SansSerif),
    labelMedium = HerdrType.Caption.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
    bodySmall = HerdrType.Caption.copy(fontFamily = FontFamily.SansSerif)
)

@Composable
fun HerdrAndroidTheme(
    preset: HerdrThemePreset = HerdrColors.currentPalette.preset,
    content: @Composable () -> Unit
) {
    val palette = preset.palette()
    if (HerdrColors.currentPalette != palette) {
        HerdrColors.currentPalette = palette
    }

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
            onSecondary = palette.canvas,
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
