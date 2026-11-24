package dev.herdr.android.ui.workbench

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.android.ui.components.HerdrDivider
import dev.herdr.android.ui.components.HerdrShapes
import dev.herdr.android.ui.terminal.MarkdownBlockNode
import dev.herdr.android.ui.theme.HerdrColors
import dev.herdr.android.ui.theme.HerdrType
import java.util.Locale

data class CodeHighlightPalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val annotation: Color,
    val diffAddFg: Color,
    val diffAddBg: Color,
    val diffDelFg: Color,
    val diffDelBg: Color,
    val diffHunkFg: Color,
    val diffMetaFg: Color
)

/**
 * Fast, dependency-free syntax highlighter supporting Kotlin, Java, TypeScript/JavaScript,
 * Python, Rust, PowerShell/Bash, JSON, and unified Diff/Patch blocks.
 */
object CodeSyntaxHighlighter {

    private val kotlinKeywords = setOf(
        "package", "import", "fun", "val", "var", "class", "interface", "object", "enum", "sealed",
        "data", "open", "abstract", "override", "private", "protected", "public", "internal",
        "companion", "init", "constructor", "return", "if", "else", "when", "for", "while", "do",
        "break", "continue", "try", "catch", "finally", "throw", "in", "out", "is", "as",
        "suspend", "inline", "reified", "tailrec", "operator", "infix", "lateinit", "by", "where",
        "true", "false", "null", "this", "super", "it", "Unit", "Nothing", "Any"
    )

    private val javaKeywords = setOf(
        "package", "import", "class", "interface", "enum", "record", "extends", "implements",
        "public", "private", "protected", "static", "final", "abstract", "synchronized", "volatile",
        "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "var",
        "return", "if", "else", "switch", "case", "default", "for", "while", "do", "break",
        "continue", "try", "catch", "finally", "throw", "throws", "new", "instanceof", "this",
        "super", "true", "false", "null"
    )

    private val tsJsKeywords = setOf(
        "import", "export", "from", "default", "function", "const", "let", "var", "class",
        "interface", "type", "enum", "implements", "extends", "public", "private", "protected",
        "readonly", "async", "await", "return", "if", "else", "switch", "case", "for", "while",
        "do", "break", "continue", "try", "catch", "finally", "throw", "new", "typeof", "keyof",
        "infer", "in", "of", "as", "satisfies", "true", "false", "null", "undefined", "this", "super"
    )

    private val pythonKeywords = setOf(
        "def", "class", "lambda", "import", "from", "as", "return", "yield", "async", "await",
        "if", "elif", "else", "for", "while", "break", "continue", "pass", "try", "except",
        "finally", "raise", "with", "assert", "in", "not", "and", "or", "is", "global",
        "nonlocal", "del", "match", "case", "True", "False", "None", "self"
    )

    private val rustKeywords = setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "type", "where",
        "pub", "mod", "use", "crate", "super", "self", "Self", "match", "if", "else", "loop",
        "while", "for", "in", "break", "continue", "return", "async", "await", "move", "unsafe",
        "dyn", "ref", "as", "true", "false", "Some", "None", "Ok", "Err", "Result", "Option"
    )

    private val shellKeywords = setOf(
        "if", "then", "else", "elif", "fi", "for", "do", "done", "while", "until", "case", "esac",
        "function", "return", "exit", "local", "export", "readonly", "in", "true", "false",
        "param", "begin", "process", "end", "foreach", "switch", "try", "catch", "finally",
        "throw", "elseif", "filter", "using", "namespace", "class", "enum", "null"
    )

    private val jsonKeywords = setOf("true", "false", "null")

    fun normalizeLanguage(rawLang: String): String =
        when (rawLang.trim().lowercase(Locale.ROOT)) {
            "kt", "kotlin", "kts" -> "kotlin"
            "java" -> "java"
            "ts", "tsx", "typescript" -> "typescript"
            "js", "jsx", "mjs", "cjs", "javascript" -> "javascript"
            "py", "python" -> "python"
            "rs", "rust" -> "rust"
            "ps1", "pwsh", "powershell", "sh", "bash", "zsh", "shell" -> "shell"
            "json", "jsonc", "jsonl" -> "json"
            "diff", "patch" -> "diff"
            else -> rawLang.trim().lowercase(Locale.ROOT)
        }

    fun highlight(
        code: String,
        language: String,
        palette: CodeHighlightPalette
    ): AnnotatedString {
        val norm = normalizeLanguage(language)
        if (norm == "diff" || looksLikeDiff(norm, code)) {
            return highlightDiff(code, palette)
        }
        val keywords = when (norm) {
            "kotlin" -> kotlinKeywords
            "java" -> javaKeywords
            "typescript", "javascript" -> tsJsKeywords
            "python" -> pythonKeywords
            "rust" -> rustKeywords
            "shell" -> shellKeywords
            "json" -> jsonKeywords
            else -> kotlinKeywords + tsJsKeywords + pythonKeywords
        }
        val hashComments = norm in setOf("python", "shell", "yaml", "yml", "toml", "ini")
        val slashComments = norm != "python" && norm != "shell"
        val isJson = norm == "json"

        return buildAnnotatedString {
            append(code)
            val n = code.length
            var i = 0
            while (i < n) {
                val c = code[i]
                // 1. Slash comments
                if (slashComments && c == '/' && i + 1 < n) {
                    val next = code[i + 1]
                    if (next == '/') {
                        val end = findLineEnd(code, i)
                        addStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic), i, end)
                        i = end
                        continue
                    } else if (next == '*') {
                        val close = code.indexOf("*/", i + 2)
                        val end = if (close >= 0) close + 2 else n
                        addStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic), i, end)
                        i = end
                        continue
                    }
                }
                // 2. Hash comments (# ...)
                if (hashComments && c == '#' && !isRustAttribute(norm, code, i)) {
                    val end = findLineEnd(code, i)
                    addStyle(SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic), i, end)
                    i = end
                    continue
                }
                // 3. String literals ("...", '...', `...`)
                if (c == '"' || c == '\'' || (c == '`' && norm in setOf("typescript", "javascript", "shell"))) {
                    val end = scanStringEnd(code, i, c)
                    val isJsonProperty = isJson && c == '"' && isFollowedByColon(code, end)
                    addStyle(
                        SpanStyle(color = if (isJsonProperty) palette.keyword else palette.string),
                        i,
                        end
                    )
                    i = end
                    continue
                }
                // 4. Annotations / PowerShell vars / C# directives
                if (c == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')) {
                    val end = scanIdentEnd(code, i + 1)
                    addStyle(SpanStyle(color = palette.annotation), i, end)
                    i = end
                    continue
                }
                if (norm == "shell" && c == '$' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_' || code[i + 1] == '{')) {
                    val end = if (code[i + 1] == '{') {
                        val closeBrace = code.indexOf('}', i + 2)
                        if (closeBrace >= 0) closeBrace + 1 else n
                    } else {
                        scanIdentEnd(code, i + 1)
                    }
                    addStyle(SpanStyle(color = palette.annotation), i, end)
                    i = end
                    continue
                }
                // 5. Numbers
                if (c.isDigit() && (i == 0 || (!code[i - 1].isLetterOrDigit() && code[i - 1] != '_'))) {
                    val end = scanNumberEnd(code, i)
                    addStyle(SpanStyle(color = palette.number), i, end)
                    i = end
                    continue
                }
                // 6. Identifiers / Keywords / Types
                if (c.isLetter() || c == '_') {
                    val end = scanIdentEnd(code, i)
                    val word = code.substring(i, end)
                    when {
                        word in keywords -> addStyle(
                            SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold),
                            i,
                            end
                        )
                        word.length >= 2 && word[0].isUpperCase() && word.any { it.isLowerCase() } -> addStyle(
                            SpanStyle(color = palette.type),
                            i,
                            end
                        )
                    }
                    i = end
                    continue
                }
                i++
            }
        }
    }

    private fun looksLikeDiff(norm: String, code: String): Boolean {
        if (norm.isNotBlank()) return false
        var hits = 0
        for (line in code.lineSequence().take(12)) {
            if (line.startsWith("@@ ") || line.startsWith("diff --git ") || line.startsWith("+++ ") || line.startsWith("--- ")) {
                hits++
                if (hits >= 2) return true
            }
        }
        return false
    }

    private fun highlightDiff(code: String, palette: CodeHighlightPalette): AnnotatedString {
        val lines = code.split('\n')
        return buildAnnotatedString {
            lines.forEachIndexed { idx, line ->
                val start = length
                append(line)
                val end = length
                when {
                    line.startsWith("@@ ") -> addStyle(
                        SpanStyle(color = palette.diffHunkFg, fontWeight = FontWeight.SemiBold),
                        start,
                        end
                    )
                    line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("diff ") || line.startsWith("index ") -> addStyle(
                        SpanStyle(color = palette.diffMetaFg, fontWeight = FontWeight.Medium),
                        start,
                        end
                    )
                    line.startsWith("+") -> addStyle(
                        SpanStyle(color = palette.diffAddFg, background = palette.diffAddBg),
                        start,
                        end
                    )
                    line.startsWith("-") -> addStyle(
                        SpanStyle(color = palette.diffDelFg, background = palette.diffDelBg),
                        start,
                        end
                    )
                }
                if (idx < lines.lastIndex) append('\n')
            }
        }
    }

    private fun isRustAttribute(norm: String, code: String, idx: Int): Boolean =
        norm == "rust" && idx + 1 < code.length && (code[idx + 1] == '[' || code[idx + 1] == '!')

    private fun findLineEnd(s: String, start: Int): Int {
        val nl = s.indexOf('\n', start)
        return if (nl >= 0) nl else s.length
    }

    private fun scanStringEnd(s: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == quote) return i + 1
            if (ch == '\n' && quote != '`') return i
            i++
        }
        return s.length
    }

    private fun scanIdentEnd(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) {
            i++
        }
        return i
    }

    private fun scanNumberEnd(s: String, start: Int): Int {
        var i = start
        if (i + 1 < s.length && s[i] == '0' && (s[i + 1] == 'x' || s[i + 1] == 'X' || s[i + 1] == 'b' || s[i + 1] == 'B')) {
            i += 2
        }
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '.' || s[i] == '_')) {
            i++
        }
        return i
    }

    private fun isFollowedByColon(s: String, pos: Int): Boolean {
        var i = pos
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return i < s.length && s[i] == ':'
    }
}

internal const val HERDR_LINK_TAG = "HERDR_LINK"
private val fileLineFragmentRegex = Regex("""^([^#]+)#L(\d+(?:-L?\d+)?)""", RegexOption.IGNORE_CASE)

@Composable
internal fun formatRichMarkdownInline(text: String): AnnotatedString {
    val codeBg = HerdrColors.Raised
    val accent = HerdrColors.Accent
    val muted = HerdrColors.Muted
    return remember(text, codeBg, accent, muted) {
        buildRichMarkdownAnnotatedString(
            text = text,
            codeBg = codeBg,
            codeFg = accent,
            linkFg = accent,
            strikeFg = muted
        )
    }
}

internal fun buildRichMarkdownAnnotatedString(
    text: String,
    codeBg: Color,
    codeFg: Color,
    linkFg: Color,
    strikeFg: Color
): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(
        builder = this,
        text = text,
        start = 0,
        end = text.length,
        codeBg = codeBg,
        codeFg = codeFg,
        linkFg = linkFg,
        strikeFg = strikeFg,
        depth = 0
    )
}

private fun appendInlineMarkdown(
    builder: AnnotatedString.Builder,
    text: String,
    start: Int,
    end: Int,
    codeBg: Color,
    codeFg: Color,
    linkFg: Color,
    strikeFg: Color,
    depth: Int
) {
    if (start >= end) return
    if (depth >= 8) {
        builder.append(text, start, end)
        return
    }
    var i = start
    while (i < end) {
        val c = text[i]

        // 1. Atomic inline code span (`...` / ``...``)
        if (c == '`') {
            val codeClose = findClosingBacktickRun(text, i, end)
            if (codeClose != null) {
                val (openEnd, closeStart, closeEnd) = codeClose
                val rawCode = text.substring(openEnd, closeStart)
                val cleanCode = if (openEnd - i > 1 && rawCode.length >= 2 && rawCode.startsWith(' ') && rawCode.endsWith(' ')) {
                    rawCode.substring(1, rawCode.length - 1)
                } else {
                    rawCode
                }
                if (cleanCode.isNotEmpty() && '\n' !in cleanCode) {
                    val spanStart = builder.length
                    builder.append(" $cleanCode ")
                    builder.addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            background = codeBg,
                            color = codeFg
                        ),
                        spanStart,
                        builder.length
                    )
                    i = closeEnd
                    continue
                }
            }
        }

        // 2. Markdown link `[label](url)` (with `file://` rendered as clickable file pills)
        if (c == '[') {
            val link = parseMarkdownLink(text, i, end)
            if (link != null) {
                val (labelStart, labelEnd, rawUrl, linkEnd) = link
                val rawLabel = text.substring(labelStart, labelEnd)
                if (rawUrl.startsWith("file://", ignoreCase = true)) {
                    val decodedTarget = rawUrl.removePrefix("file:///").removePrefix("file://")
                    val baseLabel = rawLabel.trim().removeSurrounding("`").ifBlank { decodedTarget }
                    val fragMatch = fileLineFragmentRegex.find(decodedTarget)
                    val lineSuffix = fragMatch?.groupValues?.get(2)?.replace("-L", "-")?.let { ":$it" }.orEmpty()
                    val display = if (lineSuffix.isNotEmpty() && !baseLabel.contains(':') && !baseLabel.contains('#')) {
                        "$baseLabel$lineSuffix"
                    } else {
                        baseLabel
                    }
                    builder.pushStringAnnotation(tag = HERDR_LINK_TAG, annotation = decodedTarget)
                    val spanStart = builder.length
                    builder.append(" 📄 $display ")
                    builder.addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            background = codeBg,
                            color = linkFg
                        ),
                        spanStart,
                        builder.length
                    )
                    builder.pop()
                } else {
                    builder.pushStringAnnotation(tag = HERDR_LINK_TAG, annotation = rawUrl)
                    val spanStart = builder.length
                    appendInlineMarkdown(builder, text, labelStart, labelEnd, codeBg, codeFg, linkFg, strikeFg, depth + 1)
                    builder.addStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline,
                            color = linkFg
                        ),
                        spanStart,
                        builder.length
                    )
                    builder.pop()
                }
                i = linkEnd
                continue
            }
        }

        // 3. Bold + Italic `***...***`
        if (c == '*' && i + 2 < end && text[i + 1] == '*' && text[i + 2] == '*') {
            val close = findClosingMarker(text, i + 3, end, "***")
            if (close > i + 3) {
                val spanStart = builder.length
                appendInlineMarkdown(builder, text, i + 3, close, codeBg, codeFg, linkFg, strikeFg, depth + 1)
                builder.addStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic),
                    spanStart,
                    builder.length
                )
                i = close + 3
                continue
            }
        }

        // 4. Bold `**...**` (supports nested `code` / *italic* / links!)
        if (c == '*' && i + 1 < end && text[i + 1] == '*') {
            val close = findClosingMarker(text, i + 2, end, "**")
            if (close > i + 2) {
                val spanStart = builder.length
                appendInlineMarkdown(builder, text, i + 2, close, codeBg, codeFg, linkFg, strikeFg, depth + 1)
                builder.addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), spanStart, builder.length)
                i = close + 2
                continue
            }
        }

        // 5. Strikethrough `~~...~~`
        if (c == '~' && i + 1 < end && text[i + 1] == '~') {
            val close = findClosingMarker(text, i + 2, end, "~~")
            if (close > i + 2) {
                val spanStart = builder.length
                appendInlineMarkdown(builder, text, i + 2, close, codeBg, codeFg, linkFg, strikeFg, depth + 1)
                builder.addStyle(
                    SpanStyle(color = strikeFg, textDecoration = TextDecoration.LineThrough),
                    spanStart,
                    builder.length
                )
                i = close + 2
                continue
            }
        }

        // 6. Italic `*...*` or `_..._` (respecting identifier `_` boundaries)
        if ((c == '*' || c == '_') && canOpenSingleEmph(text, i, start, end, c)) {
            val close = findClosingSingleEmph(text, i + 1, end, c)
            if (close > i + 1) {
                val spanStart = builder.length
                appendInlineMarkdown(builder, text, i + 1, close, codeBg, codeFg, linkFg, strikeFg, depth + 1)
                builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), spanStart, builder.length)
                i = close + 1
                continue
            }
        }

        builder.append(c)
        i++
    }
}

private fun findClosingBacktickRun(text: String, openStart: Int, limit: Int): Triple<Int, Int, Int>? {
    var openEnd = openStart
    while (openEnd < limit && text[openEnd] == '`') openEnd++
    val tickLen = openEnd - openStart
    if (tickLen == 0) return null
    var j = openEnd
    while (j < limit) {
        val ch = text[j]
        if (ch == '\n') return null
        if (ch == '`') {
            var runEnd = j
            while (runEnd < limit && text[runEnd] == '`') runEnd++
            if (runEnd - j == tickLen) {
                return Triple(openEnd, j, runEnd)
            }
            j = runEnd
            continue
        }
        j++
    }
    return null
}

private data class ParsedInlineLink(
    val labelStart: Int,
    val labelEnd: Int,
    val url: String,
    val linkEnd: Int
)

private fun parseMarkdownLink(text: String, openBracket: Int, limit: Int): ParsedInlineLink? {
    var j = openBracket + 1
    while (j < limit) {
        val ch = text[j]
        if (ch == '\n') return null
        if (ch == '`') {
            val code = findClosingBacktickRun(text, j, limit)
            if (code != null) {
                j = code.third
                continue
            }
        }
        if (ch == ']') {
            if (j + 1 >= limit || text[j + 1] != '(') return null
            var u = j + 2
            var parenDepth = 1
            while (u < limit && parenDepth > 0) {
                val uc = text[u]
                if (uc == '\n' || uc.isWhitespace()) return null
                when (uc) {
                    '(' -> parenDepth++
                    ')' -> parenDepth--
                }
                if (parenDepth == 0) {
                    val url = text.substring(j + 2, u)
                    if (url.isEmpty() || j <= openBracket + 1) return null
                    return ParsedInlineLink(openBracket + 1, j, url, u + 1)
                }
                u++
            }
            return null
        }
        j++
    }
    return null
}

private fun findClosingMarker(text: String, from: Int, limit: Int, marker: String): Int {
    var j = from
    while (j + marker.length <= limit) {
        val ch = text[j]
        if (ch == '`') {
            val code = findClosingBacktickRun(text, j, limit)
            if (code != null) {
                j = code.third
                continue
            }
        }
        if (text.regionMatches(j, marker, 0, marker.length)) {
            if (j > from && !text[j - 1].isWhitespace()) return j
        }
        j++
    }
    return -1
}

private fun canOpenSingleEmph(text: String, pos: Int, boundStart: Int, boundEnd: Int, delim: Char): Boolean {
    if (pos + 1 >= boundEnd) return false
    val next = text[pos + 1]
    if (next == delim || next.isWhitespace()) return false
    if (delim == '_' && pos > boundStart && (text[pos - 1].isLetterOrDigit() || text[pos - 1] == '_')) return false
    if (delim == '*' && pos > boundStart && (text[pos - 1].isLetterOrDigit())) return false
    return true
}

private fun findClosingSingleEmph(text: String, from: Int, limit: Int, delim: Char): Int {
    var j = from
    while (j < limit) {
        val ch = text[j]
        if (ch == '\n') return -1
        if (ch == '`') {
            val code = findClosingBacktickRun(text, j, limit)
            if (code != null) {
                j = code.third
                continue
            }
        }
        if (ch == delim) {
            // Skip double marker (`**` or `__`) if encountered inside
            if (j + 1 < limit && text[j + 1] == delim) {
                j += 2
                continue
            }
            val validPrev = j > from && !text[j - 1].isWhitespace()
            val validNext = if (delim == '_') {
                j + 1 >= limit || (!text[j + 1].isLetterOrDigit() && text[j + 1] != '_')
            } else {
                j + 1 >= limit || !text[j + 1].isLetterOrDigit()
            }
            if (validPrev && validNext) return j
        }
        j++
    }
    return -1
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RichInlineText(
    text: String,
    style: TextStyle,
    color: Color,
    copyFallback: String,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotated = formatRichMarkdownInline(text)
    val hasLinks = remember(annotated) {
        annotated.getStringAnnotations(HERDR_LINK_TAG, 0, annotated.length).isNotEmpty()
    }
    if (!hasLinks) {
        Text(
            text = annotated,
            style = style,
            color = color,
            modifier = modifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { onCopy(copyFallback) }
            )
        )
    } else {
        var layoutResult by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotated,
            style = style,
            color = color,
            onTextLayout = { layoutResult = it },
            modifier = modifier.pointerInput(annotated, copyFallback) {
                detectTapGestures(
                    onLongPress = { onCopy(copyFallback) },
                    onTap = { pos ->
                        val layout = layoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        val hit = annotated.getStringAnnotations(HERDR_LINK_TAG, offset, offset).firstOrNull()
                        if (hit != null) {
                            onCopy(hit.item)
                        }
                    }
                )
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkdownDocumentRenderer(
    nodes: List<MarkdownBlockNode>,
    rawMarkdown: String,
    stateKey: String,
    onCopy: (String) -> Unit
) {
    val palette = rememberCodeHighlightPalette()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        nodes.forEachIndexed { idx, node ->
            when (node) {
                is MarkdownBlockNode.Heading -> HeadingNodeView(
                    level = node.level,
                    text = node.text,
                    rawMarkdown = rawMarkdown,
                    onCopy = onCopy
                )
                MarkdownBlockNode.HorizontalRule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(HerdrColors.Border.copy(alpha = 0.75f))
                )
                is MarkdownBlockNode.QuoteBlock -> QuoteNodeView(
                    node = node,
                    rawMarkdown = rawMarkdown,
                    onCopy = onCopy
                )
                is MarkdownBlockNode.ListBlock -> ListNodeView(
                    node = node,
                    rawMarkdown = rawMarkdown,
                    onCopy = onCopy
                )
                is MarkdownBlockNode.TableBlock -> {
                    BtwGridTableBlock(table = node.table, onCopyText = onCopy)
                }
                is MarkdownBlockNode.CodeBlock -> SyntaxHighlightedCodeCard(
                    code = node.code,
                    language = node.language,
                    stateKey = "$stateKey#code#$idx",
                    palette = palette,
                    onCopy = onCopy
                )
                is MarkdownBlockNode.Paragraph -> RichInlineText(
                    text = node.text,
                    style = HerdrType.Body.copy(lineHeight = 22.sp),
                    color = HerdrColors.Foreground,
                    copyFallback = node.text,
                    onCopy = onCopy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun rememberCodeHighlightPalette(): CodeHighlightPalette {
    val keyword = HerdrColors.Accent
    val string = HerdrColors.Done
    val comment = HerdrColors.Dim
    val number = HerdrColors.Working
    val type = if (HerdrColors.IsLight) Color(0xFF0B5CAD) else Color(0xFF7DCFFF)
    val annotation = HerdrColors.Working
    val diffAddFg = HerdrColors.Done
    val diffAddBg = HerdrColors.DoneSubtle.copy(alpha = 0.65f)
    val diffDelFg = HerdrColors.Blocked
    val diffDelBg = HerdrColors.BlockedSubtle.copy(alpha = 0.65f)
    val diffHunkFg = HerdrColors.Accent
    val diffMetaFg = HerdrColors.Muted
    return remember(
        keyword, string, comment, number, type, annotation,
        diffAddFg, diffAddBg, diffDelFg, diffDelBg, diffHunkFg, diffMetaFg
    ) {
        CodeHighlightPalette(
            keyword = keyword,
            string = string,
            comment = comment,
            number = number,
            type = type,
            annotation = annotation,
            diffAddFg = diffAddFg,
            diffAddBg = diffAddBg,
            diffDelFg = diffDelFg,
            diffDelBg = diffDelBg,
            diffHunkFg = diffHunkFg,
            diffMetaFg = diffMetaFg
        )
    }
}

@Composable
private fun HeadingNodeView(
    level: Int,
    text: String,
    rawMarkdown: String,
    onCopy: (String) -> Unit
) {
    when (level) {
        1 -> Column(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            RichInlineText(
                text = text,
                style = HerdrType.Title.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
                color = HerdrColors.Foreground,
                copyFallback = rawMarkdown,
                onCopy = onCopy,
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HerdrColors.Accent.copy(alpha = 0.35f))
            )
        }
        2 -> Column(
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RichInlineText(
                text = text,
                style = HerdrType.Heading.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                color = HerdrColors.Foreground,
                copyFallback = rawMarkdown,
                onCopy = onCopy,
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HerdrColors.Border.copy(alpha = 0.65f))
            )
        }
        3 -> {
            val railColor = HerdrColors.Accent
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .drawBehind {
                        drawRect(color = railColor, size = Size(3.dp.toPx(), size.height))
                    }
                    .padding(start = 10.dp)
            ) {
                RichInlineText(
                    text = text,
                    style = HerdrType.Heading.copy(fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
                    color = HerdrColors.Foreground,
                    copyFallback = rawMarkdown,
                    onCopy = onCopy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        else -> RichInlineText(
            text = text,
            style = HerdrType.Label.copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            color = HerdrColors.Accent,
            copyFallback = rawMarkdown,
            onCopy = onCopy,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
        )
    }
}

@Composable
private fun QuoteNodeView(
    node: MarkdownBlockNode.QuoteBlock,
    rawMarkdown: String,
    onCopy: (String) -> Unit
) {
    val barColor = when (node.alertType) {
        "CAUTION" -> HerdrColors.Blocked
        "WARNING" -> HerdrColors.Working
        "TIP" -> HerdrColors.Done
        else -> HerdrColors.Accent
    }
    val alertBadge = when (node.alertType) {
        "CAUTION" -> "🚨 注意"
        "WARNING" -> "⚠️ 警告"
        "TIP" -> "💡 提示"
        "IMPORTANT" -> "📌 重要"
        "NOTE" -> "ℹ️ 说明"
        else -> null
    }
    val body = node.lines.joinToString("\n")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Small)
            .background(HerdrColors.Surface)
            .drawBehind {
                drawRect(color = barColor, size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = 13.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (alertBadge != null) {
            Text(
                text = alertBadge,
                style = HerdrType.Caption,
                color = barColor,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (body.isNotBlank()) {
            RichInlineText(
                text = body,
                style = HerdrType.Body.copy(lineHeight = 21.sp),
                color = HerdrColors.Foreground,
                copyFallback = body.ifBlank { rawMarkdown },
                onCopy = onCopy,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ListNodeView(
    node: MarkdownBlockNode.ListBlock,
    rawMarkdown: String,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        node.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.indentLevel * 16).dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                when {
                    item.checked != null -> {
                        val isDone = item.checked
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .size(15.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isDone) HerdrColors.DoneSubtle else HerdrColors.Canvas)
                                .border(
                                    1.dp,
                                    if (isDone) HerdrColors.Done else HerdrColors.Border,
                                    RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isDone) {
                                Text(
                                    text = "✓",
                                    style = HerdrType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = HerdrColors.Done
                                )
                            }
                        }
                    }
                    item.ordered -> {
                        Text(
                            text = "${item.ordinal ?: 1}.",
                            style = HerdrType.Body.copy(fontWeight = FontWeight.Medium),
                            color = HerdrColors.Accent,
                            modifier = Modifier.widthIn(min = 20.dp)
                        )
                    }
                    else -> {
                        val marker = when (item.indentLevel) {
                            0 -> "•"
                            1 -> "◦"
                            else -> "▪"
                        }
                        Text(
                            text = marker,
                            style = HerdrType.Body,
                            color = HerdrColors.Accent,
                            modifier = Modifier.widthIn(min = 12.dp)
                        )
                    }
                }
                RichInlineText(
                    text = item.text,
                    style = HerdrType.Body.copy(lineHeight = 21.sp),
                    color = if (item.checked == true) HerdrColors.Muted else HerdrColors.Foreground,
                    copyFallback = rawMarkdown,
                    onCopy = onCopy,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun SyntaxHighlightedCodeCard(
    code: String,
    language: String,
    stateKey: String,
    palette: CodeHighlightPalette = rememberCodeHighlightPalette(),
    onCopy: (String) -> Unit
) {
    val lines = remember(code) { code.split('\n') }
    val isCollapsible = lines.size > 28
    var expanded by remember(stateKey) { mutableStateOf(false) }
    val visibleCount = if (isCollapsible && !expanded) 18 else lines.size
    val visibleCode = remember(code, visibleCount, lines.size) {
        if (visibleCount >= lines.size) code else lines.take(visibleCount).joinToString("\n")
    }
    var copied by remember(stateKey, code) { mutableStateOf(false) }
    val highlighted = remember(visibleCode, language, palette) {
        CodeSyntaxHighlighter.highlight(code = visibleCode, language = language, palette = palette)
    }
    val gutterText = remember(visibleCount) {
        (1..visibleCount).joinToString("\n")
    }
    val gutterWidth = remember(visibleCount) {
        when {
            visibleCount >= 100 -> 36.dp
            visibleCount >= 10 -> 28.dp
            else -> 22.dp
        }
    }
    val displayLang = language.trim().ifBlank { "CODE" }.uppercase(Locale.ROOT)
    val gutterBorderColor = HerdrColors.Border.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Block)
            .background(HerdrColors.Surface)
            .border(1.dp, HerdrColors.Border.copy(alpha = 0.55f), HerdrShapes.Block)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Raised.copy(alpha = 0.72f))
                .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(HerdrShapes.Small)
                    .background(HerdrColors.AccentSubtle)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = displayLang,
                    style = HerdrType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = HerdrColors.Accent,
                    maxLines = 1
                )
            }
            Text(
                text = "${lines.size} 行",
                style = HerdrType.Caption,
                color = HerdrColors.Muted,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Text(
                text = if (copied) "✓ 已复制" else "复制代码",
                style = HerdrType.Caption,
                color = if (copied) HerdrColors.Done else HerdrColors.Accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(HerdrShapes.Small)
                    .clickable {
                        onCopy(code)
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        HerdrDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = gutterText,
                style = HerdrType.Mono.copy(fontSize = 11.5.sp, lineHeight = 17.sp, textAlign = TextAlign.End),
                color = HerdrColors.Dim.copy(alpha = 0.7f),
                softWrap = false,
                modifier = Modifier
                    .width(gutterWidth)
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawRect(
                            color = gutterBorderColor,
                            topLeft = Offset(size.width - stroke, 0f),
                            size = Size(stroke, size.height)
                        )
                    }
                    .padding(start = 6.dp, end = 7.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    text = highlighted,
                    style = HerdrType.Mono.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = HerdrColors.Foreground,
                    softWrap = false
                )
            }
        }
        if (isCollapsible) {
            HerdrDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HerdrColors.Raised.copy(alpha = 0.4f))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (expanded) "收起代码预览 ▴" else "展开全部 ${lines.size} 行代码 ▾",
                    style = HerdrType.Caption,
                    color = HerdrColors.Accent,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

internal enum class DiffLineKind { HUNK, ADD, DEL, CONTEXT, META }

internal data class ParsedDiffLine(
    val kind: DiffLineKind,
    val oldLineNo: Int?,
    val newLineNo: Int?,
    val code: String,
    val strongRange: IntRange? = null
)

private val diffHunkHeaderRegex = Regex("""^@@\s*-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s*@@.*$""")

internal fun parseUnifiedDiffLines(diffText: String): List<ParsedDiffLine> {
    val rawLines = diffText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = ArrayList<ParsedDiffLine>(rawLines.size)
    var curOld: Int? = null
    var curNew: Int? = null

    for (raw in rawLines) {
        when {
            raw.startsWith("@@") -> {
                val m = diffHunkHeaderRegex.find(raw)
                if (m != null) {
                    curOld = m.groupValues[1].toIntOrNull()
                    curNew = m.groupValues[2].toIntOrNull()
                }
                out.add(ParsedDiffLine(DiffLineKind.HUNK, null, null, raw))
            }
            raw.startsWith("+++") || raw.startsWith("---") || raw.startsWith("diff ") || raw.startsWith("index ") -> {
                out.add(ParsedDiffLine(DiffLineKind.META, null, null, raw))
            }
            raw.startsWith("…") -> {
                out.add(ParsedDiffLine(DiffLineKind.META, null, null, raw))
            }
            raw.startsWith("+") -> {
                val newNo = curNew
                if (curNew != null) curNew = curNew + 1
                out.add(ParsedDiffLine(DiffLineKind.ADD, null, newNo, raw.substring(1)))
            }
            raw.startsWith("-") -> {
                val oldNo = curOld
                if (curOld != null) curOld = curOld + 1
                out.add(ParsedDiffLine(DiffLineKind.DEL, oldNo, null, raw.substring(1)))
            }
            raw.startsWith(" ") -> {
                val oldNo = curOld
                val newNo = curNew
                if (curOld != null) curOld = curOld + 1
                if (curNew != null) curNew = curNew + 1
                out.add(ParsedDiffLine(DiffLineKind.CONTEXT, oldNo, newNo, raw.substring(1)))
            }
            else -> {
                out.add(ParsedDiffLine(DiffLineKind.META, null, null, raw))
            }
        }
    }

    // Intra-line word/segment highlighting for contiguous DEL/ADD replacement runs of equal size.
    var i = 0
    while (i < out.size) {
        if (out[i].kind != DiffLineKind.DEL) {
            i++
            continue
        }
        val delStart = i
        while (i < out.size && out[i].kind == DiffLineKind.DEL) i++
        val delCount = i - delStart

        val addStart = i
        while (i < out.size && out[i].kind == DiffLineKind.ADD) i++
        val addCount = i - addStart

        if (delCount == addCount && delCount in 1..8) {
            for (k in 0 until delCount) {
                val delCode = out[delStart + k].code
                val addCode = out[addStart + k].code
                var prefix = 0
                while (prefix < delCode.length && prefix < addCode.length && delCode[prefix] == addCode[prefix]) {
                    prefix++
                }
                var suffix = 0
                while (delCode.length - suffix > prefix && addCode.length - suffix > prefix &&
                    delCode[delCode.length - 1 - suffix] == addCode[addCode.length - 1 - suffix]
                ) {
                    suffix++
                }
                fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'
                while (prefix > 0 && isWordChar(delCode[prefix - 1]) &&
                    ((prefix < delCode.length && isWordChar(delCode[prefix])) ||
                        (prefix < addCode.length && isWordChar(addCode[prefix])))
                ) {
                    prefix--
                }
                while (suffix > 0 && isWordChar(delCode[delCode.length - suffix]) &&
                    ((delCode.length - suffix > prefix && isWordChar(delCode[delCode.length - suffix - 1])) ||
                        (addCode.length - suffix > prefix && isWordChar(addCode[addCode.length - suffix - 1])))
                ) {
                    suffix--
                }
                val delEndIdx = delCode.length - suffix
                val addEndIdx = addCode.length - suffix
                if (prefix + suffix >= 2) {
                    if (delEndIdx > prefix) {
                        out[delStart + k] = out[delStart + k].copy(strongRange = prefix until delEndIdx)
                    }
                    if (addEndIdx > prefix) {
                        out[addStart + k] = out[addStart + k].copy(strongRange = prefix until addEndIdx)
                    }
                }
            }
        }
    }

    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineUnifiedDiffCard(
    diffText: String,
    fileLabel: String?,
    stateKey: String,
    onCopy: (String) -> Unit
) {
    val palette = rememberCodeHighlightPalette()
    val parsedAll = remember(diffText) { parseUnifiedDiffLines(diffText) }
    val (addCount, delCount) = remember(parsedAll) {
        var adds = 0
        var dels = 0
        for (ln in parsedAll) {
            if (ln.kind == DiffLineKind.ADD) adds++
            else if (ln.kind == DiffLineKind.DEL) dels++
        }
        adds to dels
    }
    val isCollapsible = parsedAll.size > 28
    var expanded by remember(stateKey) { mutableStateOf(false) }
    val visibleLines = remember(parsedAll, isCollapsible, expanded) {
        if (!isCollapsible || expanded) parsedAll else parsedAll.take(20)
    }

    val maxDigits = remember(visibleLines) {
        val maxNo = visibleLines.maxOfOrNull { maxOf(it.oldLineNo ?: 0, it.newLineNo ?: 0) } ?: 0
        when {
            maxNo >= 1000 -> 4
            maxNo >= 100 -> 3
            maxNo >= 10 -> 2
            else -> 1
        }
    }
    val gutterText = remember(visibleLines, maxDigits) {
        visibleLines.joinToString("\n") { ln ->
            when (ln.kind) {
                DiffLineKind.HUNK -> "${"".padStart(maxDigits)} ${"".padStart(maxDigits)} ·"
                DiffLineKind.META -> "${"".padStart(maxDigits)} ${"".padStart(maxDigits)}  "
                DiffLineKind.ADD -> "${"".padStart(maxDigits)} ${(ln.newLineNo?.toString() ?: "").padStart(maxDigits)} +"
                DiffLineKind.DEL -> "${(ln.oldLineNo?.toString() ?: "").padStart(maxDigits)} ${"".padStart(maxDigits)} -"
                DiffLineKind.CONTEXT -> "${(ln.oldLineNo?.toString() ?: "").padStart(maxDigits)} ${(ln.newLineNo?.toString() ?: "").padStart(maxDigits)}  "
            }
        }
    }

    val addStrongBg = HerdrColors.Done.copy(alpha = 0.28f)
    val delStrongBg = HerdrColors.Blocked.copy(alpha = 0.28f)
    val codeAnnotated = remember(visibleLines, palette, addStrongBg, delStrongBg) {
        buildAnnotatedString {
            visibleLines.forEachIndexed { idx, ln ->
                val start = length
                append(ln.code)
                val end = length
                when (ln.kind) {
                    DiffLineKind.HUNK -> addStyle(
                        SpanStyle(color = palette.diffHunkFg, fontWeight = FontWeight.SemiBold),
                        start,
                        end
                    )
                    DiffLineKind.META -> addStyle(
                        SpanStyle(color = palette.diffMetaFg),
                        start,
                        end
                    )
                    DiffLineKind.ADD -> {
                        addStyle(SpanStyle(color = palette.diffAddFg), start, end)
                        val sr = ln.strongRange
                        if (sr != null && sr.first >= 0 && sr.last < ln.code.length) {
                            addStyle(
                                SpanStyle(background = addStrongBg, fontWeight = FontWeight.SemiBold),
                                start + sr.first,
                                start + sr.last + 1
                            )
                        }
                    }
                    DiffLineKind.DEL -> {
                        addStyle(SpanStyle(color = palette.diffDelFg), start, end)
                        val sr = ln.strongRange
                        if (sr != null && sr.first >= 0 && sr.last < ln.code.length) {
                            addStyle(
                                SpanStyle(background = delStrongBg, fontWeight = FontWeight.SemiBold),
                                start + sr.first,
                                start + sr.last + 1
                            )
                        }
                    }
                    DiffLineKind.CONTEXT -> Unit
                }
                if (idx < visibleLines.lastIndex) append('\n')
            }
        }
    }

    var copied by remember(stateKey, diffText) { mutableStateOf(false) }
    var gutterLayout by remember(visibleLines) { mutableStateOf<TextLayoutResult?>(null) }
    var codeLayout by remember(visibleLines) { mutableStateOf<TextLayoutResult?>(null) }
    val hunkTint = HerdrColors.AccentSubtle.copy(alpha = 0.45f)
    val gutterBorder = HerdrColors.Border.copy(alpha = 0.45f)
    val addBarColor = HerdrColors.Done
    val delBarColor = HerdrColors.Blocked
    val gutterWidth = remember(maxDigits) { ((maxDigits * 2 + 3) * 6.8f + 12f).dp }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Small)
            .background(HerdrColors.Canvas)
            .border(1.dp, HerdrColors.Border.copy(alpha = 0.65f), HerdrShapes.Small)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HerdrColors.Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = fileLabel?.takeIf { it.isNotBlank() } ?: "变更 Diff",
                style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = HerdrColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (addCount > 0) {
                Text(
                    text = "+$addCount",
                    style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                    color = HerdrColors.Done,
                    maxLines = 1
                )
            }
            if (delCount > 0) {
                Text(
                    text = "-$delCount",
                    style = HerdrType.Caption.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
                    color = HerdrColors.Blocked,
                    maxLines = 1
                )
            }
            Text(
                text = if (copied) "已复制" else "复制",
                style = HerdrType.Caption,
                color = if (copied) HerdrColors.Done else HerdrColors.Accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(HerdrShapes.Small)
                    .clickable {
                        onCopy(diffText)
                        copied = true
                    }
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        HerdrDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = gutterText,
                style = HerdrType.Mono.copy(fontSize = 10.5.sp, lineHeight = 16.5.sp),
                color = HerdrColors.Dim.copy(alpha = 0.85f),
                softWrap = false,
                onTextLayout = { gutterLayout = it },
                modifier = Modifier
                    .width(gutterWidth)
                    .background(HerdrColors.Surface.copy(alpha = 0.65f))
                    .drawBehind {
                        val layout = gutterLayout ?: return@drawBehind
                        val lineTotal = minOf(layout.lineCount, visibleLines.size)
                        val barW = 2.5.dp.toPx()
                        for (i in 0 until lineTotal) {
                            val ln = visibleLines[i]
                            val top = layout.getLineTop(i)
                            val bottom = layout.getLineBottom(i)
                            when (ln.kind) {
                                DiffLineKind.ADD -> {
                                    drawRect(palette.diffAddBg, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
                                    drawRect(addBarColor, topLeft = Offset(0f, top), size = Size(barW, bottom - top))
                                }
                                DiffLineKind.DEL -> {
                                    drawRect(palette.diffDelBg, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
                                    drawRect(delBarColor, topLeft = Offset(0f, top), size = Size(barW, bottom - top))
                                }
                                DiffLineKind.HUNK -> {
                                    drawRect(hunkTint, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
                                }
                                DiffLineKind.CONTEXT, DiffLineKind.META -> Unit
                            }
                        }
                        val stroke = 1.dp.toPx()
                        drawRect(
                            color = gutterBorder,
                            topLeft = Offset(size.width - stroke, 0f),
                            size = Size(stroke, size.height)
                        )
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { onCopy(diffText) }
                    )
            ) {
                val minCodePaneWidth = maxWidth
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = codeAnnotated,
                        style = HerdrType.Mono.copy(fontSize = 11.5.sp, lineHeight = 16.5.sp),
                        color = HerdrColors.Foreground,
                        softWrap = false,
                        onTextLayout = { codeLayout = it },
                        modifier = Modifier
                            .widthIn(min = minCodePaneWidth)
                            .drawBehind {
                                val layout = codeLayout ?: return@drawBehind
                                val lineTotal = minOf(layout.lineCount, visibleLines.size)
                                for (i in 0 until lineTotal) {
                                    val bg = when (visibleLines[i].kind) {
                                        DiffLineKind.ADD -> palette.diffAddBg
                                        DiffLineKind.DEL -> palette.diffDelBg
                                        DiffLineKind.HUNK -> hunkTint
                                        DiffLineKind.CONTEXT, DiffLineKind.META -> null
                                    } ?: continue
                                    val top = layout.getLineTop(i)
                                    val bottom = layout.getLineBottom(i)
                                    drawRect(
                                        color = bg,
                                        topLeft = Offset(0f, top),
                                        size = Size(size.width, bottom - top)
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
        if (isCollapsible) {
            HerdrDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HerdrColors.Surface)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (expanded) "收起 Diff ▴" else "展开完整 Diff (${parsedAll.size} 行) ▾",
                    style = HerdrType.Caption,
                    color = HerdrColors.Accent,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
