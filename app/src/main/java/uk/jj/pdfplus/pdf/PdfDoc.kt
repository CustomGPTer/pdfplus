package uk.jj.pdfplus.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import uk.jj.pdfplus.App
import java.io.File
import java.util.Date

class NeedsPassword : Exception("This PDF is password protected")

class AnnotInfo(
    val page: Int, val index: Int, val type: Int, val rect: RectF, val contents: String,
    val author: String, val color: Int, val modified: Long,
) {
    val typeName: String get() = annotTypeName(type)
    val resizable: Boolean
        get() = type in intArrayOf(PDFAnnotation.TYPE_SQUARE, PDFAnnotation.TYPE_CIRCLE, PDFAnnotation.TYPE_FREE_TEXT, PDFAnnotation.TYPE_STAMP, PDFAnnotation.TYPE_REDACT)
}

class WidgetInfo(
    val page: Int, val index: Int, val rect: RectF, val kind: String, val value: String,
    val options: List<String>, val label: String, val readOnly: Boolean, val multiline: Boolean, val signed: Boolean,
)

class LinkInfo(val rect: RectF, val uri: String, val page: Int)
class OutlineItem(val title: String, val page: Int, val level: Int)
class Para(val text: String, val rect: RectF)
class LayerInfo(val index: Int, val name: String, val visible: Boolean)

fun annotTypeName(type: Int): String = when (type) {
    PDFAnnotation.TYPE_TEXT -> "Note"
    PDFAnnotation.TYPE_FREE_TEXT -> "Text box"
    PDFAnnotation.TYPE_LINE -> "Line"
    PDFAnnotation.TYPE_SQUARE -> "Rectangle"
    PDFAnnotation.TYPE_CIRCLE -> "Ellipse"
    PDFAnnotation.TYPE_POLYGON -> "Area"
    PDFAnnotation.TYPE_POLY_LINE -> "Path"
    PDFAnnotation.TYPE_HIGHLIGHT -> "Highlight"
    PDFAnnotation.TYPE_UNDERLINE -> "Underline"
    PDFAnnotation.TYPE_SQUIGGLY -> "Squiggle"
    PDFAnnotation.TYPE_STRIKE_OUT -> "Strikeout"
    PDFAnnotation.TYPE_REDACT -> "Redaction mark"
    PDFAnnotation.TYPE_STAMP -> "Stamp"
    PDFAnnotation.TYPE_INK -> "Drawing"
    PDFAnnotation.TYPE_FILE_ATTACHMENT -> "Attachment"
    else -> "Annotation"
}

fun Int.toRgb(): FloatArray = floatArrayOf(Color.red(this) / 255f, Color.green(this) / 255f, Color.blue(this) / 255f)
fun FloatArray?.toColorInt(): Int = when {
    this == null || isEmpty() -> Color.TRANSPARENT
    size == 1 -> Color.rgb((this[0] * 255).toInt(), (this[0] * 255).toInt(), (this[0] * 255).toInt())
    size >= 3 -> Color.rgb((this[0] * 255).toInt(), (this[1] * 255).toInt(), (this[2] * 255).toInt())
    else -> Color.BLACK
}

fun Rect.toRectF() = RectF(x0, y0, x1, y1)
fun RectF.toMu() = Rect(left, top, right, bottom)
fun Quad.toArray() = floatArrayOf(ul_x, ul_y, ur_x, ur_y, ll_x, ll_y, lr_x, lr_y)
fun FloatArray.toQuad() = Quad(this[0], this[1], this[2], this[3], this[4], this[5], this[6], this[7])
fun FloatArray.quadBounds() = RectF(
    minOf(this[0], this[2], this[4], this[6]), minOf(this[1], this[3], this[5], this[7]),
    maxOf(this[0], this[2], this[4], this[6]), maxOf(this[1], this[3], this[5], this[7]),
)

/**
 * One open document. Every method must run on the [uk.jj.pdfplus.Mu] thread (or, for library work,
 * on a handle opened on MuBg) because MuPDF documents are not re-entrant.
 */
class PdfDoc private constructor(val path: String, private var doc: Document, private var password: String?) {
    var pdf: PDFDocument? = if (doc.isPDF) doc.asPDF() else null
        private set
    var pageCount: Int = doc.countPages()
        private set

    /** Per page: width, height, originX, originY in points. Filled lazily; unknown pages copy page 0. */
    var sizes: Array<FloatArray> = emptyArray()
        private set
    private var sizesKnown = BooleanArray(0)

    private val pages = object : LinkedHashMap<Int, Page>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, Page>): Boolean {
            if (size > 12) { runCatching { e.value.destroy() }; return true }
            return false
        }
    }
    private val texts = object : LinkedHashMap<Int, StructuredText>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, StructuredText>): Boolean {
            if (size > 6) { runCatching { e.value.destroy() }; return true }
            return false
        }
    }

    val isPdf: Boolean get() = pdf != null
    val hasChanges: Boolean get() = pdf?.hasUnsavedChanges() == true
    val canUndo: Boolean get() = pdf?.canUndo() == true
    val canRedo: Boolean get() = pdf?.canRedo() == true
    val title: String get() = runCatching { doc.getMetaData(Document.META_INFO_TITLE) }.getOrNull().orEmpty()

    init {
        initSizes()
        runCatching { pdf?.enableJournal() }
    }

    private fun initSizes() {
        pageCount = doc.countPages()
        val first = if (pageCount > 0) measure(0) else floatArrayOf(595f, 842f, 0f, 0f)
        sizes = Array(pageCount) { first.copyOf() }
        sizesKnown = BooleanArray(pageCount)
        if (pageCount > 0) sizesKnown[0] = true
    }

    private fun measure(i: Int): FloatArray {
        val b = page(i).bounds
        return floatArrayOf(maxOf(b.x1 - b.x0, 1f), maxOf(b.y1 - b.y0, 1f), b.x0, b.y0)
    }

    /** Measures up to [max] not-yet-measured pages around [around]. Returns true if anything changed. */
    fun measureMore(around: Int, max: Int): Boolean {
        var changed = false
        var done = 0
        var d = 0
        while (done < max && d < pageCount) {
            for (i in intArrayOf(around + d, around - d)) {
                if (i in 0 until pageCount && !sizesKnown[i]) {
                    val m = runCatching { measure(i) }.getOrNull() ?: sizes[0].copyOf()
                    if (!m.contentEquals(sizes[i])) changed = true
                    sizes[i] = m; sizesKnown[i] = true; done++
                }
            }
            d++
        }
        return changed
    }

    val allMeasured: Boolean get() = sizesKnown.all { it }

    fun page(i: Int): Page = pages.getOrPut(i) { doc.loadPage(i) }
    fun pdfPage(i: Int): PDFPage? = page(i) as? PDFPage
    fun text(i: Int): StructuredText = texts.getOrPut(i) { page(i).toStructuredText("preserve-whitespace") }

    /** Runs a change as one undoable step. Returns null if it failed (the step is rolled back). */
    fun <T> operation(name: String, block: (PDFDocument) -> T): T? = op(name, block)

    /** Forget cached pages and text after low-level edits to page content. */
    fun invalidate() = dropCaches()

    private fun dropCaches() {
        texts.values.forEach { runCatching { it.destroy() } }; texts.clear()
        pages.values.forEach { runCatching { it.destroy() } }; pages.clear()
    }

    fun close() {
        dropCaches()
        runCatching { doc.destroy() }
    }

    fun info(): Map<String, String> = linkedMapOf(
        "Title" to Document.META_INFO_TITLE, "Author" to Document.META_INFO_AUTHOR, "Subject" to Document.META_INFO_SUBJECT,
        "Keywords" to Document.META_INFO_KEYWORDS, "Created with" to Document.META_INFO_CREATOR, "Producer" to Document.META_INFO_PRODUCER,
        "Format" to Document.META_FORMAT, "Encryption" to Document.META_ENCRYPTION,
    ).mapValues { runCatching { doc.getMetaData(it.value) }.getOrNull().orEmpty() }

    fun setInfo(title: String, author: String, subject: String, keywords: String) = op("Properties") {
        doc.setMetaData(Document.META_INFO_TITLE, title); doc.setMetaData(Document.META_INFO_AUTHOR, author)
        doc.setMetaData(Document.META_INFO_SUBJECT, subject); doc.setMetaData(Document.META_INFO_KEYWORDS, keywords)
    }

    // ---- Rendering ----

    /** Renders the region (x, y, w, h) of page [i] drawn at [scale] pixels per point. */
    fun render(i: Int, scale: Float, x: Int, y: Int, w: Int, h: Int, night: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val s = sizes[i]
        val dev = AndroidDrawDevice(bmp, x + Math.round(s[2] * scale), y + Math.round(s[3] * scale))
        try {
            page(i).run(dev, Matrix(scale, scale), null)
            if (night) dev.invertLuminance()
            dev.close()
        } finally {
            dev.destroy()
        }
        return bmp
    }

    fun thumbnail(i: Int, widthPx: Int): Bitmap {
        val s = sizes[i].let { if (sizesKnown[i]) it else measure(i).also { m -> sizes[i] = m; sizesKnown[i] = true } }
        val scale = widthPx / s[0]
        return render(i, scale, 0, 0, widthPx, Math.round(s[1] * scale), false)
    }

    // ---- Text ----

    fun pageText(i: Int): String = runCatching { text(i).asText() }.getOrDefault("")

    fun paragraphs(i: Int): List<Para> {
        val out = ArrayList<Para>()
        val blocks = runCatching { text(i).blocks }.getOrNull() ?: return out
        for (b in blocks) {
            val lines = b.lines ?: continue
            val sb = StringBuilder()
            for (line in lines) {
                val t = buildString { line.chars?.forEach { appendCodePoint(it.c) } }.trim()
                if (t.isEmpty()) continue
                if (sb.isNotEmpty()) {
                    if (sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter()) sb.setLength(sb.length - 1) else sb.append(' ')
                }
                sb.append(t)
            }
            if (sb.isNotBlank()) out.add(Para(sb.toString(), b.bbox.toRectF()))
        }
        return out
    }

    fun search(i: Int, needle: String): List<List<FloatArray>> =
        runCatching { page(i).search(needle).map { hit -> hit.map { it.toArray() } } }.getOrDefault(emptyList())

    /** Snaps a point to the word under it; returns start and end points (x0, y0, x1, y1) or null. */
    fun wordAt(i: Int, x: Float, y: Float): FloatArray? {
        val q = runCatching { text(i).snapSelection(Point(x, y), Point(x, y), 1) }.getOrNull() ?: return null
        if (!q.isValid || (q.ul_x == q.lr_x && q.ul_y == q.lr_y)) return null
        return floatArrayOf(q.ul_x, (q.ul_y + q.ll_y) / 2f, q.lr_x, (q.ur_y + q.lr_y) / 2f)
    }

    fun selectionQuads(i: Int, ax: Float, ay: Float, bx: Float, by: Float): List<FloatArray> =
        runCatching { text(i).highlight(Point(ax, ay), Point(bx, by)).map { it.toArray() } }.getOrDefault(emptyList())

    fun selectionText(i: Int, ax: Float, ay: Float, bx: Float, by: Float): String =
        runCatching { text(i).copy(Point(ax, ay), Point(bx, by)) }.getOrDefault("")

    // ---- Navigation ----

    fun outline(): List<OutlineItem> {
        val out = ArrayList<OutlineItem>()
        fun walk(items: Array<Outline>?, level: Int) {
            items ?: return
            for (o in items) {
                val p = runCatching { doc.pageNumberFromLocation(doc.resolveLink(o)) }.getOrDefault(-1)
                out.add(OutlineItem(o.title ?: "", p, level))
                walk(o.down, level + 1)
            }
        }
        runCatching { walk(doc.loadOutline(), 0) }
        return out
    }

    fun links(i: Int): List<LinkInfo> = runCatching {
        page(i).links?.map { l ->
            val ext = l.isExternal
            val target = if (ext) -1 else runCatching { doc.pageNumberFromLocation(doc.resolveLink(l)) }.getOrDefault(-1)
            LinkInfo(l.bounds.toRectF(), l.uri ?: "", target)
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun layers(): List<LayerInfo> {
        val p = pdf ?: return emptyList()
        return runCatching { (0 until p.countLayers()).map { LayerInfo(it, p.getLayerName(it) ?: "Layer ${it + 1}", p.isLayerVisible(it)) } }.getOrDefault(emptyList())
    }

    fun setLayerVisible(index: Int, visible: Boolean) {
        runCatching { pdf?.setLayerVisible(index, visible) }
        dropCaches()
    }

    // ---- Annotations ----

    private inline fun <T> op(name: String, block: (PDFDocument) -> T): T? {
        val p = pdf ?: return null
        var began = false
        return try {
            p.beginOperation(name); began = true
            val r = block(p)
            p.endOperation(); began = false
            r
        } catch (e: Throwable) {
            if (began) runCatching { p.abandonOperation() }
            null
        }
    }

    private fun stampCommon(a: PDFAnnotation) {
        val author = App.prefs.author
        if (author.isNotBlank()) runCatching { a.author = author }
        runCatching { a.modificationDate = Date() }
    }

    fun annots(i: Int): List<AnnotInfo> {
        val pg = pdfPage(i) ?: return emptyList()
        val list = runCatching { pg.annotations }.getOrNull() ?: return emptyList()
        return list.mapIndexed { idx, a ->
            AnnotInfo(
                i, idx, a.type, a.bounds.toRectF(), runCatching { a.contents }.getOrNull().orEmpty(),
                runCatching { if (a.hasAuthor()) a.author else "" }.getOrNull().orEmpty(),
                runCatching { a.color.toColorInt() }.getOrDefault(Color.TRANSPARENT),
                runCatching { a.modificationDate?.time ?: 0L }.getOrDefault(0L),
            )
        }.filter { it.type != PDFAnnotation.TYPE_LINK && it.type != PDFAnnotation.TYPE_POPUP && it.type != PDFAnnotation.TYPE_WIDGET }
    }

    private fun annotAt(i: Int, index: Int): Pair<PDFPage, PDFAnnotation>? {
        val pg = pdfPage(i) ?: return null
        val a = pg.annotations?.getOrNull(index) ?: return null
        return pg to a
    }

    private fun finish(pg: PDFPage, a: PDFAnnotation?) {
        a?.let { stampCommon(it); it.update() }
        pg.update()
    }

    fun addMarkup(i: Int, type: Int, quads: List<FloatArray>, color: Int, opacity: Float) = op("Markup") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(type)
        a.setQuadPoints(quads.map { it.toQuad() }.toTypedArray())
        a.color = color.toRgb()
        if (opacity < 1f) a.opacity = opacity
        finish(pg, a)
    }

    fun addInk(i: Int, strokes: List<FloatArray>, color: Int, width: Float, opacity: Float) = op("Draw") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(PDFAnnotation.TYPE_INK)
        for (s in strokes) {
            a.addInkListStroke()
            var k = 0
            while (k + 1 < s.size) { a.addInkListStrokeVertex(s[k], s[k + 1]); k += 2 }
        }
        a.color = color.toRgb()
        a.borderWidth = width
        if (opacity < 1f) a.opacity = opacity
        finish(pg, a)
    }

    fun addShape(i: Int, type: Int, rect: RectF, color: Int, fill: Int, width: Float, opacity: Float, cloudy: Boolean = false) = op("Shape") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(type)
        a.rect = rect.toMu()
        a.color = color.toRgb()
        if (fill != Color.TRANSPARENT) a.interiorColor = fill.toRgb()
        a.borderWidth = width
        if (cloudy) runCatching { a.borderEffect = PDFAnnotation.BORDER_EFFECT_CLOUDY; a.borderEffectIntensity = 1f }
        if (opacity < 1f) a.opacity = opacity
        finish(pg, a)
    }

    fun addLine(i: Int, ax: Float, ay: Float, bx: Float, by: Float, arrow: Boolean, color: Int, width: Float, caption: String = "") = op("Line") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(PDFAnnotation.TYPE_LINE)
        a.setLine(Point(ax, ay), Point(bx, by))
        a.setLineEndingStyles(PDFAnnotation.LINE_ENDING_NONE, if (arrow) PDFAnnotation.LINE_ENDING_CLOSED_ARROW else PDFAnnotation.LINE_ENDING_NONE)
        a.color = color.toRgb()
        if (arrow) a.interiorColor = color.toRgb()
        a.borderWidth = width
        if (caption.isNotEmpty()) {
            a.contents = caption
            runCatching { a.lineCaption = true; a.setLineEndingStyles(PDFAnnotation.LINE_ENDING_BUTT, PDFAnnotation.LINE_ENDING_BUTT); a.intent = PDFAnnotation.IT_LINE_DIMENSION }
        }
        finish(pg, a)
    }

    fun addPoly(i: Int, pts: FloatArray, closed: Boolean, color: Int, width: Float, contents: String) = op("Path") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(if (closed) PDFAnnotation.TYPE_POLYGON else PDFAnnotation.TYPE_POLY_LINE)
        var k = 0
        while (k + 1 < pts.size) { a.addVertex(pts[k], pts[k + 1]); k += 2 }
        a.color = color.toRgb()
        a.borderWidth = width
        if (contents.isNotEmpty()) a.contents = contents
        finish(pg, a)
    }

    fun addFreeText(i: Int, rect: RectF, text: String, size: Float, color: Int, calloutTo: FloatArray? = null) = op("Text box") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
        a.rect = rect.toMu()
        a.contents = text
        a.setDefaultAppearance("Helv", size, color.toRgb())
        a.borderWidth = if (calloutTo != null) 1f else 0f
        if (calloutTo != null) runCatching {
            a.intent = PDFAnnotation.IT_FREETEXT_CALLOUT
            a.color = color.toRgb()
            val knee = Point((calloutTo[0] + rect.left) / 2f, rect.centerY())
            a.setCalloutLine(arrayOf(Point(calloutTo[0], calloutTo[1]), knee, Point(rect.left, rect.centerY())))
            a.calloutStyle = PDFAnnotation.LINE_ENDING_CLOSED_ARROW
        }
        finish(pg, a)
    }

    fun addNote(i: Int, x: Float, y: Float, text: String, color: Int) = op("Note") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(PDFAnnotation.TYPE_TEXT)
        a.rect = Rect(x, y, x + 20f, y + 20f)
        a.contents = text
        a.color = color.toRgb()
        runCatching { a.icon = "Note" }
        finish(pg, a)
    }

    fun addStamp(i: Int, rect: RectF, name: String) = op("Stamp") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(PDFAnnotation.TYPE_STAMP)
        a.rect = rect.toMu()
        a.icon = name
        finish(pg, a)
    }

    /** Places an image (PNG or JPEG bytes) as a movable stamp: used for pictures and signatures. */
    fun addImageStamp(i: Int, rect: RectF, bytes: ByteArray, label: String) = op("Image") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(PDFAnnotation.TYPE_STAMP)
        a.rect = rect.toMu()
        val img = Image(bytes)
        try { a.setStampImage(img) } finally { img.destroy() }
        a.rect = rect.toMu()
        if (label.isNotEmpty()) a.contents = label
        finish(pg, a)
    }

    fun addRedactMark(i: Int, rect: RectF?, quads: List<FloatArray>?) = op("Mark for redaction") {
        val pg = pdfPage(i) ?: return@op
        val a = pg.createAnnotation(PDFAnnotation.TYPE_REDACT)
        if (!quads.isNullOrEmpty()) a.setQuadPoints(quads.map { it.toQuad() }.toTypedArray()) else if (rect != null) a.rect = rect.toMu()
        finish(pg, a)
    }

    /** Permanently removes everything under the redaction marks. [pageNo] = -1 applies to all pages. */
    fun applyRedactions(pageNo: Int, blackBoxes: Boolean): Int {
        var n = 0
        op("Redact") {
            val range = if (pageNo >= 0) pageNo..pageNo else 0 until pageCount
            for (i in range) {
                val pg = pdfPage(i) ?: continue
                val marks = pg.annotations?.count { it.type == PDFAnnotation.TYPE_REDACT } ?: 0
                if (marks == 0) continue
                // image method 2 = blank out covered pixels, line art 2 = remove if touched, text 0 = remove
                if (pg.applyRedactions(blackBoxes, 2, 2, 0)) n += marks
                pg.update()
                texts.remove(i)?.let { runCatching { it.destroy() } }
            }
        }
        return n
    }

    fun moveAnnot(i: Int, index: Int, dx: Float, dy: Float) = op("Move") {
        val (pg, a) = annotAt(i, index) ?: return@op
        when {
            a.hasInkList() -> {
                val ink = a.inkList
                a.clearInkList()
                for (stroke in ink) { a.addInkListStroke(); for (p in stroke) a.addInkListStrokeVertex(p.x + dx, p.y + dy) }
            }
            a.hasLine() -> { val l = a.line; a.setLine(Point(l[0].x + dx, l[0].y + dy), Point(l[1].x + dx, l[1].y + dy)) }
            a.hasVertices() -> { val v = a.vertices; a.clearVertices(); for (p in v) a.addVertex(p.x + dx, p.y + dy) }
            a.hasQuadPoints() && a.type != PDFAnnotation.TYPE_REDACT -> return@op
            a.hasRect() -> { val r = a.rect; a.rect = Rect(r.x0 + dx, r.y0 + dy, r.x1 + dx, r.y1 + dy) }
        }
        finish(pg, a)
    }

    fun resizeAnnot(i: Int, index: Int, rect: RectF) = op("Resize") {
        val (pg, a) = annotAt(i, index) ?: return@op
        if (a.hasRect()) a.rect = rect.toMu()
        finish(pg, a)
    }

    fun editAnnot(i: Int, index: Int, contents: String?, color: Int?) = op("Edit") {
        val (pg, a) = annotAt(i, index) ?: return@op
        if (contents != null) a.contents = contents
        if (color != null) {
            if (a.type == PDFAnnotation.TYPE_FREE_TEXT) {
                val size = runCatching { a.defaultAppearance.size }.getOrDefault(12f)
                a.setDefaultAppearance("Helv", if (size > 0) size else 12f, color.toRgb())
            } else a.color = color.toRgb()
        }
        finish(pg, a)
    }

    fun deleteAnnot(i: Int, index: Int) = op("Delete") {
        val (pg, a) = annotAt(i, index) ?: return@op
        pg.deleteAnnotation(a)
        pg.update()
    }

    /** Removes ink strokes touched by the eraser at (x, y). Returns true if something was erased. */
    fun eraseAt(i: Int, x: Float, y: Float, radius: Float): Boolean {
        val pg = pdfPage(i) ?: return false
        val list = pg.annotations ?: return false
        for (a in list) {
            if (a.type != PDFAnnotation.TYPE_INK) continue
            val b = a.bounds
            if (x < b.x0 - radius || x > b.x1 + radius || y < b.y0 - radius || y > b.y1 + radius) continue
            val ink = a.inkList ?: continue
            val keep = ink.filter { stroke -> stroke.none { p -> Math.hypot((p.x - x).toDouble(), (p.y - y).toDouble()) <= radius } }
            if (keep.size == ink.size) continue
            op("Erase") {
                if (keep.isEmpty()) pg.deleteAnnotation(a) else {
                    a.clearInkList()
                    for (stroke in keep) { a.addInkListStroke(); for (p in stroke) a.addInkListStrokeVertex(p.x, p.y) }
                    a.update()
                }
                pg.update()
            }
            return true
        }
        return false
    }

    fun allAnnots(): List<AnnotInfo> {
        val out = ArrayList<AnnotInfo>()
        if (pdf == null) return out
        for (i in 0 until pageCount) runCatching { out.addAll(annots(i)) }
        return out
    }

    // ---- Forms ----

    fun widgets(i: Int): List<WidgetInfo> {
        val pg = pdfPage(i) ?: return emptyList()
        val list = runCatching { pg.widgets }.getOrNull() ?: return emptyList()
        return list.mapIndexed { idx, w ->
            val kind = when {
                w.isText -> "text"
                w.isCheckbox -> "check"
                w.isRadioButton -> "radio"
                w.isChoice -> "choice"
                w.fieldType == 6 -> "signature"
                else -> "button"
            }
            WidgetInfo(
                i, idx, w.bounds.toRectF(), kind, runCatching { w.value }.getOrNull().orEmpty(),
                runCatching { w.options?.toList() }.getOrNull() ?: emptyList(),
                runCatching { w.label }.getOrNull().orEmpty(), runCatching { w.isReadOnly }.getOrDefault(false),
                runCatching { w.isMultiline }.getOrDefault(false), kind == "signature" && runCatching { w.isSigned }.getOrDefault(false),
            )
        }
    }

    private fun widgetAt(i: Int, index: Int): Pair<PDFPage, PDFWidget>? {
        val pg = pdfPage(i) ?: return null
        val w = pg.widgets?.getOrNull(index) ?: return null
        return pg to w
    }

    fun setWidgetText(i: Int, index: Int, value: String) = op("Fill field") {
        val (pg, w) = widgetAt(i, index) ?: return@op
        w.setTextValue(value); w.update(); pg.update()
        runCatching { it.calculate() }
    }

    fun toggleWidget(i: Int, index: Int) = op("Tick") {
        val (pg, w) = widgetAt(i, index) ?: return@op
        w.toggle(); w.update(); pg.update()
        // radio groups change their siblings too
        pg.widgets?.forEach { runCatching { it.update() } }
    }

    fun setWidgetChoice(i: Int, index: Int, value: String) = op("Choose") {
        val (pg, w) = widgetAt(i, index) ?: return@op
        w.setChoiceValue(value); w.update(); pg.update()
    }

    fun rawWidget(i: Int, index: Int): PDFWidget? = widgetAt(i, index)?.second

    // ---- History and saving ----

    fun undo() { runCatching { pdf?.undo() }; dropCaches() }
    fun redo() { runCatching { pdf?.redo() }; dropCaches() }

    /** Called after an operation that changes page count or geometry. */
    fun structureChanged() { dropCaches(); initSizes() }

    /** Writes changes back to the file the document was opened from. */
    fun save() {
        val p = pdf ?: return
        if (!p.hasUnsavedChanges()) return
        if (p.canBeSavedIncrementally()) {
            p.save(path, "incremental")
        } else {
            saveAs(path)
        }
    }

    /** Saves a clean full copy to [target]. If [target] is this document's own file it is replaced safely and reopened. */
    fun saveAs(target: String, options: String = "garbage=2,compress") {
        val p = pdf ?: return
        val same = File(target).absolutePath == File(path).absolutePath
        if (!same) { p.save(target, options); return }
        val tmp = File("$target.saving")
        p.save(tmp.absolutePath, options)
        dropCaches()
        runCatching { doc.destroy() }
        val dest = File(target)
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        reopen()
    }

    private fun reopen() {
        doc = Document.openDocument(path)
        if (doc.needsPassword()) password?.let { doc.authenticatePassword(it) }
        pdf = if (doc.isPDF) doc.asPDF() else null
        initSizes()
        runCatching { pdf?.enableJournal() }
    }

    companion object {
        fun open(path: String, password: String? = null): PdfDoc {
            val d = Document.openDocument(path)
            if (d.needsPassword()) {
                if (password == null || !d.authenticatePassword(password)) {
                    d.destroy()
                    throw NeedsPassword()
                }
            }
            return PdfDoc(path, d, password)
        }
    }
}
