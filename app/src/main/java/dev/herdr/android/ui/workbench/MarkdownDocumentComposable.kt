package dev.herdr.android.ui.workbench

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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

private val richInlineTokenRegex = Regex(
    """\[([^\]]+)]\(([^)\s]+)\)|`([^`\n]+)`|\*\*\*([^*\n]+)\*\*\*|\*\*([^*\n]+)\*\*|~~([^~\n]+)~~|(?<![*\w])\*([^*\s][^*\n]*?)\*(?![*\w])|(?<![_\w])_([^_\s][^_\n]*?)_(?![_\w])"""
)

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
    var pos = 0
    for (m in richInlineTokenRegex.findAll(text)) {
        if (m.range.first > pos) {
            append(text.substring(pos, m.range.first))
        }
        val linkLabelRaw = m.groups[1]?.value
        val codeContent = m.groups[3]?.value
        val boldItalic = m.groups[4]?.value
        val bold = m.groups[5]?.value
        val strike = m.groups[6]?.value
        val italicStar = m.groups[7]?.value
        val italicUnder = m.groups[8]?.value

        val start = length
        when {
            linkLabelRaw != null -> {
                val isCode = linkLabelRaw.length >= 2 && linkLabelRaw.startsWith("`") && linkLabelRaw.endsWith("`")
                val label = if (isCode) linkLabelRaw.substring(1, linkLabelRaw.length - 1) else linkLabelRaw
                append(label)
                addStyle(
                    SpanStyle(
                        fontFamily = if (isCode) FontFamily.Monospace else null,
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline,
                        color = linkFg
                    ),
                    start,
                    length
                )
            }
            codeContent != null -> {
                append(" $codeContent ")
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        background = codeBg,
                        color = codeFg
                    ),
                    start,
                    length
                )
            }
            boldItalic != null -> {
                append(boldItalic)
                addStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic),
                    start,
                    length
                )
            }
            bold != null -> {
                append(bold)
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
            }
            strike != null -> {
                append(strike)
                addStyle(
                    SpanStyle(color = strikeFg, textDecoration = TextDecoration.LineThrough),
                    start,
                    length
                )
            }
            italicStar != null || italicUnder != null -> {
                append(italicStar ?: italicUnder.orEmpty())
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
            }
        }
        pos = m.range.last + 1
    }
    if (pos < text.length) {
        append(text.substring(pos))
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
                is MarkdownBlockNode.Paragraph -> Text(
                    text = formatRichMarkdownInline(node.text),
                    style = HerdrType.Body.copy(lineHeight = 22.sp),
                    color = HerdrColors.Foreground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = { onCopy(node.text) }
                        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeadingNodeView(
    level: Int,
    text: String,
    rawMarkdown: String,
    onCopy: (String) -> Unit
) {
    val copyMod = Modifier
        .fillMaxWidth()
        .combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = { onCopy(rawMarkdown) }
        )
    when (level) {
        1 -> Column(
            modifier = copyMod.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = formatRichMarkdownInline(text),
                style = HerdrType.Title.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold),
                color = HerdrColors.Foreground
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(HerdrColors.Accent.copy(alpha = 0.35f))
            )
        }
        2 -> Column(
            modifier = copyMod.padding(top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = formatRichMarkdownInline(text),
                style = HerdrType.Heading.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                color = HerdrColors.Foreground
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
                modifier = copyMod
                    .padding(top = 4.dp)
                    .drawBehind {
                        drawRect(color = railColor, size = Size(3.dp.toPx(), size.height))
                    }
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = formatRichMarkdownInline(text),
                    style = HerdrType.Heading.copy(fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
                    color = HerdrColors.Foreground
                )
            }
        }
        else -> Text(
            text = formatRichMarkdownInline(text),
            style = HerdrType.Label.copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            color = HerdrColors.Accent,
            modifier = copyMod.padding(top = 3.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HerdrShapes.Small)
            .background(HerdrColors.Surface)
            .drawBehind {
                drawRect(color = barColor, size = Size(3.dp.toPx(), size.height))
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { onCopy(node.lines.joinToString("\n").ifBlank { rawMarkdown }) }
            )
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
        val body = node.lines.joinToString("\n")
        if (body.isNotBlank()) {
            Text(
                text = formatRichMarkdownInline(body),
                style = HerdrType.Body.copy(lineHeight = 21.sp),
                color = HerdrColors.Foreground
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListNodeView(
    node: MarkdownBlockNode.ListBlock,
    rawMarkdown: String,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { onCopy(rawMarkdown) }
            ),
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
                Text(
                    text = formatRichMarkdownInline(item.text),
                    style = HerdrType.Body.copy(lineHeight = 21.sp),
                    color = if (item.checked == true) HerdrColors.Muted else HerdrColors.Foreground,
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
    var copied by remember(stateKey, code) { mutableStateOf(false) }
    val highlighted = remember(code, language, palette) {
        CodeSyntaxHighlighter.highlight(code = code, language = language, palette = palette)
    }
    val gutterText = remember(lines.size) {
        (1..lines.size).joinToString("\n")
    }
    val gutterWidth = remember(lines.size) {
        when {
            lines.size >= 100 -> 34.dp
            lines.size >= 10 -> 26.dp
            else -> 20.dp
        }
    }
    val displayLang = language.trim().ifBlank { "CODE" }.uppercase(Locale.ROOT)

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
                .height(IntrinsicSize.Min)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = gutterText,
                style = HerdrType.Mono.copy(fontSize = 11.sp, lineHeight = 17.sp, textAlign = TextAlign.End),
                color = HerdrColors.Dim.copy(alpha = 0.7f),
                modifier = Modifier
                    .width(gutterWidth)
                    .padding(start = 6.dp, end = 6.dp)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(HerdrColors.Border.copy(alpha = 0.45f))
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
    }
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
    val (addCount, delCount, annotated) = remember(diffText, palette) {
        var adds = 0
        var dels = 0
        for (line in diffText.lineSequence()) {
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> adds++
                line.startsWith("-") -> dels++
            }
        }
        Triple(adds, dels, CodeSyntaxHighlighter.highlight(diffText, "diff", palette))
    }
    var copied by remember(stateKey, diffText) { mutableStateOf(false) }

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { onCopy(diffText) }
                )
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = annotated,
                style = HerdrType.Mono.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = HerdrColors.Muted,
                softWrap = false
            )
        }
    }
}
