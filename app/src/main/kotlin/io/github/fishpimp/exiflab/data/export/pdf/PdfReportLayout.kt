package io.github.fishpimp.exiflab.data.export.pdf

import kotlin.math.max
import kotlin.math.min

/**
 * Turns report content into pages of drawing instructions. Pure layout: text is measured through
 * [measurer] and nothing is drawn, so tests can run it with a fake measurer and the PDF writer
 * can count pages before it draws any.
 *
 * Every photo section starts on a new page with a cover block (thumbnail, camera, exposure,
 * facts), then privacy findings, location (map or coordinate panel), one table per metadata
 * directory and warnings. Tables repeat their title and header row on every page they continue
 * on. Pages after the first of a section carry the file name as a running header.
 */
class PdfReportLayout(
    private val measurer: PdfTextMeasurer,
    val page: PdfPageSpec = PdfPageSpec(),
) {
    private val wrapper = PdfTextWrapper(measurer)

    fun layoutPhoto(section: PdfPhotoSection): List<PdfPageLayout> {
        val pages = Pages(runningHeader = section.title)
        cover(pages, section)
        privacy(pages, section.privacy)
        location(pages, section.location)
        if (section.tables.isNotEmpty()) {
            pages.space(SECTION_GAP)
            heading(pages, section.tagsTitle, keepWith = TABLE_START_HEIGHT)
            section.tables.forEach { table(pages, it) }
        }
        section.warnings?.let { notes(pages, it) }
        return pages.finish()
    }

    fun layoutSummary(summary: PdfSummary): List<PdfPageLayout> {
        val pages = Pages(runningHeader = summary.title)
        var y = page.contentTop
        y = text(pages, summary.overline.uppercase(), PdfStyles.Overline, page.contentLeft, y, page.contentWidth)
        y += 4f
        y = text(pages, summary.title, PdfStyles.Title, page.contentLeft, y, page.contentWidth, maxLines = 2)
        y += 2f
        y = text(pages, summary.subtitle, PdfStyles.Subtitle, page.contentLeft, y, page.contentWidth)
        pages.y = y + 18f
        if (summary.stats.isNotEmpty()) {
            val height = statRow(pages, summary.stats, page.contentLeft, pages.y, page.contentWidth)
            pages.y += height + SECTION_GAP
        }
        table(pages, summary.table, showTitle = false)
        summary.notIncluded?.let { notes(pages, it) }
        return pages.finish()
    }

    /** Footer of page [number] (1-based): a rule, [credit] on the left and [pageLabel] on the right. */
    fun footer(credit: String, pageLabel: String): List<PdfOp> {
        val top = page.height - page.margin - page.footerHeight + 12f
        val baseline = top + 14f
        val labelWidth = measurer.width(pageLabel, PdfStyles.Footer)
        val creditLines = wrapper.wrap(credit, PdfStyles.Footer, page.contentWidth - labelWidth - 16f, maxLines = 1)
        return listOf(
            PdfOp.Line(page.contentLeft, top, page.contentRight, top, PdfColor.Line),
            PdfOp.Text(creditLines.first(), page.contentLeft, baseline, PdfStyles.Footer),
            PdfOp.Text(pageLabel, page.contentRight - labelWidth, baseline, PdfStyles.Footer),
        )
    }

    // ---- Cover ----

    private fun cover(pages: Pages, section: PdfPhotoSection) {
        val left = page.contentLeft
        val width = page.contentWidth
        var y = page.contentTop
        y = text(pages, section.overline.uppercase(), PdfStyles.Overline, left, y, width)
        y += 4f
        y = text(pages, section.title, PdfStyles.Title, left, y, width, maxLines = 3)
        section.subtitle?.let {
            y += 2f
            y = text(pages, it, PdfStyles.Subtitle, left, y, width)
        }
        y += 14f

        // The band: thumbnail on the left, camera, exposure and facts on the right.
        val bandTop = y
        val inner = BAND_PADDING
        val thumbSlot = THUMBNAIL_SIZE
        val columnLeft = left + inner + thumbSlot + 18f
        val columnWidth = page.contentRight - inner - columnLeft
        val bandOps = mutableListOf<PdfOp>()

        var cy = bandTop + inner
        cy = textInto(bandOps, section.camera, PdfStyles.Camera, columnLeft, cy, columnWidth, maxLines = 2)
        section.lens?.let { cy = textInto(bandOps, it, PdfStyles.BodyMuted, columnLeft, cy + 1f, columnWidth, maxLines = 2) }
        if (section.exposure.isNotEmpty()) {
            cy += 12f
            cy += statRowInto(bandOps, section.exposure, columnLeft, cy, columnWidth)
        }
        if (section.facts.isNotEmpty()) {
            cy += 12f
            cy = factGrid(bandOps, section.facts, columnLeft, cy, columnWidth, columns = 2)
        }

        val (thumbWidth, thumbHeight) = fit(section.thumbnailAspect ?: 1f, thumbSlot, thumbSlot)
        val bandBottom = max(cy, bandTop + inner + thumbHeight) + inner
        val thumbLeft = left + inner + (thumbSlot - thumbWidth) / 2f
        val thumbTop = bandTop + inner
        pages.add(PdfOp.Rect(left, bandTop, page.contentRight, bandBottom, PdfColor.Panel, radius = 14f))
        pages.add(PdfOp.Image(PdfImage.Thumbnail, thumbLeft, thumbTop, thumbLeft + thumbWidth, thumbTop + thumbHeight, radius = 8f))
        bandOps.forEach(pages::add)
        pages.y = bandBottom
    }

    /** Equal boxes, each a big value over a small label. Returns the row height. */
    private fun statRow(pages: Pages, stats: List<PdfStat>, left: Float, top: Float, width: Float): Float {
        val ops = mutableListOf<PdfOp>()
        val height = statRowInto(ops, stats, left, top, width)
        ops.forEach(pages::add)
        return height
    }

    private fun statRowInto(ops: MutableList<PdfOp>, stats: List<PdfStat>, left: Float, top: Float, width: Float): Float {
        val gap = 6f
        val count = stats.size
        val boxWidth = (width - gap * (count - 1)) / count
        val padding = 7f
        val wrapped = stats.map { stat ->
            wrapper.wrap(stat.value, PdfStyles.Numeral, boxWidth - 2 * padding, maxLines = 2) to
                wrapper.wrap(stat.label, PdfStyles.NumeralLabel, boxWidth - 2 * padding, maxLines = 2)
        }
        val contentHeight = wrapped.maxOf { (value, label) ->
            value.size * PdfStyles.Numeral.lineHeight + 1f + label.size * PdfStyles.NumeralLabel.lineHeight
        }
        val height = contentHeight + 2 * padding
        wrapped.forEachIndexed { index, (value, label) ->
            val boxLeft = left + index * (boxWidth + gap)
            ops.add(PdfOp.Rect(boxLeft, top, boxLeft + boxWidth, top + height, PdfColor.AccentContainer, radius = 8f))
            var y = top + padding
            value.forEach { line ->
                ops.add(PdfOp.Text(line, boxLeft + padding, y + PdfStyles.Numeral.baselineOffset, PdfStyles.Numeral))
                y += PdfStyles.Numeral.lineHeight
            }
            y += 1f
            label.forEach { line ->
                ops.add(PdfOp.Text(line, boxLeft + padding, y + PdfStyles.NumeralLabel.baselineOffset, PdfStyles.NumeralLabel))
                y += PdfStyles.NumeralLabel.lineHeight
            }
        }
        return height
    }

    /** Label over value, [columns] per row. Returns the y below the grid. */
    private fun factGrid(ops: MutableList<PdfOp>, facts: List<PdfFact>, left: Float, top: Float, width: Float, columns: Int): Float {
        val gap = 12f
        val cellWidth = (width - gap * (columns - 1)) / columns
        var y = top
        facts.chunked(columns).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) y += 8f
            var rowBottom = y
            row.forEachIndexed { index, fact ->
                val x = left + index * (cellWidth + gap)
                var cy = textInto(ops, fact.label.uppercase(), PdfStyles.Label, x, y, cellWidth, maxLines = 2)
                cy = textInto(ops, fact.value, if (fact.mono) PdfStyles.ValueMono else PdfStyles.Value, x, cy + 1f, cellWidth, maxLines = 4)
                rowBottom = max(rowBottom, cy)
            }
            y = rowBottom
        }
        return y
    }

    // ---- Privacy and location ----

    private fun privacy(pages: Pages, privacy: PdfPrivacy) {
        pages.space(SECTION_GAP)
        heading(pages, privacy.title, keepWith = 40f)
        val introLines = wrapper.wrap(privacy.intro, PdfStyles.Body, page.contentWidth)
        pages.ensure(introLines.size * PdfStyles.Body.lineHeight)
        pages.y = lines(pages, introLines, PdfStyles.Body, page.contentLeft, pages.y)
        if (privacy.findings.isEmpty()) return
        pages.space(8f)
        // Two findings per row, each a tinted card with a marker, its label and its tags.
        val gap = 8f
        val cardWidth = (page.contentWidth - gap) / 2f
        val padding = 8f
        val markerSpace = 14f
        privacy.findings.chunked(2).forEach { row ->
            val wrapped = row.map { finding ->
                wrapper.wrap(finding.label, PdfStyles.PrivacyLabel, cardWidth - 2 * padding - markerSpace, maxLines = 2) to
                    wrapper.wrap(finding.detail, PdfStyles.PrivacyDetail, cardWidth - 2 * padding - markerSpace, maxLines = 4)
            }
            val height = wrapped.maxOf { (label, detail) ->
                label.size * PdfStyles.PrivacyLabel.lineHeight + detail.size * PdfStyles.PrivacyDetail.lineHeight
            } + 2 * padding
            pages.ensure(height)
            val top = pages.y
            wrapped.forEachIndexed { index, (label, detail) ->
                val left = page.contentLeft + index * (cardWidth + gap)
                pages.add(PdfOp.Rect(left, top, left + cardWidth, top + height, PdfColor.SensitiveContainer, radius = 8f))
                pages.add(PdfOp.Circle(left + padding + 4f, top + padding + PdfStyles.PrivacyLabel.lineHeight / 2f, 3.5f, PdfColor.Sensitive))
                val labelBottom = lines(pages, label, PdfStyles.PrivacyLabel, left + padding + markerSpace, top + padding)
                lines(pages, detail, PdfStyles.PrivacyDetail, left + padding + markerSpace, labelBottom)
            }
            pages.y = top + height + gap
        }
        pages.y -= gap
    }

    private fun location(pages: Pages, block: PdfLocationBlock) {
        pages.space(SECTION_GAP)
        val coordinates = block.coordinates
        if (coordinates == null) {
            heading(pages, block.title, keepWith = 30f)
            block.message?.let { message ->
                val messageLines = wrapper.wrap(message, PdfStyles.Body, page.contentWidth)
                pages.ensure(messageLines.size * PdfStyles.Body.lineHeight)
                pages.y = lines(pages, messageLines, PdfStyles.Body, page.contentLeft, pages.y)
            }
            return
        }
        val factOps = mutableListOf<PdfOp>()
        val factsHeight = if (block.facts.isEmpty()) 0f else factGrid(factOps, block.facts, 0f, 0f, page.contentWidth, columns = 3) + 10f
        // A map and the panel that stands in for it take the same room, credit line included, so a
        // map that fails to load never changes the page count.
        val creditHeight = PdfStyles.Small.lineHeight + 3f
        heading(pages, block.title, keepWith = MAP_HEIGHT + creditHeight + factsHeight)
        val top = pages.y
        val left = page.contentLeft
        val right = page.contentRight
        when (val map = block.map) {
            is PdfMap.Snapshot -> {
                pages.add(PdfOp.Image(PdfImage.Map, left, top, right, top + MAP_HEIGHT, radius = 12f, placeholder = PdfColor.Panel))
                pages.y = top + MAP_HEIGHT + 3f
                val credit = wrapper.wrap(map.attribution, PdfStyles.Small, page.contentWidth, maxLines = 1).first()
                val creditWidth = measurer.width(credit, PdfStyles.Small)
                pages.add(PdfOp.Text(credit, right - creditWidth, pages.y + PdfStyles.Small.baselineOffset, PdfStyles.Small))
                pages.y += PdfStyles.Small.lineHeight
            }
            is PdfMap.Panel, PdfMap.None -> {
                pages.add(PdfOp.Rect(left, top, right, top + MAP_HEIGHT, PdfColor.Panel, radius = 12f))
                // A sparse grid suggests a map without pretending to be one.
                val step = MAP_HEIGHT / 4f
                var gx = left + step
                while (gx < right - 1f) {
                    pages.add(PdfOp.Line(gx, top + 6f, gx, top + MAP_HEIGHT - 6f, PdfColor.Line, 0.4f))
                    gx += step
                }
                for (i in 1..3) pages.add(PdfOp.Line(left + 6f, top + i * step, right - 6f, top + i * step, PdfColor.Line, 0.4f))
                val centerX = (left + right) / 2f
                val centerY = top + MAP_HEIGHT / 2f - 14f
                pages.add(PdfOp.Circle(centerX, centerY, 9f, PdfColor.AccentContainer))
                pages.add(PdfOp.Circle(centerX, centerY, 4f, PdfColor.Accent))
                var y = centerY + 16f
                y = centered(pages, coordinates, PdfStyles.Coordinates, centerX, y)
                block.coordinatesDms?.let { y = centered(pages, it, PdfStyles.ValueMono, centerX, y + 1f) }
                (map as? PdfMap.Panel)?.let { centered(pages, it.note, PdfStyles.BodyMuted, centerX, y + 4f) }
                pages.y = top + MAP_HEIGHT + creditHeight
            }
        }
        if (block.facts.isNotEmpty()) {
            pages.y += 10f
            val baseY = pages.y
            factOps.forEach { op -> pages.add(op.translate(page.contentLeft, baseY)) }
            pages.y = baseY + factsHeight - 10f
        }
    }

    // ---- Tables ----

    private fun table(pages: Pages, table: PdfTable, showTitle: Boolean = true) {
        val left = page.contentLeft
        val width = page.contentWidth
        val totalWeight = table.columns.sumOf { it.weight.toDouble() }.toFloat()
        val widths = table.columns.map { width * it.weight / totalWeight }
        val headerLines = table.columns.mapIndexed { index, column ->
            wrapper.wrap(column.header.uppercase(), PdfStyles.TableHeader, widths[index] - 2 * CELL_PADDING_X, maxLines = 2)
        }
        val headerHeight = headerLines.maxOf { it.size } * PdfStyles.TableHeader.lineHeight + 2 * CELL_PADDING_Y

        fun rowLines(row: List<String>): List<List<String>> = table.columns.mapIndexed { index, column ->
            wrapper.wrap(row.getOrElse(index) { "" }, column.style, widths[index] - 2 * CELL_PADDING_X, maxLines = MAX_CELL_LINES)
        }
        fun rowHeight(lines: List<List<String>>): Float =
            table.columns.indices.maxOf { lines[it].size * table.columns[it].style.lineHeight } + 2 * CELL_PADDING_Y

        val titleHeight = if (showTitle) PdfStyles.Subheading.lineHeight + 6f else 0f

        fun startTable(continued: Boolean) {
            if (showTitle) {
                val title = if (continued) "${table.title} ${table.continued}" else table.title
                val y = pages.y
                pages.add(PdfOp.Text(title, left, y + PdfStyles.Subheading.baselineOffset, PdfStyles.Subheading))
                if (!continued && table.detail.isNotEmpty()) {
                    val titleWidth = measurer.width(title, PdfStyles.Subheading)
                    pages.add(PdfOp.Text(table.detail, left + titleWidth + 8f, y + PdfStyles.Subheading.baselineOffset, PdfStyles.BodyMuted))
                }
                pages.y += titleHeight
            }
            val top = pages.y
            pages.add(PdfOp.Rect(left, top, left + width, top + headerHeight, PdfColor.PanelStrong, radius = 4f))
            var x = left
            headerLines.forEachIndexed { index, header ->
                lines(pages, header, PdfStyles.TableHeader, x + CELL_PADDING_X, top + CELL_PADDING_Y)
                x += widths[index]
            }
            pages.y = top + headerHeight
        }

        val first = table.rows.firstOrNull()?.let(::rowLines)
        pages.space(if (showTitle) 14f else 0f)
        pages.ensure(titleHeight + headerHeight + (first?.let(::rowHeight) ?: 0f))
        startTable(continued = false)
        table.rows.forEachIndexed { rowIndex, row ->
            val cells = if (rowIndex == 0 && first != null) first else rowLines(row)
            val height = rowHeight(cells)
            if (pages.y + height > page.contentBottom) {
                pages.newPage()
                startTable(continued = true)
            }
            val top = pages.y
            if (rowIndex % 2 == 1) pages.add(PdfOp.Rect(left, top, left + width, top + height, PdfColor.Panel))
            var x = left
            cells.forEachIndexed { index, cell ->
                lines(pages, cell, table.columns[index].style, x + CELL_PADDING_X, top + CELL_PADDING_Y)
                x += widths[index]
            }
            pages.y = top + height
            pages.add(PdfOp.Line(left, pages.y, left + width, pages.y, PdfColor.Line, 0.3f))
        }
    }

    private fun notes(pages: Pages, notes: PdfNotes) {
        pages.space(SECTION_GAP)
        heading(pages, notes.title, keepWith = 20f)
        notes.lines.forEach { line ->
            val wrapped = wrapper.wrap(line, PdfStyles.Body, page.contentWidth - 12f)
            pages.ensure(wrapped.size * PdfStyles.Body.lineHeight)
            val top = pages.y
            pages.add(PdfOp.Circle(page.contentLeft + 3f, top + PdfStyles.Body.lineHeight / 2f, 1.6f, PdfColor.Muted))
            pages.y = lines(pages, wrapped, PdfStyles.Body, page.contentLeft + 12f, top) + 2f
        }
    }

    // ---- Text helpers ----

    /** A section heading with a rule under it, moved to the next page with whatever must follow it ([keepWith]). */
    private fun heading(pages: Pages, title: String, keepWith: Float) {
        val titleLines = wrapper.wrap(title, PdfStyles.Heading, page.contentWidth, maxLines = 2)
        val height = titleLines.size * PdfStyles.Heading.lineHeight + 8f
        pages.ensure(height + keepWith)
        pages.y = lines(pages, titleLines, PdfStyles.Heading, page.contentLeft, pages.y)
        pages.y += 3f
        pages.add(PdfOp.Line(page.contentLeft, pages.y, page.contentRight, pages.y, PdfColor.Line, 0.6f))
        pages.y += 5f
    }

    private fun text(pages: Pages, text: String, style: PdfTextStyle, x: Float, top: Float, width: Float, maxLines: Int = Int.MAX_VALUE): Float =
        lines(pages, wrapper.wrap(text, style, width, maxLines), style, x, top)

    private fun textInto(ops: MutableList<PdfOp>, text: String, style: PdfTextStyle, x: Float, top: Float, width: Float, maxLines: Int): Float {
        var y = top
        wrapper.wrap(text, style, width, maxLines).forEach { line ->
            ops.add(PdfOp.Text(line, x, y + style.baselineOffset, style))
            y += style.lineHeight
        }
        return y
    }

    private fun lines(pages: Pages, lines: List<String>, style: PdfTextStyle, x: Float, top: Float): Float {
        var y = top
        lines.forEach { line ->
            if (line.isNotEmpty()) pages.add(PdfOp.Text(line, x, y + style.baselineOffset, style))
            y += style.lineHeight
        }
        return y
    }

    private fun centered(pages: Pages, text: String, style: PdfTextStyle, centerX: Float, top: Float): Float {
        var y = top
        wrapper.wrap(text, style, page.contentWidth - 40f, maxLines = 2).forEach { line ->
            val width = measurer.width(line, style)
            pages.add(PdfOp.Text(line, centerX - width / 2f, y + style.baselineOffset, style))
            y += style.lineHeight
        }
        return y
    }

    /** Collects ops into pages; [y] is the cursor on the current page. */
    private inner class Pages(private val runningHeader: String) {
        private val finished = mutableListOf<PdfPageLayout>()
        private var ops = mutableListOf<PdfOp>()
        var y: Float = page.contentTop

        fun add(op: PdfOp) {
            ops.add(op)
        }

        /** Vertical space, unless at the top of a page. */
        fun space(amount: Float) {
            if (y > contentStart() + 0.5f) y += amount
        }

        /** Starts a new page unless [height] still fits on this one. */
        fun ensure(height: Float) {
            if (y + height > page.contentBottom && y > contentStart() + 0.5f) newPage()
        }

        fun newPage() {
            finished.add(PdfPageLayout(ops))
            ops = mutableListOf()
            val header = wrapper.wrap(runningHeader, PdfStyles.Small, page.contentWidth, maxLines = 1).first()
            ops.add(PdfOp.Text(header, page.contentLeft, page.contentTop + PdfStyles.Small.baselineOffset, PdfStyles.Small))
            y = contentStart()
        }

        private fun contentStart(): Float = if (finished.isEmpty()) page.contentTop else page.contentTop + RUNNING_HEADER_HEIGHT

        fun finish(): List<PdfPageLayout> {
            finished.add(PdfPageLayout(ops))
            return finished.toList()
        }
    }

    companion object {
        const val THUMBNAIL_SIZE = 180f
        const val MAP_HEIGHT = 190f
        const val MAX_CELL_LINES = 30
        private const val BAND_PADDING = 16f
        private const val SECTION_GAP = 22f
        private const val CELL_PADDING_X = 5f
        private const val CELL_PADDING_Y = 3.5f
        private const val RUNNING_HEADER_HEIGHT = 22f

        /** A table title, its header and a first row: what a heading above tables keeps with it. */
        private const val TABLE_START_HEIGHT = 60f

        /** The largest [aspect] (width / height) box inside [maxWidth] by [maxHeight]. */
        fun fit(aspect: Float, maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
            if (!aspect.isFinite() || aspect <= 0f) return maxWidth to maxHeight
            val width = min(maxWidth, maxHeight * aspect)
            return width to width / aspect
        }
    }
}

private fun PdfOp.translate(dx: Float, dy: Float): PdfOp = when (this) {
    is PdfOp.Text -> copy(x = x + dx, baseline = baseline + dy)
    is PdfOp.Rect -> copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)
    is PdfOp.Circle -> copy(centerX = centerX + dx, centerY = centerY + dy)
    is PdfOp.Line -> copy(x1 = x1 + dx, y1 = y1 + dy, x2 = x2 + dx, y2 = y2 + dy)
    is PdfOp.Image -> copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)
}
