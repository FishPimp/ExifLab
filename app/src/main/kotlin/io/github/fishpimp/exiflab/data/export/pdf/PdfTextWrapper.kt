package io.github.fishpimp.exiflab.data.export.pdf

/**
 * Breaks text into lines that fit a width: at spaces first, then after "-", "/", "_", "," and ";"
 * (file names, paths and lists), and inside a word only when nothing else fits, as long hex dumps
 * need. Line breaks in the text are kept.
 */
class PdfTextWrapper(private val measurer: PdfTextMeasurer) {

    /**
     * [text] as lines no wider than [width]. With more than [maxLines] lines the last one kept
     * ends in an ellipsis. Very long text is cut to [MAX_CHARS] first, so a binary blob cannot fill
     * pages.
     */
    fun wrap(text: String, style: PdfTextStyle, width: Float, maxLines: Int = Int.MAX_VALUE): List<String> {
        require(maxLines >= 1)
        val clipped = text.length > MAX_CHARS
        val source = if (clipped) text.take(MAX_CHARS) else text
        val lines = mutableListOf<String>()
        val paragraphs = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (paragraph in paragraphs) {
            wrapParagraph(paragraph, style, width, lines, maxLines + 1)
            if (lines.size > maxLines) break
        }
        if (lines.size > maxLines || clipped) {
            val kept = lines.take(maxLines).toMutableList()
            kept[kept.lastIndex] = ellipsize(kept.last(), style, width)
            return kept
        }
        return lines
    }

    /** Width of the widest of [lines]. */
    fun widest(lines: List<String>, style: PdfTextStyle): Float = lines.maxOfOrNull { measurer.width(it, style) } ?: 0f

    private fun wrapParagraph(paragraph: String, style: PdfTextStyle, width: Float, out: MutableList<String>, limit: Int) {
        if (paragraph.isEmpty()) {
            out.add("")
            return
        }
        var line = StringBuilder()
        for (segment in segments(paragraph)) {
            if (out.size >= limit) return
            val candidate = line.toString() + segment
            if (measurer.width(candidate.trimEnd(), style) <= width) {
                line.append(segment)
                continue
            }
            if (line.isNotEmpty()) {
                out.add(line.toString().trimEnd())
                line = StringBuilder()
            }
            var rest = segment.trimStart()
            // A segment wider than a whole line is broken wherever it has to be.
            while (rest.isNotEmpty() && measurer.width(rest.trimEnd(), style) > width) {
                if (out.size >= limit) return
                val fit = fittingPrefix(rest, style, width)
                out.add(rest.substring(0, fit))
                rest = rest.substring(fit)
            }
            line.append(rest)
        }
        if (line.isNotEmpty() || out.isEmpty()) out.add(line.toString().trimEnd())
    }

    /** Pieces of [text] that each end at a break opportunity (the separator stays with the piece before it). */
    private fun segments(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            val char = text[i]
            val breakAfter = char == ' ' && (i + 1 >= text.length || text[i + 1] != ' ') || char in BREAK_AFTER
            if (breakAfter) {
                result.add(text.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start < text.length) result.add(text.substring(start))
        return result
    }

    /** The number of leading characters of [text] that fit [width]; at least one, and never splitting a surrogate pair. */
    private fun fittingPrefix(text: String, style: PdfTextStyle, width: Float): Int {
        var low = 1
        var high = minOf(text.length, MAX_LINE_CHARS)
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (measurer.width(text.substring(0, mid), style) <= width) low = mid else high = mid - 1
        }
        if (low < text.length && Character.isHighSurrogate(text[low - 1])) low = if (low > 1) low - 1 else low + 1
        return low.coerceAtMost(text.length)
    }

    private fun ellipsize(line: String, style: PdfTextStyle, width: Float): String {
        var text = line.trimEnd()
        while (text.isNotEmpty() && measurer.width(text + ELLIPSIS, style) > width) {
            text = text.dropLast(1).trimEnd()
        }
        return text + ELLIPSIS
    }

    companion object {
        /** Longest text wrapped in full; tag values beyond this are cut with an ellipsis. */
        const val MAX_CHARS = 4_000
        private const val MAX_LINE_CHARS = 400
        const val ELLIPSIS = "…"
        private val BREAK_AFTER = setOf('-', '/', '_', ',', ';', '​')
    }
}
