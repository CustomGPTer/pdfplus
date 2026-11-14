package uk.jj.pdfplus.reader

import android.graphics.Color
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artifex.mupdf.fitz.PDFAnnotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.pdfplus.App
import uk.jj.pdfplus.Mu
import uk.jj.pdfplus.data.Library
import uk.jj.pdfplus.pdf.AnnotInfo
import uk.jj.pdfplus.pdf.Editor
import uk.jj.pdfplus.pdf.ImageHit
import uk.jj.pdfplus.pdf.LinkInfo
import uk.jj.pdfplus.pdf.NeedsPassword
import uk.jj.pdfplus.pdf.PdfDoc
import uk.jj.pdfplus.pdf.TextLineInfo
import uk.jj.pdfplus.pdf.WidgetInfo
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

enum class RTool(val label: String, val view: ViewTool) {
    NONE("", ViewTool.NONE),
    HIGHLIGHT("Highlight", ViewTool.MARKUP), UNDERLINE("Underline", ViewTool.MARKUP), STRIKE("Strikeout", ViewTool.MARKUP), SQUIGGLE("Squiggle", ViewTool.MARKUP),
    PEN("Pen", ViewTool.INK), MARKER("Marker", ViewTool.INK), ERASER("Eraser", ViewTool.ERASER),
    TEXT("Text box", ViewTool.DRAG_RECT), CALLOUT("Callout", ViewTool.DRAG_LINE), NOTE("Note", ViewTool.TAP),
    RECT("Rectangle", ViewTool.DRAG_RECT), ELLIPSE("Ellipse", ViewTool.DRAG_RECT), CLOUD("Cloud", ViewTool.DRAG_RECT),
    LINE("Line", ViewTool.DRAG_LINE), ARROW("Arrow", ViewTool.DRAG_LINE),
    STAMP("Stamp", ViewTool.TAP), IMAGE("Picture", ViewTool.TAP), SIGN("Signature", ViewTool.TAP), TICK("Tick", ViewTool.TAP), CROSS("Cross", ViewTool.TAP), DATE("Date", ViewTool.TAP),
    REDACT("Redact", ViewTool.DRAG_RECT), REDACT_TEXT("Redact text", ViewTool.MARKUP),
    MEASURE("Length", ViewTool.DRAG_LINE), MEASURE_PATH("Path", ViewTool.POINTS), MEASURE_AREA("Area", ViewTool.POINTS), CALIBRATE("Set scale", ViewTool.DRAG_LINE),
    FIELD_TEXT("Text field", ViewTool.DRAG_RECT), FIELD_CHECK("Tick box", ViewTool.DRAG_RECT), FIELD_SIGN("Signature box", ViewTool.DRAG_RECT), CERT_SIGN("Digital signature", ViewTool.DRAG_RECT),
    EDIT_TEXT("Edit text", ViewTool.TAP), EDIT_IMAGE("Edit picture", ViewTool.TAP),
}

sealed class RDialog {
    class Password(val wrong: Boolean) : RDialog()
    class TextBox(val page: Int, val rect: RectF, val calloutTo: FloatArray?) : RDialog()
    class Note(val page: Int, val x: Float, val y: Float) : RDialog()
    class EditAnnot(val a: AnnotInfo) : RDialog()
    class FieldText(val w: WidgetInfo) : RDialog()
    class FieldChoice(val w: WidgetInfo) : RDialog()
    class SignatureField(val w: WidgetInfo) : RDialog()
    class Calibrate(val points: Float) : RDialog()
    class EditLine(val line: TextLineInfo) : RDialog()
    class Picture(val hit: ImageHit) : RDialog()
    class StampPick(val page: Int, val x: Float, val y: Float) : RDialog()
    class CertSign(val page: Int, val rect: RectF, val existing: Int = -1) : RDialog()
    class Message(val title: String, val text: String) : RDialog()
}

class Selection(val page: Int, val quads: List<FloatArray>, val a: FloatArray, val b: FloatArray)

/** One open document (a tab). Holds the engine handle, the page view and everything the toolbars change. */
class Session(val path: String, private val scope: CoroutineScope) : PdfViewListener {
    val name: String get() = File(path).name
    var doc by mutableStateOf<PdfDoc?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var view: PdfView? = null
    var page by mutableIntStateOf(0)
    var pageCount by mutableIntStateOf(0)
    var tool by mutableStateOf(RTool.NONE)
    var barsVisible by mutableStateOf(true)
    var dialog by mutableStateOf<RDialog?>(null)
    var selection by mutableStateOf<Selection?>(null)
    var selectedAnnot by mutableStateOf<AnnotInfo?>(null)
    var changeTick by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var unsaved by mutableStateOf(false)
    var pointCount by mutableIntStateOf(0)
    var toast by mutableStateOf<String?>(null)
    var viewMode by mutableIntStateOf(App.prefs.viewMode)
    var colourMode by mutableStateOf(App.prefs.colourMode)

    // tool settings
    var markupColor by mutableIntStateOf(0xFFFFD400.toInt())
    var inkColor by mutableIntStateOf(0xFFE11D48.toInt())
    var shapeColor by mutableIntStateOf(0xFFE11D48.toInt())
    var textColor by mutableIntStateOf(0xFF111827.toInt())
    var inkWidth by mutableFloatStateOf(2f)
    var textSize by mutableFloatStateOf(12f)
    var pendingImage: ByteArray? = null
    var pendingImageAspect = 1f
    var pendingLabel = ""

    // measuring
    var unitsPerPoint by mutableFloatStateOf(25.4f / 72f)
    var unit by mutableStateOf("mm")

    // search
    var searchQuery by mutableStateOf("")
    var searchHits by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
    var searchIndex by mutableIntStateOf(-1)
    var searching by mutableStateOf(false)
    private var searchJob: Job? = null
    private var password: String? = null
    private var startPage = 0

    fun open(pw: String? = null, atPage: Int = -1) {
        loading = true; error = null
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val f = File(path)
                App.db.ensure(path, f.name, f.length(), f.lastModified())
                App.db.file(path)
            }
            try {
                val d = withContext(Mu) { PdfDoc.open(path, pw) }
                password = pw
                doc = d; pageCount = d.pageCount
                startPage = (if (atPage >= 0) atPage else file?.lastPage ?: 0).coerceIn(0, maxOf(0, d.pageCount - 1))
                page = startPage
                if (file != null) {
                    if (file.viewMode >= 0) viewMode = file.viewMode
                    if (file.colourMode.isNotEmpty()) colourMode = file.colourMode
                }
                App.prefs.str("scale:$path").split('|').let { if (it.size == 2) { it[0].toFloatOrNull()?.let { v -> unitsPerPoint = v; unit = it[1] } } }
                withContext(Dispatchers.IO) { App.db.markOpened(path); if (file?.pages != d.pageCount) App.db.setPages(path, d.pageCount) }
                Library.changed()
                loading = false; dialog = null
                view?.let { attach(it) }
            } catch (e: NeedsPassword) {
                loading = false; dialog = RDialog.Password(pw != null)
            } catch (e: Throwable) {
                loading = false; error = "This file could not be opened. It may be damaged or not a PDF."
            }
        }
    }

    fun attach(v: PdfView) {
        view = v
        v.listener = this
        v.stylusOnly = App.prefs.stylusOnlyInk
        val d = doc ?: return
        v.mode = viewMode
        applyColours()
        if (v.doc !== d) v.setDocument(d, page)
        applyTool()
    }

    fun applyColours() {
        val v = view ?: return
        when (colourMode) {
            "night" -> { v.setTint(null, null); v.night = true }
            "sepia" -> { v.night = false; v.setTint(0xFFF4ECD8.toInt(), 0xFF3B2F1E.toInt()) }
            "grey" -> { v.night = false; v.setTint(0xFFDCDCDC.toInt(), 0xFF202020.toInt()) }
            "custom" -> { v.night = false; v.setTint(App.prefs.customPaper, App.prefs.customInk) }
            else -> { v.night = false; v.setTint(null, null) }
        }
    }

    fun setViewPrefs(mode: Int = viewMode, colour: String = colourMode) {
        viewMode = mode; colourMode = colour
        view?.mode = mode; applyColours()
        scope.launch(Dispatchers.IO) { App.db.saveViewPrefs(path, colour, mode) }
    }

    fun pickTool(t: RTool) {
        tool = if (tool == t) RTool.NONE else t
        applyTool()
    }

    private fun applyTool() {
        val v = view ?: return
        v.tool = tool.view
        v.previewOval = tool == RTool.ELLIPSE
        v.pointsClosed = tool == RTool.MEASURE_AREA
        v.toolColor = when (tool) { RTool.PEN, RTool.MARKER -> inkColor; RTool.REDACT -> Color.BLACK; else -> shapeColor }
        v.toolWidth = if (tool == RTool.MARKER) inkWidth * 5 else inkWidth
        v.clearSelection(); v.deselectAnnot()
    }

    fun toolSettingsChanged() = applyTool()

    // ---- running things on the document ----

    /** Runs [block] on the reader thread, then repaints [page] (or everything) and refreshes undo/save state. */
    fun edit(page: Int = -1, structure: Boolean = false, block: (PdfDoc) -> Unit) {
        val d = doc ?: return
        scope.launch {
            withContext(Mu) { runCatching { block(d) } }
            afterChange(page, structure)
        }
    }

    suspend fun <T> read(block: (PdfDoc) -> T): T? {
        val d = doc ?: return null
        return withContext(Mu) { runCatching { block(d) }.getOrNull() }
    }

    suspend fun afterChange(page: Int = -1, structure: Boolean = false) {
        val d = doc ?: return
        val st = withContext(Mu) { Triple(d.canUndo, d.canRedo, d.hasChanges) }
        canUndo = st.first; canRedo = st.second; unsaved = st.third
        if (structure) { withContext(Mu) { d.structureChanged() }; pageCount = d.pageCount }
        view?.refresh(page, structure)
        changeTick++
    }

    fun undo() = edit { it.undo() }
    fun redo() = edit { it.redo() }

    fun save(then: () -> Unit = {}) {
        val d = doc ?: return then()
        view?.commitInk()
        scope.launch {
            val ok = withContext(Mu) { runCatching { if (d.hasChanges) d.save(); true }.getOrDefault(false) }
            if (ok) {
                unsaved = false
                withContext(Dispatchers.IO) { val f = File(path); App.db.ensure(path, f.name, f.length(), f.lastModified()) }
                pageCount = d.pageCount
            } else toast = "Could not save to this location. Use Save a copy."
            then()
        }
    }

    fun saveCopy(target: File, flatten: Boolean, done: (Boolean) -> Unit) {
        val d = doc ?: return done(false)
        view?.commitInk()
        scope.launch {
            val ok = withContext(Mu) {
                runCatching {
                    if (flatten) { val tmp = App.workFile("flatten.pdf"); d.saveAs(tmp.absolutePath); uk.jj.pdfplus.pdf.PdfTools.flatten(tmp.absolutePath, target.absolutePath); tmp.delete() }
                    else d.saveAs(target.absolutePath)
                    true
                }.getOrDefault(false)
            }
            if (ok) withContext(Dispatchers.IO) { Library.register(target) }
            done(ok)
        }
    }

    fun close() {
        savePosition()
        val d = doc; doc = null; view?.setDocument(null); view = null
        if (d != null) scope.launch(Mu) { runCatching { d.close() } }
    }

    fun savePosition() {
        val p = page; val z = view?.zoom ?: 1f
        scope.launch(Dispatchers.IO) { App.db.savePosition(path, p, z) }
    }

    fun goTo(p: Int) { view?.scrollToPage(p.coerceIn(0, maxOf(0, pageCount - 1))) }

    // ---- search ----

    fun search(q: String) {
        searchJob?.cancel()
        searchQuery = q
        val d = doc
        if (q.length < 2 || d == null) { searchHits = emptyList(); searchIndex = -1; view?.hits = emptyMap(); view?.currentHit = null; searching = false; return }
        searching = true
        searchJob = scope.launch {
            val all = HashMap<Int, List<List<FloatArray>>>()
            val flat = ArrayList<Pair<Int, Int>>()
            val order = (page until pageCount) + (0 until page)
            var shown = false
            for (i in order) {
                val h = withContext(Mu) { d.search(i, q) }
                if (h.isNotEmpty()) {
                    all[i] = h
                    for (k in h.indices) flat.add(i to k)
                    flat.sortWith(compareBy({ it.first }, { it.second }))
                    searchHits = flat.toList(); view?.hits = HashMap(all)
                    if (!shown) { shown = true; searchIndex = flat.indexOf(i to 0); showHit() }
                }
            }
            searching = false
        }
    }

    fun stepSearch(delta: Int) {
        if (searchHits.isEmpty()) return
        searchIndex = ((searchIndex + delta) % searchHits.size + searchHits.size) % searchHits.size
        showHit()
    }

    private fun showHit() {
        val h = searchHits.getOrNull(searchIndex) ?: return
        view?.currentHit = h
        val q = view?.hits?.get(h.first)?.getOrNull(h.second)?.firstOrNull() ?: return
        view?.scrollToPage(h.first, yPoints = q[1])
    }

    // ---- measuring ----

    fun fmt(points: Float): String = String.format(Locale.UK, if (unit == "m" || unit == "ft") "%.3f %s" else "%.1f %s", points * unitsPerPoint, unit)
    fun fmtArea(sqPoints: Float): String = String.format(Locale.UK, "%.2f %s²", sqPoints * unitsPerPoint * unitsPerPoint, unit)

    fun setScale(upp: Float, u: String) {
        unitsPerPoint = upp; unit = u
        App.prefs.set("scale:$path", "$upp|$u")
    }

    fun finishPoints() {
        val v = view ?: return
        val (p, pts) = v.takePoints()
        if (p < 0 || pts.size < 4) return
        var len = 0f
        var k = 2
        while (k + 1 < pts.size) { len += hypot(pts[k] - pts[k - 2], pts[k + 1] - pts[k - 1]); k += 2 }
        if (tool == RTool.MEASURE_AREA && pts.size >= 6) {
            var area = 0f
            val n = pts.size / 2
            for (i in 0 until n) { val j = (i + 1) % n; area += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1] }
            area = abs(area) / 2f
            len += hypot(pts[0] - pts[pts.size - 2], pts[1] - pts[pts.size - 1])
            val label = "Area ${fmtArea(area)}, perimeter ${fmt(len)}"
            edit(p) { it.addPoly(p, pts, true, shapeColor, 1.5f, label); it.addFreeText(p, labelRect(pts), label, 9f, shapeColor) }
        } else {
            val label = "Length ${fmt(len)}"
            edit(p) { it.addPoly(p, pts, false, shapeColor, 1.5f, label); it.addFreeText(p, labelRect(pts), label, 9f, shapeColor) }
        }
    }

    private fun labelRect(pts: FloatArray): RectF {
        val x = pts[pts.size - 2]; val y = pts[pts.size - 1]
        return RectF(x + 4f, y - 14f, x + 190f, y + 2f)
    }

    // ---- view callbacks ----

    override fun onTap() { barsVisible = !barsVisible }
    override fun onPageChanged(page: Int) { this.page = page }

    override fun onSelection(page: Int, quads: List<FloatArray>, a: FloatArray?, b: FloatArray?) {
        selection = if (page >= 0 && quads.isNotEmpty() && a != null && b != null) Selection(page, quads, a, b) else null
    }

    fun markupSelection(type: Int) {
        val s = selection ?: return
        val colour = markupColor
        view?.clearSelection()
        if (type == PDFAnnotation.TYPE_REDACT) edit(s.page) { it.addRedactMark(s.page, null, s.quads) }
        else edit(s.page) { it.addMarkup(s.page, type, s.quads, if (type == PDFAnnotation.TYPE_HIGHLIGHT) colour else shapeColor, 1f) }
    }

    suspend fun selectionText(): String {
        val s = selection ?: return ""
        return read { it.selectionText(s.page, s.a[0], s.a[1], s.b[0], s.b[1]) }.orEmpty()
    }

    override fun onMarkup(page: Int, quads: List<FloatArray>) {
        when (tool) {
            RTool.HIGHLIGHT -> edit(page) { it.addMarkup(page, PDFAnnotation.TYPE_HIGHLIGHT, quads, markupColor, 1f) }
            RTool.UNDERLINE -> edit(page) { it.addMarkup(page, PDFAnnotation.TYPE_UNDERLINE, quads, shapeColor, 1f) }
            RTool.STRIKE -> edit(page) { it.addMarkup(page, PDFAnnotation.TYPE_STRIKE_OUT, quads, shapeColor, 1f) }
            RTool.SQUIGGLE -> edit(page) { it.addMarkup(page, PDFAnnotation.TYPE_SQUIGGLY, quads, shapeColor, 1f) }
            RTool.REDACT_TEXT -> edit(page) { it.addRedactMark(page, null, quads) }
            else -> {}
        }
    }

    override fun onInk(page: Int, strokes: List<FloatArray>, pressure: Float) {
        val marker = tool == RTool.MARKER
        val w = (if (marker) inkWidth * 5 else inkWidth) * (0.6f + pressure.coerceIn(0f, 1.5f) * 0.8f).let { if (pressure >= 0.99f) 1f else it }
        val c = inkColor
        edit(page) { it.addInk(page, strokes, c, w, if (marker) 0.4f else 1f) }
    }

    override fun onErase(page: Int, x: Float, y: Float, radius: Float) {
        val d = doc ?: return
        scope.launch {
            val hit = withContext(Mu) { runCatching { d.eraseAt(page, x, y, radius) }.getOrDefault(false) }
            if (hit) afterChange(page)
        }
    }

    override fun onRect(page: Int, rect: RectF) {
        val c = shapeColor; val w = inkWidth
        when (tool) {
            RTool.RECT -> edit(page) { it.addShape(page, PDFAnnotation.TYPE_SQUARE, rect, c, Color.TRANSPARENT, w, 1f) }
            RTool.ELLIPSE -> edit(page) { it.addShape(page, PDFAnnotation.TYPE_CIRCLE, rect, c, Color.TRANSPARENT, w, 1f) }
            RTool.CLOUD -> edit(page) { it.addShape(page, PDFAnnotation.TYPE_SQUARE, rect, c, Color.TRANSPARENT, w, 1f, cloudy = true) }
            RTool.TEXT -> dialog = RDialog.TextBox(page, rect, null)
            RTool.REDACT -> edit(page) { it.addRedactMark(page, rect, null) }
            RTool.FIELD_TEXT -> edit(page) { Editor.addTextField(it, page, rect, rect.height() > 40f) }
            RTool.FIELD_CHECK -> edit(page) { val s = minOf(rect.width(), rect.height()); Editor.addCheckbox(it, page, RectF(rect.left, rect.top, rect.left + s, rect.top + s)) }
            RTool.FIELD_SIGN -> edit(page) { Editor.addSignatureField(it, page, rect) }
            RTool.CERT_SIGN -> dialog = RDialog.CertSign(page, rect)
            else -> {}
        }
    }

    override fun onLine(page: Int, ax: Float, ay: Float, bx: Float, by: Float) {
        val c = shapeColor; val w = inkWidth
        when (tool) {
            RTool.LINE -> edit(page) { it.addLine(page, ax, ay, bx, by, false, c, w) }
            RTool.ARROW -> edit(page) { it.addLine(page, ax, ay, bx, by, true, c, w) }
            RTool.MEASURE -> { val label = fmt(hypot(bx - ax, by - ay)); edit(page) { it.addLine(page, ax, ay, bx, by, false, c, 1.2f, label) } }
            RTool.CALIBRATE -> dialog = RDialog.Calibrate(hypot(bx - ax, by - ay))
            RTool.CALLOUT -> dialog = RDialog.TextBox(page, RectF(bx, by - 18f, bx + 170f, by + 18f), floatArrayOf(ax, ay))
            else -> {}
        }
    }

    override fun onToolTap(page: Int, x: Float, y: Float) {
        when (tool) {
            RTool.NOTE -> dialog = RDialog.Note(page, x, y)
            RTool.STAMP -> dialog = RDialog.StampPick(page, x, y)
            RTool.IMAGE, RTool.SIGN -> {
                val bytes = pendingImage ?: return
                val w = if (tool == RTool.SIGN) 150f else 220f
                val h = w / pendingImageAspect
                val label = pendingLabel
                edit(page) { it.addImageStamp(page, RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), bytes, label) }
                if (tool == RTool.IMAGE) pickTool(RTool.NONE)
            }
            RTool.TICK -> edit(page) { it.addInk(page, listOf(floatArrayOf(x - 6f, y, x - 2f, y + 5f, x + 7f, y - 7f)), textColor, 1.6f, 1f) }
            RTool.CROSS -> edit(page) { it.addInk(page, listOf(floatArrayOf(x - 6f, y - 6f, x + 6f, y + 6f), floatArrayOf(x + 6f, y - 6f, x - 6f, y + 6f)), textColor, 1.6f, 1f) }
            RTool.DATE -> { val t = java.text.SimpleDateFormat("d MMM yyyy", Locale.UK).format(java.util.Date()); edit(page) { it.addFreeText(page, RectF(x, y - 8f, x + 90f, y + 8f), t, textSize, textColor) } }
            RTool.EDIT_TEXT -> scope.launch { read { Editor.lineAt(it, page, x, y) }?.let { dialog = RDialog.EditLine(it) } ?: run { toast = "No text found there. Scanned pages need OCR first." } }
            RTool.EDIT_IMAGE -> scope.launch { read { Editor.imageAt(it, page, x, y, false) }?.let { dialog = RDialog.Picture(it) } ?: run { toast = "No picture found there." } }
            else -> {}
        }
    }

    override fun onPointsChanged(count: Int) { pointCount = count }
    override fun onAnnotSelected(a: AnnotInfo?) { selectedAnnot = a }
    override fun onAnnotMoved(a: AnnotInfo, dx: Float, dy: Float) = edit(a.page) { it.moveAnnot(a.page, a.index, dx, dy) }
    override fun onAnnotResized(a: AnnotInfo, rect: RectF) = edit(a.page) { it.resizeAnnot(a.page, a.index, rect) }

    override fun onWidget(w: WidgetInfo) {
        when (w.kind) {
            "text" -> dialog = RDialog.FieldText(w)
            "check", "radio" -> edit(w.page) { it.toggleWidget(w.page, w.index) }
            "choice" -> dialog = RDialog.FieldChoice(w)
            "signature" -> dialog = RDialog.SignatureField(w)
        }
    }

    var onExternalLink: (String) -> Unit = {}
    override fun onLink(l: LinkInfo) { if (l.page >= 0) goTo(l.page) else if (l.uri.isNotBlank()) onExternalLink(l.uri) }
}

/** The set of open tabs. */
object Reader {
    val sessions = mutableStateListOf<Session>()
    var current by mutableStateOf<Session?>(null)

    fun open(path: String, page: Int = -1) {
        val existing = sessions.firstOrNull { it.path == path }
        if (existing != null) { current = existing; if (page >= 0) existing.goTo(page); return }
        if (sessions.size >= 6) closeTab(sessions.first())
        val s = Session(path, App.instance.scope)
        sessions.add(s); current = s
        s.open(atPage = page)
    }

    fun closeTab(s: Session) {
        val finish = {
            s.close(); sessions.remove(s)
            if (current === s) current = sessions.lastOrNull()
        }
        if (s.unsaved && App.prefs.autoSave) s.save { finish() } else finish()
    }

    /** Leave the reader but keep tabs open. */
    fun hide() { current?.let { if (it.unsaved && App.prefs.autoSave) it.save(); it.savePosition() }; current = null }

    fun saveAll() { for (s in sessions) { if (s.unsaved && App.prefs.autoSave) s.save(); s.savePosition() } }
}
