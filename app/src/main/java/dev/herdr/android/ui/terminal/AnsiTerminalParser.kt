package dev.herdr.android.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.herdr.android.ui.theme.HerdrColors

/**
 * Pure 1:1 non-destructive terminal parser & colorizer.
 *
 * Preserves the exact line structure, indentation, box-drawing tables, code blocks, and trees
 * produced by the remote TUI (`agy`, `codex`, `pi`), applying only ANSI SGR or non-destructive
 * semantic syntax coloring without merging lines or stripping table borders.
 */
object AnsiTerminalParser {

    private val ansi16DarkPalette = arrayOf(
        Color(0xFF1E2430), // 0 Black
        Color(0xFFF87171), // 1 Red
        Color(0xFF34D399), // 2 Green
        Color(0xFFFBBF24), // 3 Yellow
        Color(0xFF60A5FA), // 4 Blue
        Color(0xFFC084FC), // 5 Magenta
        Color(0xFF22D3EE), // 6 Cyan
        Color(0xFFE2E8F0), // 7 White
        Color(0xFF64748B), // 8 Bright Black
        Color(0xFFFCA5A5), // 9 Bright Red
        Color(0xFF6EE7B7), // 10 Bright Green
        Color(0xFFFDE68A), // 11 Bright Yellow
        Color(0xFF93C5FD), // 12 Bright Blue
        Color(0xFFD8B4FE), // 13 Bright Magenta
        Color(0xFF67E8F9), // 14 Bright Cyan
        Color(0xFFF8FAFC)  // 15 Bright White
    )

    private val ansi16LightPalette = arrayOf(
        Color(0xFF1F2937), // 0 Black
        Color(0xFFDC2626), // 1 Red
        Color(0xFF059669), // 2 Green
        Color(0xFFB45309), // 3 Yellow/Amber
        Color(0xFF2563EB), // 4 Blue
        Color(0xFF7C3AED), // 5 Magenta
        Color(0xFF0891B2), // 6 Cyan
        Color(0xFF374151), // 7 White -> Dark Slate on Light bg
        Color(0xFF6B7280), // 8 Bright Black
        Color(0xFFE11D48), // 9 Bright Red
        Color(0xFF047857), // 10 Bright Green
        Color(0xFFD97706), // 11 Bright Yellow
        Color(0xFF1D4ED8), // 12 Bright Blue
        Color(0xFF6D28D9), // 13 Bright Magenta
        Color(0xFF0E7490), // 14 Bright Cyan
        Color(0xFF111827)  // 15 Bright White -> Deep Ink on Light bg
    )

    private val activeAnsiPalette: Array<Color>
        get() = if (HerdrColors.IsLight) ansi16LightPalette else ansi16DarkPalette

    private val oscRegex = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val csiRegex = Regex("\u001B\\[([0-9;:?]*)([A-Za-z])")
    private val fullLineRuleRegex = Regex("^[\\s─━═\\-_]{6,}$")
    private val boxTableBorderRegex = Regex("^[\\s─━═┌┬┐├┼┤└┴┘╭╮╯╰|+\\-:]{4,}$")
    private val labeledRuleRegex = Regex("^([\\s─━═\\-_]{3,})([^─━═\\-_]+?)([\\s─━═\\-_]{3,})$")
    private val userPromptLineRegex = Regex("^(\\s*[>›❯]\\s+)(.+)$")
    private val thoughtLineRegex = Regex("^\\s*[○◦▸▾▼]?\\s*Thought for\\s+.+$")
    private val toolStepLineRegex = Regex("^(\\s*[●•○◦])(\\s+[A-Z][A-Za-z0-9_-]*(?:\\s+[A-Za-z0-9_-]+){0,3})(.*)$")
    private val treeBranchItemRegex = Regex("^(\\s*[├└]─?\\s*)([✓✗◦•○●]?)(.*)$")
    private val toolSubOutputHeaderRegex = Regex("^(\\s*⎿\\s*)(.+)$")
    private val spinnerLineRegex = Regex("^\\s*[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏⣾⣽⣻⢿⡿⣟⣯⣷⠿]\\s+.*$")
    private val footerLineRegex = Regex(
        "^\\s*(esc to cancel|\\? for shortcuts|← for agents|Thinking blocks:|Tool output:|\\[hc] incomplete:|.*(?:tok/s|TTFT:|Cline upstream:|MCP:\\s*\\d+)).*$"
    )
    private val diffAddLineRegex = Regex("^\\s*\\d+\\s+\\+\\s+.*$")
    private val diffDelLineRegex = Regex("^\\s*\\d+\\s+-\\s+.*$")
    private val lonePromptLineRegex = Regex("^\\s*[›❯]\\s*$")
    private val errorLineRegex = Regex("^\\s*(?:Error|ERROR|error|Fatal|FATAL|fatal|panic)(?:\\[[^\\]]*])?\\s*[:：].*$")
    private val warningLineRegex = Regex("^\\s*(?:Warning|WARNING|warning|WARN|Warn)(?:\\[[^\\]]*])?\\s*[:：].*$")

    /** Minimum contrast ratio enforced for remote 256-color / truecolor foregrounds on light themes. */
    private const val MIN_LIGHT_FG_CONTRAST = 3.0

    fun parseToAnnotatedString(
        rawText: String,
        mobileReflow: Boolean = false
    ): AnnotatedString {
        val cleanedOsc = oscRegex.replace(rawText, "")
        val hasAnsiSgr = csiRegex.containsMatchIn(cleanedOsc)
        val normalized = normalizeRawLines(cleanedOsc)

        if (hasAnsiSgr) {
            return parseAnsiSgrString(normalized)
        }
        return parseSemanticTuiString(normalized)
    }

    /**
     * Non-destructively normalizes line endings and trims trailing blank padding on each row,
     * preserving 100% of the TUI's native layout, column spacing, and box-drawing characters.
     */
    private fun normalizeRawLines(input: String): String {
        val rawLines = input.replace("\r\n", "\n").split('\n')
        val outLines = ArrayList<String>(rawLines.size)
        var consecutiveBlankCount = 0

        for (rawLine in rawLines) {
            val crNormalized = if ('\r' !in rawLine) {
                rawLine
            } else {
                rawLine.substringAfterLast('\r')
            }
            val trimmedEnd = crNormalized.trimEnd()
            if (trimmedEnd.isEmpty()) {
                consecutiveBlankCount++
                if (consecutiveBlankCount <= 2) {
                    outLines.add("")
                }
            } else {
                consecutiveBlankCount = 0
                outLines.add(trimmedEnd)
            }
        }

        while (outLines.isNotEmpty() && outLines.first().isEmpty()) {
            outLines.removeAt(0)
        }
        while (outLines.isNotEmpty() && outLines.last().isEmpty()) {
            outLines.removeAt(outLines.lastIndex)
        }
        return outLines.joinToString("\n")
    }

    private fun parseSemanticTuiString(text: String): AnnotatedString = buildAnnotatedString {
        val lines = text.split('\n')
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            when {
                labeledRuleRegex.matches(line) -> {
                    val match = labeledRuleRegex.find(line)!!
                    appendStyledSegment(match.groupValues[1], HerdrColors.Border, Color.Unspecified, false)
                    appendStyledSegment(match.groupValues[2], HerdrColors.Working, Color.Unspecified, true)
                    appendStyledSegment(match.groupValues[3], HerdrColors.Border, Color.Unspecified, false)
                }

                fullLineRuleRegex.matches(trimmed) || boxTableBorderRegex.matches(trimmed) -> {
                    appendStyledSegment(line, HerdrColors.Border, Color.Unspecified, false)
                }

                userPromptLineRegex.matches(line) && !trimmed.startsWith("> ") -> {
                    val match = userPromptLineRegex.find(line)!!
                    appendStyledSegment(match.groupValues[1], HerdrColors.Accent, Color.Unspecified, true)
                    appendStyledSegment(match.groupValues[2], HerdrColors.Foreground, Color.Unspecified, true)
                }

                lonePromptLineRegex.matches(line) -> {
                    appendStyledSegment(line, HerdrColors.Accent, Color.Unspecified, true)
                }

                errorLineRegex.matches(line) -> {
                    appendStyledSegment(line, HerdrColors.Blocked, Color.Unspecified, false)
                }

                warningLineRegex.matches(line) -> {
                    appendStyledSegment(line, HerdrColors.Working, Color.Unspecified, false)
                }

                thoughtLineRegex.matches(trimmed) -> {
                    val isExpanded = trimmed.startsWith("▾") || trimmed.startsWith("▼")
                    appendStyledSegment(
                        line,
                        if (isExpanded) HerdrColors.Muted else HerdrColors.Dim,
                        Color.Unspecified,
                        isExpanded
                    )
                }

                toolSubOutputHeaderRegex.matches(line) -> {
                    val match = toolSubOutputHeaderRegex.find(line)!!
                    val prefix = match.groupValues[1]
                    val body = match.groupValues[2]
                    val isErr = "code 1" in body.lowercase() || "failed" in body.lowercase() || "error" in body.lowercase()
                    appendStyledSegment(prefix, HerdrColors.Border, Color.Unspecified, true)
                    appendStyledSegment(
                        body,
                        if (isErr) HerdrColors.Blocked else HerdrColors.Muted,
                        Color.Unspecified,
                        isErr
                    )
                }

                treeBranchItemRegex.matches(line) -> {
                    val match = treeBranchItemRegex.find(line)!!
                    val branch = match.groupValues[1]
                    val statusSym = match.groupValues[2]
                    val rest = match.groupValues[3]
                    appendStyledSegment(branch, HerdrColors.Border, Color.Unspecified, false)
                    if (statusSym.isNotEmpty()) {
                        val symColor = when (statusSym) {
                            "✓", "•", "●" -> HerdrColors.Done
                            "✗" -> HerdrColors.Blocked
                            else -> HerdrColors.Accent
                        }
                        appendStyledSegment(statusSym, symColor, Color.Unspecified, true)
                    }
                    appendStyledSegment(rest, HerdrColors.Foreground, Color.Unspecified, false)
                }

                toolStepLineRegex.matches(line) -> {
                    val match = toolStepLineRegex.find(line)!!
                    val bullet = match.groupValues[1]
                    val head = match.groupValues[2]
                    val tail = match.groupValues[3]
                    val fullRest = "$head$tail"
                    val isError = "failed" in fullRest.lowercase() || "error" in fullRest.lowercase()
                    val isRunning = head.trimStart().startsWith("Running") || head.trimStart().startsWith("Scheduling")
                    val bulletColor = when {
                        isError -> HerdrColors.Blocked
                        isRunning -> HerdrColors.Working
                        else -> HerdrColors.Done
                    }
                    appendStyledSegment(bullet, bulletColor, Color.Unspecified, true)
                    appendStyledSegment(head, HerdrColors.Muted, Color.Unspecified, true)
                    if (tail.isNotEmpty()) {
                        appendStyledSegment(tail, HerdrColors.Dim, Color.Unspecified, false)
                    }
                }

                '│' in line -> {
                    appendLineWithBoxPipes(line)
                }

                spinnerLineRegex.matches(line) -> {
                    appendStyledSegment(line, HerdrColors.Working, Color.Unspecified, true)
                }

                diffAddLineRegex.matches(line) -> {
                    appendStyledSegment(line, HerdrColors.Done, Color.Unspecified, false)
                }

                diffDelLineRegex.matches(line) -> {
                    appendStyledSegment(line, HerdrColors.Blocked, Color.Unspecified, false)
                }

                footerLineRegex.matches(line) -> {
                    appendStyledSegment(line, HerdrColors.Dim, Color.Unspecified, false)
                }

                else -> {
                    appendStyledSegment(line, HerdrColors.Foreground, Color.Unspecified, false)
                }
            }
            if (index < lines.lastIndex) {
                append("\n")
            }
        }
    }

    private fun AnnotatedString.Builder.appendLineWithBoxPipes(line: String) {
        var cursor = 0
        for (i in line.indices) {
            if (line[i] == '│') {
                if (i > cursor) {
                    appendStyledSegment(line.substring(cursor, i), HerdrColors.Foreground, Color.Unspecified, false)
                }
                appendStyledSegment("│", HerdrColors.Border, Color.Unspecified, false)
                cursor = i + 1
            }
        }
        if (cursor < line.length) {
            appendStyledSegment(line.substring(cursor), HerdrColors.Foreground, Color.Unspecified, false)
        }
    }

    /**
     * Mutable SGR attribute state for the ANSI path.
     * `fg`/`bg` hold the *logical* colors; inverse/dim are resolved at emit time.
     */
    private class SgrState {
        var fg: Color = Color.Unspecified // Unspecified => theme foreground
        var bg: Color = Color.Unspecified
        var bold = false
        var faint = false
        var italic = false
        var underline = false
        var inverse = false
        var strike = false

        fun reset() {
            fg = Color.Unspecified
            bg = Color.Unspecified
            bold = false
            faint = false
            italic = false
            underline = false
            inverse = false
            strike = false
        }
    }

    private fun parseAnsiSgrString(normalized: String): AnnotatedString = buildAnnotatedString {
        val state = SgrState()

        var cursor = 0
        for (match in csiRegex.findAll(normalized)) {
            if (match.range.first > cursor) {
                appendSgrSegment(normalized.substring(cursor, match.range.first), state)
            }
            val paramsRaw = match.groupValues[1]
            val finalByte = match.groupValues[2]

            // Private-mode sequences (e.g. ESC[?25l) are not SGR even when ending in 'm'.
            if (finalByte == "m" && !paramsRaw.startsWith("?")) {
                // Colon sub-parameters (e.g. "4:3" curly underline, "38:2::r:g:b") are reduced to their
                // leading selector; semicolon form carries the full color payload.
                val codes = if (paramsRaw.isEmpty()) listOf(0)
                else paramsRaw.split(';').map { part ->
                    part.substringBefore(':').toIntOrNull() ?: 0
                }

                var idx = 0
                while (idx < codes.size) {
                    when (val code = codes[idx]) {
                        0 -> state.reset()
                        1 -> state.bold = true
                        2 -> state.faint = true
                        3 -> state.italic = true
                        4, 21 -> state.underline = true
                        7 -> state.inverse = true
                        9 -> state.strike = true
                        22 -> {
                            state.bold = false
                            state.faint = false
                        }
                        23 -> state.italic = false
                        24 -> state.underline = false
                        27 -> state.inverse = false
                        29 -> state.strike = false
                        in 30..37 -> state.fg = activeAnsiPalette[code - 30]
                        39 -> state.fg = Color.Unspecified
                        in 40..47 -> state.bg = activeAnsiPalette[code - 40]
                        49 -> state.bg = Color.Unspecified
                        in 90..97 -> state.fg = activeAnsiPalette[code - 90 + 8]
                        in 100..107 -> state.bg = activeAnsiPalette[code - 100 + 8]
                        38, 48 -> {
                            val isForeground = (code == 38)
                            val mode = codes.getOrNull(idx + 1)
                            if (mode == 5) {
                                val colorIndex = codes.getOrNull(idx + 2) ?: 0
                                val resolved = xterm256ToColor(colorIndex)
                                if (isForeground) {
                                    state.fg = if (colorIndex >= 16) ensureLightThemeContrast(resolved) else resolved
                                } else {
                                    state.bg = resolved
                                }
                                idx += 2
                            } else if (mode == 2) {
                                val r = (codes.getOrNull(idx + 2) ?: 255).coerceIn(0, 255)
                                val g = (codes.getOrNull(idx + 3) ?: 255).coerceIn(0, 255)
                                val b = (codes.getOrNull(idx + 4) ?: 255).coerceIn(0, 255)
                                val resolved = Color(r, g, b)
                                if (isForeground) {
                                    state.fg = ensureLightThemeContrast(resolved)
                                } else {
                                    state.bg = resolved
                                }
                                idx += 4
                            }
                        }
                    }
                    idx++
                }
            }
            cursor = match.range.last + 1
        }

        if (cursor < normalized.length) {
            appendSgrSegment(normalized.substring(cursor), state)
        }
    }

    /** Resolves inverse/faint/background translucency and appends one ANSI-styled run. */
    private fun AnnotatedString.Builder.appendSgrSegment(text: String, state: SgrState) {
        if (text.isEmpty()) return
        val logicalFg = if (state.fg == Color.Unspecified) HerdrColors.Foreground else state.fg

        val fg: Color
        val bg: Color
        if (state.inverse) {
            // Reverse video: swap. Default background maps to the canvas so the run reads as a solid block.
            fg = if (state.bg == Color.Unspecified) HerdrColors.Canvas else state.bg
            bg = logicalFg
        } else {
            fg = logicalFg
            bg = if (state.bg == Color.Unspecified) Color.Unspecified else state.bg.copy(alpha = 0.3f)
        }
        val finalFg = if (state.faint) fg.copy(alpha = fg.alpha * 0.6f) else fg

        val decorations = buildList {
            if (state.underline) add(TextDecoration.Underline)
            if (state.strike) add(TextDecoration.LineThrough)
        }
        val start = length
        append(text)
        addStyle(
            SpanStyle(
                color = finalFg,
                background = bg,
                fontWeight = if (state.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (state.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations)
            ),
            start,
            length
        )
    }

    private fun AnnotatedString.Builder.appendStyledSegment(
        text: String,
        fg: Color,
        bg: Color,
        bold: Boolean
    ) {
        if (text.isEmpty()) return
        val start = length
        append(text)
        addStyle(
            SpanStyle(
                color = fg,
                background = bg,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            ),
            start,
            length
        )
    }

    /**
     * Remote TUIs usually pick 256/truecolor foregrounds for dark backgrounds; on light themes those
     * (pale yellow, light gray, white) become unreadable. Darken progressively until the contrast
     * against the canvas reaches [MIN_LIGHT_FG_CONTRAST]. No-op on dark themes.
     */
    private fun ensureLightThemeContrast(color: Color): Color {
        if (!HerdrColors.IsLight) return color
        val canvasLum = HerdrColors.Canvas.luminance()
        var adjusted = color
        var steps = 0
        while (contrastRatio(adjusted.luminance(), canvasLum) < MIN_LIGHT_FG_CONTRAST && steps < 12) {
            adjusted = Color(
                red = adjusted.red * 0.82f,
                green = adjusted.green * 0.82f,
                blue = adjusted.blue * 0.82f,
                alpha = adjusted.alpha
            )
            steps++
        }
        return adjusted
    }

    private fun contrastRatio(lumA: Float, lumB: Float): Double {
        val hi = maxOf(lumA, lumB)
        val lo = minOf(lumA, lumB)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun xterm256ToColor(index: Int): Color {
        val clamped = index.coerceIn(0, 255)
        if (clamped < 16) return activeAnsiPalette[clamped]
        if (clamped in 16..231) {
            val offset = clamped - 16
            val r = (offset / 36) % 6
            val g = (offset / 6) % 6
            val b = offset % 6
            val scale = intArrayOf(0, 95, 135, 175, 215, 255)
            return Color(scale[r], scale[g], scale[b])
        }
        val gray = (8 + (clamped - 232) * 10).coerceIn(0, 255)
        return Color(gray, gray, gray)
    }
}
