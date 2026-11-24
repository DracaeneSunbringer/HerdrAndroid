package dev.herdr.android.ui.terminal

/**
 * Pure AST node representation of a Markdown document emitted directly by the
 * `.jsonl` side-channel stream (or offline Markdown responses).
 */
sealed interface MarkdownBlockNode {
    data class Heading(
        val level: Int,
        val text: String
    ) : MarkdownBlockNode

    data object HorizontalRule : MarkdownBlockNode

    data class CodeBlock(
        val language: String,
        val code: String,
        val fenceLen: Int = 3
    ) : MarkdownBlockNode

    data class TableBlock(
        val table: BtwContentSegment.Table
    ) : MarkdownBlockNode

    data class QuoteBlock(
        val lines: List<String>,
        val alertType: String? = null
    ) : MarkdownBlockNode

    data class ListBlock(
        val items: List<MarkdownListItem>
    ) : MarkdownBlockNode

    data class Paragraph(
        val text: String
    ) : MarkdownBlockNode
}

/**
 * Single item inside a [MarkdownBlockNode.ListBlock].
 * Supports unordered bullets, ordered ordinals, task checkboxes, and nested indentation.
 */
data class MarkdownListItem(
    val indentLevel: Int,
    val ordered: Boolean,
    val ordinal: Int? = null,
    val checked: Boolean? = null,
    val text: String
)

/**
 * Stateful line-by-line Markdown AST parser supporting:
 * - ATX headings H1-H6
 * - Horizontal rules (`---`, `***`, `___`, spaced variants)
 * - Backtick / tilde fenced code blocks of variable length (`` ``` ``, ```` ```` ````, `~~~`)
 * - GFM pipe tables (delegating cell alignment parsing to [BtwRichContentParser.parseTableOrNull])
 * - Blockquotes & GitHub Alerts (`[!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]`, `[!CAUTION]`)
 * - Nested unordered/ordered lists and `- [x]` / `- [ ]` task lists
 * - Paragraphs with multi-line continuation
 */
object MarkdownDocumentParser {

    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val hrRegex = Regex("""^\s{0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,})$""")
    private val fenceOpenRegex = Regex("""^\s{0,3}(`{3,}|~{3,})(.*)$""")
    private val alertHeaderRegex = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val unorderedItemRegex = Regex("""^([ \t]*)([-*+])\s+(.*)$""")
    private val orderedItemRegex = Regex("""^([ \t]*)(\d{1,5})([.)])\s+(.*)$""")
    private val checkboxPrefixRegex = Regex("""^\[([ xX])](?:\s+(.*))?$""")
    private val tableSepRegex = Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)+\|?\s*$|^\s*\|\s*:?-{2,}:?\s*\|\s*$""")

    fun parse(rawMarkdown: String): List<MarkdownBlockNode> {
        val normalized = rawMarkdown.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()

        val lines = normalized.split('\n')
        val out = mutableListOf<MarkdownBlockNode>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Fenced code block (``` or ~~~~)
            val fenceMatch = fenceOpenRegex.matchEntire(line)
            if (fenceMatch != null) {
                val fenceToken = fenceMatch.groupValues[1]
                val fenceChar = fenceToken[0]
                val fenceLen = fenceToken.length
                val info = fenceMatch.groupValues[2].trim()
                // Backtick info string cannot contain backticks in CommonMark
                val validBacktickInfo = fenceChar != '`' || !info.contains('`')
                if (validBacktickInfo) {
                    val lang = info.split(' ', '\t').firstOrNull().orEmpty().trim()
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val candidate = lines[i].trim()
                        if (isClosingFence(candidate, fenceChar, fenceLen)) {
                            i++
                            break
                        }
                        codeLines.add(lines[i])
                        i++
                    }
                    out.add(
                        MarkdownBlockNode.CodeBlock(
                            language = lang,
                            code = codeLines.joinToString("\n"),
                            fenceLen = fenceLen
                        )
                    )
                    continue
                }
            }

            // 2. ATX Heading (H1..H6)
            val headingMatch = headingRegex.matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                val title = headingMatch.groupValues[2].trim()
                if (title.isNotEmpty()) {
                    out.add(MarkdownBlockNode.Heading(level = level, text = title))
                }
                i++
                continue
            }

            // 3. Horizontal rule (must precede unordered list check so `- - -` parses as HR)
            if (hrRegex.matches(trimmed)) {
                out.add(MarkdownBlockNode.HorizontalRule)
                i++
                continue
            }

            // 4. Pipe table
            if (i + 1 < lines.size && isTableStart(line, lines[i + 1])) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size) {
                    val row = lines[i]
                    val rowTrim = row.trim()
                    if (rowTrim.isEmpty()) break
                    if (tableLines.size >= 2 && (
                            fenceOpenRegex.matches(row) ||
                                headingRegex.matches(row) ||
                                hrRegex.matches(rowTrim) ||
                                rowTrim.startsWith('>')
                            )
                    ) {
                        break
                    }
                    if (!rowTrim.contains('|')) break
                    tableLines.add(row)
                    i++
                }
                val parsed = BtwRichContentParser.parseTableOrNull(tableLines.joinToString("\n"))
                if (parsed != null) {
                    out.add(MarkdownBlockNode.TableBlock(parsed))
                } else {
                    out.add(MarkdownBlockNode.Paragraph(tableLines.joinToString("\n")))
                }
                continue
            }

            // 5. Blockquote / GitHub Alert
            if (line.trimStart().startsWith('>')) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size) {
                    val qTrim = lines[i].trimStart()
                    if (!qTrim.startsWith('>')) break
                    val body = qTrim.drop(1).let { if (it.startsWith(' ')) it.drop(1) else it }
                    quoteLines.add(body.trimEnd())
                    i++
                }
                out.add(buildQuoteBlock(quoteLines))
                continue
            }

            // 6. Ordered / Unordered / Task List
            if (parseListItemStart(line) != null) {
                val items = mutableListOf<MarkdownListItem>()
                while (i < lines.size) {
                    val cur = lines[i]
                    val curTrim = cur.trim()
                    if (curTrim.isEmpty()) {
                        // Look ahead across single blank lines for loose lists
                        var lookahead = i + 1
                        while (lookahead < lines.size && lines[lookahead].isBlank()) {
                            lookahead++
                        }
                        if (lookahead < lines.size && parseListItemStart(lines[lookahead]) != null) {
                            i = lookahead
                            continue
                        }
                        break
                    }
                    val itemStart = parseListItemStart(cur)
                    if (itemStart != null) {
                        items.add(itemStart)
                        i++
                        continue
                    }
                    // Stop if another top-level block starts
                    if (isBlockBoundary(lines, i)) break
                    // Indented continuation line folds into previous item
                    val leading = countIndentSpaces(cur)
                    if (items.isNotEmpty() && leading >= 2) {
                        val last = items.removeAt(items.lastIndex)
                        items.add(last.copy(text = "${last.text}\n$curTrim"))
                        i++
                        continue
                    }
                    break
                }
                if (items.isNotEmpty()) {
                    out.add(MarkdownBlockNode.ListBlock(items))
                }
                continue
            }

            // 7. Paragraph until next blank line or block boundary
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trim()
                if (curTrim.isEmpty()) break
                if (paraLines.isNotEmpty() && isBlockBoundary(lines, i)) break
                paraLines.add(cur.trimEnd())
                i++
            }
            if (paraLines.isNotEmpty()) {
                out.add(MarkdownBlockNode.Paragraph(paraLines.joinToString("\n")))
            } else {
                i++
            }
        }

        return out
    }

    fun extractPlainPreview(nodes: List<MarkdownBlockNode>, maxChars: Int = 220): String {
        val sb = StringBuilder()
        for (node in nodes) {
            val snippet = when (node) {
                is MarkdownBlockNode.Heading -> node.text
                is MarkdownBlockNode.Paragraph -> node.text
                is MarkdownBlockNode.QuoteBlock -> node.lines.joinToString(" ")
                is MarkdownBlockNode.ListBlock -> node.items.joinToString(" · ") { it.text }
                is MarkdownBlockNode.TableBlock -> node.table.headers.joinToString(" | ")
                is MarkdownBlockNode.CodeBlock -> node.language.ifBlank { "code" }
                MarkdownBlockNode.HorizontalRule -> ""
            }.trim().replace('\n', ' ')
            if (snippet.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(snippet)
            if (sb.length >= maxChars) break
        }
        val flat = sb.toString().replace(Regex("""\s+"""), " ").trim()
        return if (flat.length <= maxChars) flat else flat.take(maxChars - 1).trimEnd() + "…"
    }

    private fun isClosingFence(trimmedLine: String, fenceChar: Char, openLen: Int): Boolean {
        if (trimmedLine.length < openLen) return false
        return trimmedLine.all { it == fenceChar }
    }

    private fun isTableStart(headerLine: String, sepLine: String): Boolean {
        if (!headerLine.contains('|')) return false
        return tableSepRegex.matches(sepLine)
    }

    private fun isBlockBoundary(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        val fence = fenceOpenRegex.matchEntire(line)
        if (fence != null) {
            val ch = fence.groupValues[1][0]
            val info = fence.groupValues[2].trim()
            if (ch != '`' || !info.contains('`')) return true
        }
        if (headingRegex.matches(line)) return true
        if (hrRegex.matches(trimmed)) return true
        if (line.trimStart().startsWith('>')) return true
        if (parseListItemStart(line) != null) return true
        if (index + 1 < lines.size && isTableStart(line, lines[index + 1])) return true
        return false
    }

    private fun parseListItemStart(line: String): MarkdownListItem? {
        val unordered = unorderedItemRegex.matchEntire(line)
        if (unordered != null) {
            val indentSpaces = countIndentSpaces(unordered.groupValues[1])
            val body = unordered.groupValues[3]
            val (checked, cleaned) = parseCheckbox(body)
            return MarkdownListItem(
                indentLevel = computeIndentLevel(indentSpaces),
                ordered = false,
                ordinal = null,
                checked = checked,
                text = cleaned
            )
        }
        val ordered = orderedItemRegex.matchEntire(line)
        if (ordered != null) {
            val indentSpaces = countIndentSpaces(ordered.groupValues[1])
            val num = ordered.groupValues[2].toIntOrNull()
            val body = ordered.groupValues[4]
            val (checked, cleaned) = parseCheckbox(body)
            return MarkdownListItem(
                indentLevel = computeIndentLevel(indentSpaces),
                ordered = true,
                ordinal = num,
                checked = checked,
                text = cleaned
            )
        }
        return null
    }

    private fun parseCheckbox(body: String): Pair<Boolean?, String> {
        val match = checkboxPrefixRegex.matchEntire(body.trimStart()) ?: return null to body
        val flag = match.groupValues[1]
        val checked = flag == "x" || flag == "X"
        val rest = match.groupValues[2]
        return checked to rest
    }

    private fun buildQuoteBlock(rawLines: List<String>): MarkdownBlockNode.QuoteBlock {
        if (rawLines.isEmpty()) return MarkdownBlockNode.QuoteBlock(emptyList(), null)
        val firstMatch = alertHeaderRegex.matchEntire(rawLines[0].trim())
        if (firstMatch != null) {
            val alertType = firstMatch.groupValues[1].uppercase()
            val remainder = firstMatch.groupValues[2].trim()
            val contentLines = buildList {
                if (remainder.isNotEmpty()) add(remainder)
                for (k in 1 until rawLines.size) {
                    add(rawLines[k])
                }
            }
            return MarkdownBlockNode.QuoteBlock(lines = contentLines, alertType = alertType)
        }
        return MarkdownBlockNode.QuoteBlock(lines = rawLines, alertType = null)
    }

    private fun countIndentSpaces(s: String): Int {
        var count = 0
        for (ch in s) {
            when (ch) {
                ' ' -> count++
                '\t' -> count += 2
                else -> break
            }
        }
        return count
    }

    private fun computeIndentLevel(spaces: Int): Int {
        if (spaces <= 0) return 0
        return ((spaces + 1) / 2).coerceIn(0, 4)
    }
}
