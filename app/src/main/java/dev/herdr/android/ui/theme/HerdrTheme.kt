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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Herdr design system (v0.3 visual reset).
 *
 * Every theme is defined by a 3-color core triad:
 *  1. [HerdrThemeSlot.CANVAS]    — Background canvas (derives surface, raised, hover, border, foreground, muted, dim, isLight).
 *  2. [HerdrThemeSlot.ACCENT]    — Primary brand accent (derives accentSubtle and contrast-aware onAccent).
 *  3. [HerdrThemeSlot.SECONDARY] — Secondary / status done color (derives doneSubtle and secondary indicators).
 */
enum class HerdrThemePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val isLight: Boolean
) {
    LINEAR_OBSIDIAN("linear_obsidian", "石墨", "极简黑白 · 默认", false),
    TOKYO_NIGHT("tokyo_night", "夜蓝", "低饱和蓝调", false),
    CATPPUCCIN_MOCHA("catppuccin_mocha", "摩卡", "柔和暖炭", false),
    NORD_FROST("nord_frost", "极地", "北欧冷灰", false),
    ALABASTER_LIGHT("alabaster_light", "纸白", "日间浅色", true),
    SOLARIZED_WARM("solarized_warm", "米黄", "护眼暖色", true);

    companion object {
        fun fromId(id: String?): HerdrThemePreset {
            return entries.firstOrNull { it.id == id } ?: LINEAR_OBSIDIAN
        }
    }
}

/**
 * The three core color slots that define a complete Herdr theme.
 */
enum class HerdrThemeSlot(
    val index: Int,
    val title: String,
    val roleHint: String,
    val recommendedSwatches: List<Pair<String, String>>
) {
    CANVAS(
        index = 1,
        title = "背景底色",
        roleHint = "画布 · 自动推导卡片层级、边框与文字明暗",
        recommendedSwatches = listOf(
            "#0C0C0E" to "曜石黑",
            "#14151C" to "夜幕蓝",
            "#121219" to "深摩卡",
            "#161B1E" to "深林墨",
            "#22262E" to "极地灰",
            "#1C1917" to "暖炭黑",
            "#F5F5F7" to "纸白",
            "#F5F0E6" to "米黄",
            "#EEF2F6" to "冷雾白",
            "#EDF3EE" to "薄荷纸"
        )
    ),
    ACCENT(
        index = 2,
        title = "主强调色",
        roleHint = "主按钮 · 选中态 · 焦点边框与开关高亮",
        recommendedSwatches = listOf(
            "#E4E4E7" to "纯白银",
            "#18181B" to "深墨黑",
            "#82A6F5" to "夜空蓝",
            "#38BDF8" to "天青蓝",
            "#8DC3D2" to "极地青",
            "#2DD4BF" to "青碧",
            "#34D399" to "极客绿",
            "#E5C07B" to "琥珀金",
            "#FB923C" to "珊瑚橙",
            "#F87171" to "绯红"
        )
    ),
    SECONDARY(
        index = 3,
        title = "次强调/状态色",
        roleHint = "辅助色点 · 就绪/完成徽章与次级高亮",
        recommendedSwatches = listOf(
            "#62CC94" to "翠绿",
            "#7FD3C3" to "青绿",
            "#A2DB9E" to "嫩绿",
            "#A9C793" to "苔绿",
            "#1C7C43" to "深林绿",
            "#38BDF8" to "冰川蓝",
            "#2DD4BF" to "松石青",
            "#E5B266" to "沙金",
            "#FB923C" to "暖橙",
            "#A1A1AA" to "中性银"
        )
    )
}

/**
 * Custom 3-color overrides on top of a [HerdrThemePreset].
 * Blank hex strings mean "use the preset's built-in color for this slot".
 */
data class HerdrThemeTriad(
    val canvasHex: String = "",
    val accentHex: String = "",
    val secondaryHex: String = ""
) {
    val hasCustomOverrides: Boolean
        get() = parseHexColorOrNull(canvasHex) != null ||
            parseHexColorOrNull(accentHex) != null ||
            parseHexColorOrNull(secondaryHex) != null

    fun hexForSlot(slot: HerdrThemeSlot): String = when (slot) {
        HerdrThemeSlot.CANVAS -> canvasHex
        HerdrThemeSlot.ACCENT -> accentHex
        HerdrThemeSlot.SECONDARY -> secondaryHex
    }

    fun withSlotHex(slot: HerdrThemeSlot, hex: String): HerdrThemeTriad = when (slot) {
        HerdrThemeSlot.CANVAS -> copy(canvasHex = hex)
        HerdrThemeSlot.ACCENT -> copy(accentHex = hex)
        HerdrThemeSlot.SECONDARY -> copy(secondaryHex = hex)
    }
}

fun parseHexColorOrNull(rawHex: String?): Color? {
    val cleaned = rawHex?.trim()?.removePrefix("#")?.uppercase().orEmpty()
    if (cleaned.isEmpty()) return null
    val fullHex = when (cleaned.length) {
        3 -> buildString {
            for (c in cleaned) {
                append(c)
                append(c)
            }
        }
        6 -> cleaned
        else -> return null
    }
    if (!fullHex.all { it in '0'..'9' || it in 'A'..'F' }) return null
    val rgb = fullHex.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

fun formatColorHex(color: Color): String {
    val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", r, g, b)
}

/**
 * Pure Kotlin RGB -> HSV conversion (H in 0..360, S in 0..1, V in 0..1).
 */
fun colorToHsv(color: Color): FloatArray {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC

    var h = when {
        delta < 1e-5f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    if (h < 0f) h += 360f
    val s = if (maxC < 1e-5f) 0f else (delta / maxC).coerceIn(0f, 1f)
    val v = maxC.coerceIn(0f, 1f)
    return floatArrayOf(h, s, v)
}

/**
 * Pure Kotlin HSV -> Color conversion (H in 0..360, S in 0..1, V in 0..1).
 */
fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}

fun colorLuminance(color: Color): Float {
    return 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}

private fun blendColors(base: Color, overlay: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = (base.red * (1f - t) + overlay.red * t).coerceIn(0f, 1f),
        green = (base.green * (1f - t) + overlay.green * t).coerceIn(0f, 1f),
        blue = (base.blue * (1f - t) + overlay.blue * t).coerceIn(0f, 1f),
        alpha = 1f
    )
}

private fun liftDarkSurface(canvas: Color, delta: Float): Color {
    return Color(
        red = (canvas.red + (1f - canvas.red) * delta).coerceIn(0f, 1f),
        green = (canvas.green + (1f - canvas.green) * delta).coerceIn(0f, 1f),
        blue = (canvas.blue + (1f - canvas.blue) * delta).coerceIn(0f, 1f),
        alpha = 1f
    )
}

private fun darkenLightSurface(canvas: Color, delta: Float): Color {
    return Color(
        red = (canvas.red * (1f - delta)).coerceIn(0f, 1f),
        green = (canvas.green * (1f - delta)).coerceIn(0f, 1f),
        blue = (canvas.blue * (1f - delta)).coerceIn(0f, 1f),
        alpha = 1f
    )
}

private fun contrastOnColor(bg: Color): Color {
    return if (colorLuminance(bg) > 0.50f) Color(0xFF09090B) else Color(0xFFFFFFFF)
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
    val idle: Color,
    val isLight: Boolean = preset.isLight
) {
    fun colorForSlot(slot: HerdrThemeSlot): Color = when (slot) {
        HerdrThemeSlot.CANVAS -> canvas
        HerdrThemeSlot.ACCENT -> accent
        HerdrThemeSlot.SECONDARY -> done
    }
}

fun HerdrThemePreset.basePalette(): HerdrColorPalette = when (this) {
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
        accent = Color(0xFFE4E4E7),
        accentSubtle = Color(0xFF27272B),
        onAccent = Color(0xFF09090B),
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
        accent = Color(0xFFE5C07B),
        accentSubtle = Color(0xFF2E281C),
        onAccent = Color(0xFF17130B),
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
        accent = Color(0xFF18181B),
        accentSubtle = Color(0xFFE4E4E7),
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
 * Derives a complete [HerdrColorPalette] from a [HerdrThemePreset] and optional 3-color [HerdrThemeTriad] overrides.
 */
fun HerdrThemePreset.palette(triad: HerdrThemeTriad = HerdrThemeTriad()): HerdrColorPalette {
    val base = basePalette()
    val customCanvas = parseHexColorOrNull(triad.canvasHex)
    val customAccent = parseHexColorOrNull(triad.accentHex)
    val customSecondary = parseHexColorOrNull(triad.secondaryHex)

    if (customCanvas == null && customAccent == null && customSecondary == null) {
        return base
    }

    val derivedIsLight = if (customCanvas != null) {
        colorLuminance(customCanvas) > 0.45f
    } else {
        base.isLight
    }

    val canvas = customCanvas ?: base.canvas
    val chrome = canvas
    val surface: Color
    val raised: Color
    val hover: Color
    val border: Color
    val inputBorder: Color
    val foreground: Color
    val muted: Color
    val dim: Color
    val idle: Color

    if (customCanvas != null) {
        if (derivedIsLight) {
            val lum = colorLuminance(customCanvas)
            surface = if (lum > 0.94f) Color(0xFFFFFFFF) else blendColors(customCanvas, Color.White, 0.68f)
            raised = darkenLightSurface(customCanvas, 0.042f)
            hover = darkenLightSurface(customCanvas, 0.082f)
            border = darkenLightSurface(customCanvas, 0.095f)
            inputBorder = darkenLightSurface(customCanvas, 0.155f)
            foreground = blendColors(Color(0xFF18181B), customCanvas, 0.08f)
            muted = blendColors(Color(0xFF52525B), customCanvas, 0.12f)
            dim = blendColors(Color(0xFF6E6E78), customCanvas, 0.15f)
            idle = blendColors(Color(0xFF8A8A94), customCanvas, 0.18f)
        } else {
            surface = liftDarkSurface(customCanvas, 0.042f)
            raised = liftDarkSurface(customCanvas, 0.082f)
            hover = liftDarkSurface(customCanvas, 0.125f)
            border = liftDarkSurface(customCanvas, 0.105f)
            inputBorder = liftDarkSurface(customCanvas, 0.165f)
            foreground = blendColors(Color(0xFFECECEF), customCanvas, 0.08f)
            muted = blendColors(Color(0xFFA1A3B0), customCanvas, 0.14f)
            dim = blendColors(Color(0xFF767886), customCanvas, 0.18f)
            idle = blendColors(Color(0xFF6C6E7C), customCanvas, 0.20f)
        }
    } else {
        surface = base.surface
        raised = base.raised
        hover = base.hover
        border = base.border
        inputBorder = base.inputBorder
        foreground = base.foreground
        muted = base.muted
        dim = base.dim
        idle = base.idle
    }

    val subtleRatio = if (derivedIsLight) 0.13f else 0.18f

    val resolvedAccent = when {
        customAccent != null -> customAccent
        derivedIsLight != base.isLight && abs(colorLuminance(base.accent) - colorLuminance(surface)) < 0.25f -> {
            if (derivedIsLight) Color(0xFF18181B) else Color(0xFFE4E4E7)
        }
        else -> base.accent
    }
    val resolvedAccentSubtle = if (customAccent == null && customCanvas == null) {
        base.accentSubtle
    } else {
        blendColors(surface, resolvedAccent, subtleRatio)
    }
    val resolvedOnAccent = if (customAccent == null && customCanvas == null && resolvedAccent == base.accent) {
        base.onAccent
    } else {
        contrastOnColor(resolvedAccent)
    }

    val resolvedDone = customSecondary ?: base.done
    val resolvedDoneSubtle = if (customSecondary == null && customCanvas == null) {
        base.doneSubtle
    } else {
        blendColors(surface, resolvedDone, subtleRatio)
    }

    val resolvedWorking = if (derivedIsLight == base.isLight) {
        base.working
    } else if (derivedIsLight) {
        Color(0xFFAD5A07)
    } else {
        Color(0xFFE5B266)
    }
    val resolvedWorkingSubtle = if (customCanvas == null) {
        base.workingSubtle
    } else {
        blendColors(surface, resolvedWorking, subtleRatio)
    }

    val resolvedBlocked = if (derivedIsLight == base.isLight) {
        base.blocked
    } else if (derivedIsLight) {
        Color(0xFFD1344A)
    } else {
        Color(0xFFF07A80)
    }
    val resolvedBlockedSubtle = if (customCanvas == null) {
        base.blockedSubtle
    } else {
        blendColors(surface, resolvedBlocked, subtleRatio)
    }

    return HerdrColorPalette(
        preset = this,
        canvas = canvas,
        chrome = chrome,
        surface = surface,
        raised = raised,
        hover = hover,
        border = border,
        inputBorder = inputBorder,
        foreground = foreground,
        muted = muted,
        dim = dim,
        accent = resolvedAccent,
        accentSubtle = resolvedAccentSubtle,
        onAccent = resolvedOnAccent,
        working = resolvedWorking,
        workingSubtle = resolvedWorkingSubtle,
        blocked = resolvedBlocked,
        blockedSubtle = resolvedBlockedSubtle,
        done = resolvedDone,
        doneSubtle = resolvedDoneSubtle,
        idle = idle,
        isLight = derivedIsLight
    )
}

/**
 * Dynamic theme-aware color accessors used across UI and Terminal parser.
 */
object HerdrColors {
    var currentPalette by mutableStateOf(HerdrThemePreset.LINEAR_OBSIDIAN.palette())
        internal set

    fun applyPreset(
        preset: HerdrThemePreset,
        triad: HerdrThemeTriad = HerdrThemeTriad()
    ) {
        currentPalette = preset.palette(triad)
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
    triad: HerdrThemeTriad = HerdrThemeTriad(),
    content: @Composable () -> Unit
) {
    val palette = preset.palette(triad)
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
            onSecondary = contrastOnColor(palette.done),
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
            onSecondary = contrastOnColor(palette.done),
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
