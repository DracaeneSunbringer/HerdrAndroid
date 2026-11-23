package dev.herdr.android.ui.terminal

/** Alignment of a column in a parsed `/btw` table. */
enum class BtwTableAlign {
    START,
    CENTER,
    END
}

/** A single item inside a Markdown/bullet list within a `/btw` answer. */
data class BtwListItem(
    val marker: String,
    val text: String,
    val indentLevel: Int = 0
)

/** Structured visual segments extracted from a `/btw` response body. */
sealed interface BtwContentSegment {
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<BtwTableAlign>,
        val rawText: String
    ) : BtwContentSegment

    data class CodeBlock(
        val code: String,
        val language: String? = null
    ) : BtwContentSegment

    data class Heading(
        val text: String,
        val level: Int
    ) : BtwContentSegment

    data class BulletList(
        val items: List<BtwListItem>
    ) : BtwContentSegment

    data class Paragraph(
        val text: String
    ) : BtwContentSegment
}

/**
 * Parses raw `/btw` terminal card answers into structured tables, fenced code blocks,
 * headings, lists, and prose paragraphs while preserving monospace geometry where needed.
 *
 * Supports all 3 table families emitted by Antigravity CLI (`agy`):
 * 1. Antigravity borderless `│` tables (`Module │ Role` + `───────┼───────`)
 * 2. Unicode box-drawn (`┌─┬─┐` / `├─┼─┤` / `└─┴─┘`) & ASCII `+---+---+` tables
 * 3. Standard Markdown pipe tables (`| H1 | H2 |` + `| :--- | :---: |`)
 */
object BtwRichContentParser {

    private val fenceRegex = Regex("^```([A-Za-z0-9_+.-]*)\\s*$")
    private val headingRegex = Regex("^(#{1,4})\\s+(.+?)\\s*#*$")
    private val bulletRegex = Regex("^(\\s*)(?:([•\\-*])|(\\d+\\.))\\s+(.+)$")
    private val markdownSepRegex = Regex("^\\|?\\s*:?-{2,}:?(?:\\s*\\|\\s*:?-{2,}:?)+\\s*\\|?$")
    private val unescapedPipeRegex = Regex("(?<!\\\\)\\|")
    private val agyCrossSepRegex = Regex("^[─━═]+(?:[┼╋┬┴][─━═]+)+$")
    private val boxTopBorderRegex = Regex("^[┌╭][─━═┬]+[┐╮]$")
    private val boxMidBorderRegex = Regex("^[├╞][─━═┼╬]+[┤╡]$")
    private val boxBottomBorderRegex = Regex("^[└╰][─━═┴]+[┘╯]$")
    private val boxHLineOnlyRegex = Regex("^[─━═┼╋┬┴├┤┌┐└┘╭╮╰╯]+$")
    private val asciiPlusBorderRegex = Regex("^\\+[-=+]{2,}(?:\\+[-=+]{2,})+\\+$")
    private val codexSpacedRuleRegex = Regex("^\\s*[━─]{3,}(?:\\s{2,}[━─]{3,})+\\s*$")

    /**
     * Parses a raw table snippet (Markdown, Antigravity `│` + `───┼───`, box-drawn, or Codex
     * spaced-rule `━━━  ━━━`) into a structured [BtwContentSegment.Table], or null if it is not a
     * standalone table.
     */
    fun parseTableOrNull(rawText: String): BtwContentSegment.Table? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        val segments = parse(trimmed)
        return segments.singleOrNull() as? BtwContentSegment.Table
    }

    fun parse(answerText: String): List<BtwContentSegment> {
        if (answerText.isBlank()) return emptyList()
        val lines = answerText.replace("\r\n", "\n").lines()
        val segments = ArrayList<BtwContentSegment>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Fenced code block (```lang ... ```)
            val fenceMatch = fenceRegex.matchEntire(trimmed)
            if (fenceMatch != null) {
                val lang = fenceMatch.groupValues[1].trim().ifEmpty { null }
                val baseIndent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                i++
                val codeLines = ArrayList<String>()
                while (i < lines.size) {
                    if (lines[i].trim().startsWith("```")) {
                        i++
                        break
                    }
                    codeLines.add(stripUpToSpaces(lines[i], baseIndent))
                    i++
                }
                while (codeLines.isNotEmpty() && codeLines.first().isBlank()) codeLines.removeAt(0)
                while (codeLines.isNotEmpty() && codeLines.last().isBlank()) codeLines.removeAt(codeLines.lastIndex)
                if (codeLines.isNotEmpty()) {
                    segments.add(BtwContentSegment.CodeBlock(code = codeLines.joinToString("\n"), language = lang))
                }
                continue
            }

            // 2. Standard Markdown pipe table (| H1 | H2 | + | :--- | ---: |)
            if (isMarkdownTableStart(lines, i)) {
                val (seg, nextIdx) = consumeMarkdownTable(lines, i)
                segments.add(seg)
                i = nextIdx
                continue
            }

            // 3. Antigravity borderless `│` table (`Col1 │ Col2` + `───┼───`)
            if (isAgyPipeTableStart(lines, i)) {
                val (seg, nextIdx) = consumeAgyPipeTable(lines, i)
                segments.add(seg)
                i = nextIdx
                continue
            }

            // 4. Unicode box-drawn (`┌─┬─┐`) or ASCII `+---+---+` table/diagram
            if (isBoxTableStart(lines, i)) {
                val (seg, nextIdx) = consumeBoxTable(lines, i)
                segments.add(seg)
                i = nextIdx
                continue
            }

            // 5. Codex spaced-rule table (`━━━  ━━━` / `───  ───`)
            if (isCodexSpacedTableStart(lines, i)) {
                val (seg, nextIdx) = consumeCodexSpacedTable(lines, i)
                segments.add(seg)
                i = nextIdx
                continue
            }

            // 6. Markdown heading (# .. ####)
            val hMatch = headingRegex.matchEntire(trimmed)
            if (hMatch != null) {
                segments.add(
                    BtwContentSegment.Heading(
                        text = hMatch.groupValues[2].trim(),
                        level = hMatch.groupValues[1].length
                    )
                )
                i++
                continue
            }

            // 6. Bullet / ordered list block
            val bMatch = bulletRegex.matchEntire(line)
            if (bMatch != null) {
                val (listSeg, nextIdx) = consumeBulletList(lines, i)
                segments.add(listSeg)
                i = nextIdx
                continue
            }

            // 7. Paragraph prose block
            val paraLines = ArrayList<String>()
            while (i < lines.size) {
                val cur = lines[i]
                val curTrim = cur.trim()
                if (curTrim.isEmpty() ||
                    fenceRegex.matches(curTrim) ||
                    headingRegex.matches(curTrim) ||
                    bulletRegex.matches(cur) ||
                    isMarkdownTableStart(lines, i) ||
                    isAgyPipeTableStart(lines, i) ||
                    isBoxTableStart(lines, i) ||
                    isCodexSpacedTableStart(lines, i)
                ) {
                    break
                }
                paraLines.add(curTrim)
                i++
            }
            if (paraLines.isNotEmpty()) {
                segments.add(BtwContentSegment.Paragraph(joinWrappedLines(paraLines)))
            }
        }
        return segments
    }

    private fun isMarkdownTableStart(lines: List<String>, idx: Int): Boolean {
        if (idx + 1 >= lines.size) return false
        val header = lines[idx].trim()
        val sep = lines[idx + 1].trim()
        return '|' in header && markdownSepRegex.matches(sep)
    }

    private fun consumeMarkdownTable(lines: List<String>, startIdx: Int): Pair<BtwContentSegment.Table, Int> {
        val rawLines = ArrayList<String>()
        val headerCells = splitMarkdownRow(lines[startIdx])
        rawLines.add(lines[startIdx])
        val alignments = splitMarkdownRow(lines[startIdx + 1]).map { parseAlignment(it) }
        rawLines.add(lines[startIdx + 1])

        val rows = ArrayList<List<String>>()
        var i = startIdx + 2
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || '|' !in trimmed || fenceRegex.matches(trimmed) || boxHLineOnlyRegex.matches(trimmed)) {
                break
            }
            if (markdownSepRegex.matches(trimmed)) {
                rawLines.add(lines[i])
                i++
                continue
            }
            val cells = splitMarkdownRow(trimmed)
            if (cells.size <= 1 && !trimmed.startsWith("|") && !trimmed.endsWith("|")) {
                break
            }
            rows.add(cells)
            rawLines.add(lines[i])
            i++
        }

        val colCount = maxOf(
            headerCells.size,
            alignments.size,
            rows.maxOfOrNull { it.size } ?: 0
        ).coerceAtLeast(1)

        return BtwContentSegment.Table(
            headers = normalizeRow(headerCells, colCount),
            rows = rows.map { normalizeRow(it, colCount) },
            alignments = normalizeAlignments(alignments, colCount),
            rawText = rawLines.joinToString("\n")
        ) to i
    }

    private fun isAgyPipeTableStart(lines: List<String>, idx: Int): Boolean {
        val cur = lines[idx].trim()
        if (agyCrossSepRegex.matches(cur)) {
            return idx + 1 < lines.size && ('│' in lines[idx + 1] || '┃' in lines[idx + 1])
        }
        if ('│' !in cur && '┃' !in cur) return false
        if (cur.startsWith("┌") || cur.startsWith("╭") || cur.startsWith("├") || cur.startsWith("└") || cur.startsWith("╰")) {
            return false
        }
        return idx + 1 < lines.size && agyCrossSepRegex.matches(lines[idx + 1].trim())
    }

    private fun consumeAgyPipeTable(lines: List<String>, startIdx: Int): Pair<BtwContentSegment.Table, Int> {
        val rawLines = ArrayList<String>()
        var i = startIdx
        var headerCells = emptyList<String>()

        if (!agyCrossSepRegex.matches(lines[i].trim())) {
            headerCells = splitPipeRow(lines[i], trimOuterEmpty = false)
            rawLines.add(lines[i])
            i++
        }
        if (i < lines.size && agyCrossSepRegex.matches(lines[i].trim())) {
            rawLines.add(lines[i])
            i++
        }

        val rows = ArrayList<List<String>>()
        var hadSepBeforeRow = true
        while (i < lines.size) {
            val rawLine = lines[i]
            val t = rawLine.trim()
            if (t.isEmpty()) break
            if (agyCrossSepRegex.matches(t)) {
                rawLines.add(rawLine)
                hadSepBeforeRow = true
                i++
                continue
            }
            if ('│' !in rawLine && '┃' !in rawLine) break
            val cells = splitPipeRow(rawLine, trimOuterEmpty = false)
            rawLines.add(rawLine)

            // Merge wrapped multiline cell continuation rows when col 0 is blank and exactly one later column continues
            val nonBlankIndices = cells.indices.filter { cells[it].isNotBlank() }
            if (!hadSepBeforeRow &&
                rows.isNotEmpty() &&
                cells.firstOrNull()?.isBlank() == true &&
                nonBlankIndices.size == 1
            ) {
                val prev = rows.last().toMutableList()
                val mergeCol = nonBlankIndices[0]
                while (prev.size <= mergeCol) prev.add("")
                if (prev[mergeCol].isNotBlank()) {
                    prev[mergeCol] = joinWrappedLines(listOf(prev[mergeCol], cells[mergeCol]))
                    rows[rows.lastIndex] = prev
                    hadSepBeforeRow = false
                    i++
                    continue
                }
            }

            rows.add(cells)
            hadSepBeforeRow = false
            i++
        }

        val colCount = maxOf(
            headerCells.size,
            rows.maxOfOrNull { it.size } ?: 0
        ).coerceAtLeast(1)

        return BtwContentSegment.Table(
            headers = if (headerCells.isNotEmpty()) normalizeRow(headerCells, colCount) else emptyList(),
            rows = rows.map { normalizeRow(it, colCount) },
            alignments = List(colCount) { BtwTableAlign.START },
            rawText = rawLines.joinToString("\n")
        ) to i
    }

    private fun isBoxTableStart(lines: List<String>, idx: Int): Boolean {
        val t = lines[idx].trim()
        if (!boxTopBorderRegex.matches(t) && !asciiPlusBorderRegex.matches(t)) return false
        return idx + 1 < lines.size && (
            lines[idx + 1].trim().startsWith("│") ||
                lines[idx + 1].trim().startsWith("║") ||
                lines[idx + 1].trim().startsWith("|")
            )
    }

    private fun consumeBoxTable(lines: List<String>, startIdx: Int): Pair<BtwContentSegment, Int> {
        val rawLines = ArrayList<String>()
        var i = startIdx
        val isAsciiPlus = asciiPlusBorderRegex.matches(lines[startIdx].trim())
        rawLines.add(lines[i])
        i++

        val groups = ArrayList<ArrayList<List<String>>>()
        var curGroup = ArrayList<List<String>>()
        while (i < lines.size) {
            val rawLine = lines[i]
            val t = rawLine.trim()
            if (t.isEmpty()) break
            rawLines.add(rawLine)
            i++

            if ((!isAsciiPlus && boxBottomBorderRegex.matches(t)) ||
                (isAsciiPlus && asciiPlusBorderRegex.matches(t) && (i >= lines.size || !isBoxRowLike(lines[i].trim())))
            ) {
                if (curGroup.isNotEmpty()) groups.add(curGroup)
                curGroup = ArrayList()
                break
            }
            if ((!isAsciiPlus && boxMidBorderRegex.matches(t)) ||
                (isAsciiPlus && asciiPlusBorderRegex.matches(t))
            ) {
                if (curGroup.isNotEmpty()) {
                    groups.add(curGroup)
                    curGroup = ArrayList()
                }
                continue
            }
            if (!isBoxRowLike(t)) {
                rawLines.removeAt(rawLines.lastIndex)
                i--
                break
            }
            val rowCells = splitPipeRow(t, trimOuterEmpty = true)
            curGroup.add(rowCells)
        }
        if (curGroup.isNotEmpty()) groups.add(curGroup)

        val maxCols = groups.flatten().maxOfOrNull { it.size } ?: 0
        if (maxCols <= 1) {
            return BtwContentSegment.CodeBlock(
                code = rawLines.joinToString("\n"),
                language = "图示"
            ) to i
        }

        val mergedRows = ArrayList<List<String>>()
        if (groups.size >= 2) {
            // Group 0 = header (possibly wrapped across multiple lines); subsequent groups = body rows
            val headers = mergeGroupCells(groups[0], maxCols)
            for (g in 1 until groups.size) {
                val grp = groups[g]
                if (g == groups.lastIndex && grp.size > 1 && grp.all { row -> row.firstOrNull()?.isNotBlank() == true }) {
                    // Single body section containing all rows without row-by-row horizontal dividers
                    for (r in grp) mergedRows.add(normalizeRow(r, maxCols))
                } else {
                    mergedRows.add(mergeGroupCells(grp, maxCols))
                }
            }
            return BtwContentSegment.Table(
                headers = headers,
                rows = mergedRows,
                alignments = List(maxCols) { BtwTableAlign.START },
                rawText = rawLines.joinToString("\n")
            ) to i
        }

        val flatRows = groups.firstOrNull().orEmpty()
        val headers = flatRows.firstOrNull()?.let { normalizeRow(it, maxCols) }.orEmpty()
        val body = if (flatRows.size > 1) flatRows.drop(1).map { normalizeRow(it, maxCols) } else emptyList()
        return BtwContentSegment.Table(
            headers = headers,
            rows = body,
            alignments = List(maxCols) { BtwTableAlign.START },
            rawText = rawLines.joinToString("\n")
        ) to i
    }

    private fun consumeBulletList(lines: List<String>, startIdx: Int): Pair<BtwContentSegment.BulletList, Int> {
        val items = ArrayList<BtwListItem>()
        var i = startIdx
        var currentIndent = 0
        var currentMarker = "•"
        val currentParts = ArrayList<String>()

        fun flushItem() {
            if (currentParts.isNotEmpty()) {
                items.add(
                    BtwListItem(
                        marker = currentMarker,
                        text = joinWrappedLines(currentParts),
                        indentLevel = (currentIndent / 2).coerceIn(0, 4)
                    )
                )
                currentParts.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) break
            if (fenceRegex.matches(trimmed) ||
                headingRegex.matches(trimmed) ||
                isMarkdownTableStart(lines, i) ||
                isAgyPipeTableStart(lines, i) ||
                isBoxTableStart(lines, i) ||
                isCodexSpacedTableStart(lines, i)
            ) {
                break
            }
            val bMatch = bulletRegex.matchEntire(line)
            if (bMatch != null) {
                flushItem()
                currentIndent = bMatch.groupValues[1].length
                currentMarker = bMatch.groupValues[2].ifEmpty { bMatch.groupValues[3] }.ifEmpty { "•" }
                currentParts.add(bMatch.groupValues[4].trim())
                i++
                continue
            }
            val leadingSpaces = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (currentParts.isNotEmpty() && leadingSpaces >= currentIndent + 1) {
                currentParts.add(trimmed)
                i++
            } else {
                break
            }
        }
        flushItem()
        return BtwContentSegment.BulletList(items) to i
    }

    private fun isBoxRowLike(t: String): Boolean =
        t.startsWith("│") || t.startsWith("║") || t.startsWith("|") || ('│' in t && !boxHLineOnlyRegex.matches(t))

    private fun isCodexSpacedTableStart(lines: List<String>, idx: Int): Boolean {
        val cur = lines[idx].trim()
        if (codexSpacedRuleRegex.matches(lines[idx])) {
            return idx + 1 < lines.size && lines[idx + 1].isNotBlank()
        }
        if (cur.isEmpty() || boxHLineOnlyRegex.matches(cur)) return false
        return idx + 1 < lines.size && codexSpacedRuleRegex.matches(lines[idx + 1])
    }

    private fun consumeCodexSpacedTable(lines: List<String>, startIdx: Int): Pair<BtwContentSegment.Table, Int> {
        val rawLines = ArrayList<String>()
        var i = startIdx
        val ruleLine = if (codexSpacedRuleRegex.matches(lines[startIdx])) {
            lines[startIdx]
        } else {
            lines[startIdx + 1]
        }
        val colSpans = extractDisplayColSpans(ruleLine)
        val colCount = colSpans.size.coerceAtLeast(1)

        fun sliceCodexRow(raw: String): List<String> =
            List(colCount) { colIdx ->
                val span = colSpans[colIdx]
                val nextStart = colSpans.getOrNull(colIdx + 1)?.first
                val isLast = colIdx == colCount - 1
                sliceByDisplayColumns(raw, span.first, nextStart, isLast).trim()
            }

        var headerCells = emptyList<String>()
        if (!codexSpacedRuleRegex.matches(lines[i])) {
            headerCells = sliceCodexRow(lines[i])
            rawLines.add(lines[i])
            i++
        }
        if (i < lines.size && codexSpacedRuleRegex.matches(lines[i])) {
            rawLines.add(lines[i])
            i++
        }

        val rows = ArrayList<List<String>>()
        var hadSepBeforeRow = true
        while (i < lines.size) {
            val rawLine = lines[i]
            val t = rawLine.trim()
            if (t.isEmpty()) break
            if (codexSpacedRuleRegex.matches(rawLine)) {
                rawLines.add(rawLine)
                hadSepBeforeRow = true
                i++
                continue
            }
            val cells = sliceCodexRow(rawLine)
            rawLines.add(rawLine)
            val nonBlankIndices = cells.indices.filter { cells[it].isNotBlank() }
            if (!hadSepBeforeRow &&
                rows.isNotEmpty() &&
                cells.firstOrNull()?.isBlank() == true &&
                nonBlankIndices.size == 1
            ) {
                val prev = rows.last().toMutableList()
                val mergeCol = nonBlankIndices[0]
                while (prev.size <= mergeCol) prev.add("")
                if (prev[mergeCol].isNotBlank()) {
                    prev[mergeCol] = joinWrappedLines(listOf(prev[mergeCol], cells[mergeCol]))
                    rows[rows.lastIndex] = prev
                    hadSepBeforeRow = false
                    i++
                    continue
                }
            }
            rows.add(cells)
            hadSepBeforeRow = false
            i++
        }

        return BtwContentSegment.Table(
            headers = if (headerCells.isNotEmpty()) normalizeRow(headerCells, colCount) else emptyList(),
            rows = rows.map { normalizeRow(it, colCount) },
            alignments = List(colCount) { BtwTableAlign.START },
            rawText = rawLines.joinToString("\n")
        ) to i
    }

    private fun extractDisplayColSpans(ruleLine: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var spanStart = -1
        var col = 0
        var idx = 0
        while (idx < ruleLine.length) {
            val cp = ruleLine.codePointAt(idx)
            val w = codePointDisplayWidth(cp)
            val isRuleChar = cp == '━'.code || cp == '─'.code || cp == '═'.code
            if (isRuleChar) {
                if (spanStart < 0) spanStart = col
            } else if (spanStart >= 0) {
                spans.add(spanStart until col)
                spanStart = -1
            }
            col += w
            idx += Character.charCount(cp)
        }
        if (spanStart >= 0) {
            spans.add(spanStart until col)
        }
        return spans
    }

    private fun sliceByDisplayColumns(
        line: String,
        startCol: Int,
        nextSpanStart: Int?,
        isLast: Boolean
    ): String {
        val sb = StringBuilder()
        val endCol = if (isLast || nextSpanStart == null) Int.MAX_VALUE else (nextSpanStart - 1).coerceAtLeast(startCol)
        var col = 0
        var idx = 0
        while (idx < line.length) {
            val cp = line.codePointAt(idx)
            val w = codePointDisplayWidth(cp)
            val nextCol = col + w
            if (col >= startCol && (isLast || nextCol <= endCol || (col < endCol && nextCol > endCol))) {
                sb.appendCodePoint(cp)
            } else if (!isLast && col >= endCol) {
                break
            }
            col = nextCol
            idx += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun codePointDisplayWidth(cp: Int): Int =
        if (cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 ||
            cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 ||
            cp in 0xFFE0..0xFFE6 || cp >= 0x1F300
        ) 2 else 1

    private fun splitMarkdownRow(line: String): List<String> {
        var trimmed = line.trim()
        if (trimmed.startsWith("|")) trimmed = trimmed.drop(1)
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.dropLast(1)
        return unescapedPipeRegex.split(trimmed).map { cell ->
            cell.replace("\\|", "|").trim()
        }
    }

    private fun splitPipeRow(line: String, trimOuterEmpty: Boolean): List<String> {
        val sep = when {
            '│' in line -> '│'
            '║' in line -> '║'
            else -> '|'
        }
        var rawParts = line.trimEnd().split(sep)
        if (trimOuterEmpty) {
            val t = line.trim()
            if (t.startsWith(sep) && rawParts.isNotEmpty() && rawParts.first().isBlank()) {
                rawParts = rawParts.drop(1)
            }
            if (t.endsWith(sep) && rawParts.isNotEmpty() && rawParts.last().isBlank()) {
                rawParts = rawParts.dropLast(1)
            }
        }
        return rawParts.map { it.trim() }
    }

    private fun parseAlignment(sepCell: String): BtwTableAlign {
        val t = sepCell.trim()
        val left = t.startsWith(":")
        val right = t.endsWith(":")
        return when {
            left && right -> BtwTableAlign.CENTER
            right -> BtwTableAlign.END
            else -> BtwTableAlign.START
        }
    }

    private fun mergeGroupCells(group: List<List<String>>, colCount: Int): List<String> =
        List(colCount) { col ->
            val parts = group.mapNotNull { row -> row.getOrNull(col)?.trim()?.takeIf { it.isNotEmpty() } }
            joinWrappedLines(parts)
        }

    private fun normalizeRow(cells: List<String>, colCount: Int): List<String> =
        List(colCount) { idx -> cells.getOrNull(idx).orEmpty() }

    private fun normalizeAlignments(alignments: List<BtwTableAlign>, colCount: Int): List<BtwTableAlign> =
        List(colCount) { idx -> alignments.getOrNull(idx) ?: BtwTableAlign.START }

    private fun stripUpToSpaces(line: String, maxSpaces: Int): String {
        var removed = 0
        while (removed < maxSpaces && removed < line.length && line[removed] == ' ') {
            removed++
        }
        return line.substring(removed)
    }

    private fun joinWrappedLines(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        val sb = StringBuilder(parts[0])
        for (idx in 1 until parts.size) {
            val next = parts[idx]
            if (next.isEmpty()) continue
            val prevLast = sb.lastOrNull()
            val nextFirst = next.firstOrNull()
            if (prevLast != null && nextFirst != null && isCjkChar(prevLast) && isCjkChar(nextFirst)) {
                sb.append(next)
            } else {
                sb.append(' ').append(next)
            }
        }
        return sb.toString()
    }

    private fun isCjkChar(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
}
