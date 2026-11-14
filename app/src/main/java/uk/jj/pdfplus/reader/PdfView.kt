package uk.jj.pdfplus.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.pdfplus.Mu
import uk.jj.pdfplus.pdf.AnnotInfo
import uk.jj.pdfplus.pdf.LinkInfo
import uk.jj.pdfplus.pdf.PdfDoc
import uk.jj.pdfplus.pdf.WidgetInfo
import uk.jj.pdfplus.pdf.quadBounds
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ViewTool { NONE, MARKUP, INK, ERASER, DRAG_RECT, DRAG_LINE, POINTS, TAP }

class PageInfo(val annots: List<AnnotInfo>, val widgets: List<WidgetInfo>, val links: List<LinkInfo>)

interface PdfViewListener {
    fun onTap() {}
    fun onPageChanged(page: Int) {}
    fun onSelection(page: Int, quads: List<FloatArray>, a: FloatArray?, b: FloatArray?) {}
    fun onMarkup(page: Int, quads: List<FloatArray>) {}
    fun onInk(page: Int, strokes: List<FloatArray>, pressure: Float) {}
    fun onErase(page: Int, x: Float, y: Float, radius: Float) {}
    fun onRect(page: Int, rect: RectF) {}
    fun onLine(page: Int, ax: Float, ay: Float, bx: Float, by: Float) {}
    fun onToolTap(page: Int, x: Float, y: Float) {}
    fun onPointsChanged(count: Int) {}
    fun onAnnotSelected(a: AnnotInfo?) {}
    fun onAnnotMoved(a: AnnotInfo, dx: Float, dy: Float) {}
    fun onAnnotResized(a: AnnotInfo, rect: RectF) {}
    fun onWidget(w: WidgetInfo) {}
    fun onLink(l: LinkInfo) {}
}

/**
 * The page surface: lays pages out, draws cached page bitmaps, sharpens the visible region after a zoom,
 * and turns touches into scrolling, selection, drawing or taps depending on the active tool.
 * All coordinates passed to the listener are page coordinates in points.
 */
@SuppressLint("ViewConstructor")
class PdfView(context: Context, private val scope: CoroutineScope) : View(context) {

    var listener: PdfViewListener = object : PdfViewListener {}
    var doc: PdfDoc? = null
        private set

    // 0 = continuous scroll, 1 = one page at a time, 2 = two-page spread
    var mode = 0
        set(v) { if (field != v) { field = v; relayout(true) } }
    var night = false
        set(v) { if (field != v) { field = v; clearBitmaps(); invalidate() } }
    private var tintFilter: ColorMatrixColorFilter? = null
    private var paperColor = Color.WHITE
    var tool = ViewTool.NONE
        set(v) { commitInk(); field = v; points.clear(); dragRect = null; lineA = null; invalidate() }
    var previewOval = false
    var toolColor = Color.RED
    var toolWidth = 2f
    var stylusOnly = true
    var formHighlight = true
    var maxZoom = 16f

    private val density = resources.displayMetrics.density
    private val gap = 8f * density
    private var rects: Array<RectF> = emptyArray()
    private var contentW = 0f
    private var contentH = 0f
    var zoom = 1f
        private set
    private var sx = 0f
    private var sy = 0f
    var currentPage = 0
        private set

    private var generation = 0
    private val baseCache = object : LruCache<Int, Bitmap>(72 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Int>()
    private class Patch(val page: Int, val zoom: Float, val x: Int, val y: Int, val bmp: Bitmap)
    private var patches: List<Patch> = emptyList()
    private var patchJob: Job? = null
    private val infos = HashMap<Int, PageInfo>()
    private val infoPending = HashSet<Int>()
    @Volatile private var firstVisible = 0
    @Volatile private var lastVisible = 0

    // overlays
    var hits: Map<Int, List<List<FloatArray>>> = emptyMap()
        set(v) { field = v; invalidate() }
    var currentHit: Pair<Int, Int>? = null
        set(v) { field = v; invalidate() }
    var speakRect: Pair<Int, RectF>? = null
        set(v) { field = v; invalidate() }
    private var selPage = -1
    private var selA: FloatArray? = null
    private var selB: FloatArray? = null
    private var selQuads: List<FloatArray> = emptyList()
    private var selJob: Job? = null
    var selectedAnnot: AnnotInfo? = null
        private set
    private var annotDrag: RectF? = null
    private val strokes = ArrayList<ArrayList<Float>>()
    private var strokePage = -1
    private var pressureSum = 0f
    private var pressureN = 0
    private var inkJob: Job? = null
    private var stylusSeen = false
    private var penDown = false
    private var dragRect: RectF? = null
    private var dragPage = -1
    private var lineA: PointF? = null
    private var lineB: PointF? = null
    val points = ArrayList<Float>()
    var pointsPage = -1
        private set
    var pointsClosed = false

    private enum class Drag { NONE, SEL_A, SEL_B, ANNOT_MOVE, ANNOT_RESIZE, TOOL, THUMB }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val hitPaint = Paint().apply { color = 0x55FFD400 }
    private val curHitPaint = Paint().apply { color = 0x77FF7A00 }
    private val selPaint = Paint().apply { color = 0x553B82F6 }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
    private val speakPaint = Paint().apply { color = 0x3322C55E }
    private val fieldPaint = Paint().apply { color = 0x223B82F6 }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xFF2563EB.toInt() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99666666.toInt() }
    private val path = Path()
    private var thumbUntil = 0L

    private val scroller = OverScroller(context)

    fun setTint(paper: Int?, ink: Int?) {
        if (paper == null || ink == null) { tintFilter = null; paperColor = Color.WHITE } else {
            val m = FloatArray(20)
            val pr = Color.red(paper); val pg = Color.green(paper); val pb = Color.blue(paper)
            val ir = Color.red(ink); val ig = Color.green(ink); val ib = Color.blue(ink)
            m[0] = (pr - ir) / 255f; m[4] = ir.toFloat()
            m[6] = (pg - ig) / 255f; m[9] = ig.toFloat()
            m[12] = (pb - ib) / 255f; m[14] = ib.toFloat()
            m[18] = 1f
            tintFilter = ColorMatrixColorFilter(ColorMatrix(m)); paperColor = paper
        }
        bmpPaint.colorFilter = tintFilter
        pagePaint.color = if (night) Color.BLACK else paperColor
        invalidate()
    }

    fun setDocument(d: PdfDoc?, page: Int = 0) {
        doc = d
        clearAll()
        relayout(false)
        scrollToPage(page, animate = false)
        measureInBackground()
    }

    private var measureJob: Job? = null

    private fun measureInBackground() {
        measureJob?.cancel()
        val d = doc ?: return
        measureJob = scope.launch {
            while (doc === d) {
                val (changed, done) = withContext(Mu) { runCatching { d.measureMore(currentPage, 60) to d.allMeasured }.getOrDefault(false to true) }
                if (doc !== d) break
                if (changed) relayout(true)
                if (done) break
                delay(20)
            }
        }
    }

    private fun clearBitmaps() { generation++; baseCache.evictAll(); pending.clear(); patches = emptyList(); patchJob?.cancel() }
    private fun clearAll() { clearBitmaps(); infos.clear(); infoPending.clear(); clearSelection(); selectedAnnot = null; strokes.clear(); points.clear() }

    /** Call after the document's pages or annotations changed. [structure] = pages were added, removed or resized. */
    fun refresh(page: Int = -1, structure: Boolean = false) {
        if (structure) { clearAll(); relayout(true); measureInBackground(); return }
        generation++
        pending.clear(); patches = emptyList()
        if (page >= 0) { baseCache.remove(page); infos.remove(page) } else { baseCache.evictAll(); infos.clear() }
        infoPending.clear()
        selectedAnnot?.let { sel -> if (page < 0 || sel.page == page) { selectedAnnot = null; listener.onAnnotSelected(null) } }
        invalidate()
        schedulePatch()
    }

    // ---------- Layout ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) { clearBitmaps(); relayout(true) }
    }

    private fun relayout(keepPlace: Boolean) {
        val d = doc
        val w = width.toFloat(); val h = height.toFloat()
        if (d == null || w <= 0 || h <= 0) { rects = emptyArray(); return }
        val anchor = if (keepPlace && rects.isNotEmpty() && currentPage < rects.size) {
            val r = rects[currentPage]
            (sy / zoom - r.top) / max(1f, r.height())
        } else 0f
        val n = d.pageCount
        val out = Array(n) { RectF() }
        when (mode) {
            1 -> {
                for (i in 0 until n) {
                    val s = d.sizes[i]
                    val k = min(w / s[0], h / s[1])
                    val pw = s[0] * k; val ph = s[1] * k
                    out[i].set(i * w + (w - pw) / 2, (h - ph) / 2, i * w + (w + pw) / 2, (h + ph) / 2)
                }
                contentW = n * w; contentH = h
            }
            2 -> {
                val cw = (w - gap) / 2
                var y = gap
                var i = 0
                while (i < n) {
                    val a = d.sizes[i]; val ha = cw * a[1] / a[0]
                    out[i].set(0f, y, cw, y + ha)
                    var rowH = ha
                    if (i + 1 < n) { val b = d.sizes[i + 1]; val hb = cw * b[1] / b[0]; out[i + 1].set(cw + gap, y, w, y + hb); rowH = max(ha, hb) }
                    y += rowH + gap; i += 2
                }
                contentW = w; contentH = y
            }
            else -> {
                var y = gap
                for (i in 0 until n) { val s = d.sizes[i]; val ph = w * s[1] / s[0]; out[i].set(0f, y, w, y + ph); y += ph + gap }
                contentW = w; contentH = y
            }
        }
        rects = out
        clearBitmaps()
        if (keepPlace && currentPage < n) {
            val r = out[currentPage]
            if (mode == 1) sx = currentPage * w * zoom else sy = (r.top + anchor * r.height()) * zoom
        }
        clampScroll(); invalidate(); schedulePatch()
    }

    private fun clampScroll() {
        sx = sx.coerceIn(0f, max(0f, contentW * zoom - width))
        sy = sy.coerceIn(0f, max(0f, contentH * zoom - height))
    }

    private fun offX() = if (contentW * zoom < width) (width - contentW * zoom) / 2f else -sx
    private fun offY() = if (contentH * zoom < height) (height - contentH * zoom) / 2f else -sy
    private fun pageScale(i: Int) = rects[i].width() / (doc?.sizes?.get(i)?.get(0) ?: 1f)

    fun pageToScreen(i: Int, px: Float, py: Float): PointF {
        val s = doc!!.sizes[i]; val k = pageScale(i); val r = rects[i]
        return PointF((r.left + (px - s[2]) * k) * zoom + offX(), (r.top + (py - s[3]) * k) * zoom + offY())
    }

    /** Screen point to (page, x, y) in page points; picks the nearest page if the point is in a gap. */
    fun screenToPage(x: Float, y: Float, forcePage: Int = -1): FloatArray? {
        if (rects.isEmpty()) return null
        val bx = (x - offX()) / zoom; val by = (y - offY()) / zoom
        var best = forcePage
        if (best < 0) {
            var bestD = Float.MAX_VALUE
            for (i in firstVisible..min(lastVisible, rects.size - 1)) {
                val r = rects[i]
                val dx = max(0f, max(r.left - bx, bx - r.right)); val dy = max(0f, max(r.top - by, by - r.bottom))
                val dd = dx + dy
                if (dd < bestD) { bestD = dd; best = i }
            }
        }
        if (best < 0 || best >= rects.size) return null
        val s = doc!!.sizes[best]; val k = pageScale(best); val r = rects[best]
        return floatArrayOf(best.toFloat(), (bx - r.left) / k + s[2], (by - r.top) / k + s[3])
    }

    fun scrollToPage(page: Int, animate: Boolean = false, yPoints: Float = -1f) {
        if (rects.isEmpty()) { currentPage = max(0, page); return }
        val p = page.coerceIn(0, rects.size - 1)
        scroller.forceFinished(true)
        val r = rects[p]
        if (mode == 1) { sx = p * width * zoom; sy = 0f } else {
            val extra = if (yPoints >= 0) (yPoints - doc!!.sizes[p][3]) * pageScale(p) - height / (3f * zoom) else -gap / 2
            sy = (r.top + max(-gap / 2, extra)) * zoom
            if (zoom <= 1.01f) sx = 0f
        }
        clampScroll(); updateVisible(); invalidate(); schedulePatch()
    }

    fun showRect(page: Int, rect: RectF) = scrollToPage(page, yPoints = rect.top)

    private fun updateVisible() {
        if (rects.isEmpty()) return
        val top = (0 - offY()) / zoom; val bottom = (height - offY()) / zoom
        val left = (0 - offX()) / zoom; val right = (width - offX()) / zoom
        var first = -1; var last = -1; var best = -1; var bestArea = -1f
        // binary search start for long documents
        var lo = 0; var hi = rects.size - 1
        if (mode != 1) { while (lo < hi) { val mid = (lo + hi) / 2; if (rects[mid].bottom < top) lo = mid + 1 else hi = mid }; lo = max(0, lo - 1) } else lo = max(0, (left / max(1f, width.toFloat())).toInt() - 1)
        for (i in lo until rects.size) {
            val r = rects[i]
            if (mode != 1 && r.top > bottom) break
            if (mode == 1 && r.left > right) break
            val w = min(r.right, right) - max(r.left, left); val h = min(r.bottom, bottom) - max(r.top, top)
            if (w > 0 && h > 0) {
                if (first < 0) first = i
                last = i
                if (w * h > bestArea) { bestArea = w * h; best = i }
            }
        }
        if (first >= 0) { firstVisible = first; lastVisible = last }
        if (best >= 0 && best != currentPage) { currentPage = best; listener.onPageChanged(best) }
    }

    // ---------- Drawing ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(if (night) 0xFF0B0B0C.toInt() else 0xFFDADCE0.toInt())
        val d = doc ?: return
        if (rects.isEmpty()) return
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat(); sy = scroller.currY.toFloat(); clampScroll()
            postInvalidateOnAnimation()
            if (scroller.isFinished) { snapPage(); schedulePatch() }
        }
        updateVisible()
        val ox = offX(); val oy = offY()
        pagePaint.color = if (night) Color.BLACK else paperColor
        val dst = RectF()
        for (i in max(0, firstVisible - 1)..min(rects.size - 1, lastVisible + 1)) {
            val r = rects[i]
            dst.set(r.left * zoom + ox, r.top * zoom + oy, r.right * zoom + ox, r.bottom * zoom + oy)
            c.drawRect(dst, pagePaint)
            val b = baseCache.get(i)
            if (b != null) c.drawBitmap(b, null, dst, bmpPaint) else requestBase(i)
            if (i in firstVisible..lastVisible) {
                for (p in patches) if (p.page == i && abs(p.zoom - zoom) < 0.001f) {
                    c.drawBitmap(p.bmp, dst.left + p.x, dst.top + p.y, bmpPaint)
                }
                if (!infos.containsKey(i)) requestInfo(i)
                drawOverlays(c, d, i)
            }
        }
        drawTool(c)
        if (mode != 1 && contentH * zoom > height * 2 && System.currentTimeMillis() < thumbUntil) {
            c.drawRoundRect(thumbRect(), 6 * density, 6 * density, thumbPaint)
            postInvalidateDelayed(300)
        }
    }

    private fun thumbRect(): RectF {
        val th = 48 * density
        val frac = sy / max(1f, contentH * zoom - height)
        val top = frac * (height - th)
        return RectF(width - 10 * density, top, width - 3 * density, top + th)
    }

    private fun quadPath(i: Int, q: FloatArray): Path {
        path.rewind()
        val a = pageToScreen(i, q[0], q[1]); val b = pageToScreen(i, q[2], q[3])
        val cc = pageToScreen(i, q[6], q[7]); val dd = pageToScreen(i, q[4], q[5])
        path.moveTo(a.x, a.y); path.lineTo(b.x, b.y); path.lineTo(cc.x, cc.y); path.lineTo(dd.x, dd.y); path.close()
        return path
    }

    private fun screenRect(i: Int, r: RectF): RectF {
        val a = pageToScreen(i, r.left, r.top); val b = pageToScreen(i, r.right, r.bottom)
        return RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    private fun drawOverlays(c: Canvas, d: PdfDoc, i: Int) {
        if (formHighlight) infos[i]?.widgets?.forEach { w -> if (!w.readOnly) c.drawRect(screenRect(i, w.rect), fieldPaint) }
        hits[i]?.forEachIndexed { n, hit ->
            val p = if (currentHit?.first == i && currentHit?.second == n) curHitPaint else hitPaint
            for (q in hit) c.drawPath(quadPath(i, q), p)
        }
        speakRect?.let { if (it.first == i) c.drawRect(screenRect(i, it.second), speakPaint) }
        if (selPage == i) {
            for (q in selQuads) c.drawPath(quadPath(i, q), selPaint)
            if (selQuads.isNotEmpty() && tool == ViewTool.NONE) {
                val f = selQuads.first(); val l = selQuads.last()
                val a = pageToScreen(i, f[4], f[5]); val b = pageToScreen(i, l[6], l[7])
                c.drawCircle(a.x, a.y + 9 * density, 9 * density, handlePaint)
                c.drawCircle(b.x, b.y + 9 * density, 9 * density, handlePaint)
            }
        }
        selectedAnnot?.let { a ->
            if (a.page == i) {
                val r = screenRect(i, annotDrag ?: a.rect)
                r.inset(-3 * density, -3 * density)
                c.drawRect(r, boxPaint)
                if (a.resizable) for (p in corners(r)) c.drawCircle(p.x, p.y, 7 * density, handlePaint)
            }
        }
    }

    private fun corners(r: RectF) = arrayOf(PointF(r.left, r.top), PointF(r.right, r.top), PointF(r.left, r.bottom), PointF(r.right, r.bottom))

    private fun drawTool(c: Canvas) {
        strokePaint.color = toolColor
        if (strokes.isNotEmpty() && strokePage >= 0 && strokePage < rects.size) {
            strokePaint.strokeWidth = max(1f, toolWidth * pageScale(strokePage) * zoom)
            for (s in strokes) {
                path.rewind()
                var k = 0
                while (k + 1 < s.size) { val p = pageToScreen(strokePage, s[k], s[k + 1]); if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y); k += 2 }
                c.drawPath(path, strokePaint)
            }
        }
        strokePaint.strokeWidth = 2f * density
        dragRect?.let { r ->
            if (dragPage in rects.indices) {
                val sr = screenRect(dragPage, RectF(min(r.left, r.right), min(r.top, r.bottom), max(r.left, r.right), max(r.top, r.bottom)))
                if (previewOval) c.drawOval(sr, strokePaint) else c.drawRect(sr, strokePaint)
            }
        }
        val a = lineA; val b = lineB
        if (a != null && b != null && dragPage in rects.indices) {
            val p = pageToScreen(dragPage, a.x, a.y); val q = pageToScreen(dragPage, b.x, b.y)
            c.drawLine(p.x, p.y, q.x, q.y, strokePaint)
        }
        if (points.size >= 2 && pointsPage in rects.indices) {
            path.rewind()
            var k = 0
            while (k + 1 < points.size) {
                val p = pageToScreen(pointsPage, points[k], points[k + 1])
                if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                c.drawCircle(p.x, p.y, 4 * density, handlePaint); k += 2
            }
            if (pointsClosed && points.size >= 6) path.close()
            c.drawPath(path, strokePaint)
        }
    }

    // ---------- Background work ----------

    private fun requestBase(i: Int) {
        val d = doc ?: return
        if (!pending.add(i)) return
        val gen = generation
        val w = rects[i].width().toInt(); val h = rects[i].height().toInt()
        val n = night
        scope.launch {
            var resized = false
            val bmp = withContext(Mu) {
                if (gen != generation || i < firstVisible - 2 || i > lastVisible + 2) null
                else runCatching {
                    // the layout may have assumed this page's size; check before drawing it
                    if (d.measureMore(i, 1)) { resized = true; null } else d.render(i, w / d.sizes[i][0], 0, 0, w, h, n)
                }.getOrNull()
            }
            if (resized && doc === d) { relayout(true); return@launch }
            if (gen == generation) { pending.remove(i); if (bmp != null) { baseCache.put(i, bmp); invalidate() } }
        }
    }

    private fun requestInfo(i: Int) {
        val d = doc ?: return
        if (!infoPending.add(i)) return
        val gen = generation
        scope.launch {
            val info = withContext(Mu) { runCatching { PageInfo(d.annots(i), d.widgets(i), d.links(i)) }.getOrNull() }
            if (gen == generation && info != null) { infos[i] = info; invalidate() }
            infoPending.remove(i)
        }
    }

    /** After zooming or scrolling settles, re-render just the visible region at full sharpness. */
    private fun schedulePatch() {
        patchJob?.cancel()
        val d = doc ?: return
        if (zoom <= 1.02f) { if (patches.isNotEmpty()) { patches = emptyList(); invalidate() }; return }
        val gen = generation
        patchJob = scope.launch {
            delay(140)
            val z = zoom; val ox = offX(); val oy = offY(); val n = night
            val out = ArrayList<Patch>()
            for (i in firstVisible..min(lastVisible, rects.size - 1)) {
                val r = rects[i]
                val left = r.left * z + ox; val top = r.top * z + oy
                val x0 = max(0f, -left).toInt(); val y0 = max(0f, -top).toInt()
                val x1 = min(r.width() * z, width - left).toInt(); val y1 = min(r.height() * z, height - top).toInt()
                if (x1 - x0 < 8 || y1 - y0 < 8) continue
                val k = pageScale(i) * z
                val bmp = withContext(Mu) { if (gen != generation) null else runCatching { d.render(i, k, x0, y0, x1 - x0, y1 - y0, n) }.getOrNull() } ?: continue
                out.add(Patch(i, z, x0, y0, bmp))
            }
            if (gen == generation && abs(z - zoom) < 0.001f) { patches = out; invalidate() }
        }
    }

    private fun snapPage() {
        if (mode != 1 || zoom > 1.02f || width == 0) return
        val target = Math.round(sx / width) * width
        if (abs(target - sx) > 1f) { scroller.startScroll(sx.toInt(), sy.toInt(), (target - sx).toInt(), 0, 220); postInvalidateOnAnimation() }
    }

    // ---------- Selection ----------

    fun clearSelection() {
        if (selPage >= 0) { selPage = -1; selA = null; selB = null; selQuads = emptyList(); listener.onSelection(-1, emptyList(), null, null); invalidate() }
    }

    fun deselectAnnot() { if (selectedAnnot != null) { selectedAnnot = null; annotDrag = null; listener.onAnnotSelected(null); invalidate() } }

    private fun updateSelection(notify: Boolean) {
        val d = doc ?: return
        val p = selPage; val a = selA ?: return; val b = selB ?: return
        selJob?.cancel()
        selJob = scope.launch {
            val q = withContext(Mu) { d.selectionQuads(p, a[0], a[1], b[0], b[1]) }
            if (selPage == p) { selQuads = q; invalidate(); if (notify) listener.onSelection(p, q, a, b) }
        }
    }

    private fun startSelectionAt(x: Float, y: Float) {
        val d = doc ?: return
        val pt = screenToPage(x, y) ?: return
        val p = pt[0].toInt()
        scope.launch {
            val w = withContext(Mu) { d.wordAt(p, pt[1], pt[2]) } ?: return@launch
            selPage = p; selA = floatArrayOf(w[0], w[1]); selB = floatArrayOf(w[2], w[3])
            drag = Drag.SEL_B
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            updateSelection(true)
        }
    }

    // ---------- Touch ----------

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val old = zoom
            val nz = (zoom * det.scaleFactor).coerceIn(if (mode == 0) 1f else 0.6f, maxZoom)
            if (nz == old) return true
            val fx = det.focusX; val fy = det.focusY
            val bx = (fx - offX()) / old; val by = (fy - offY()) / old
            zoom = nz
            sx = bx * nz - fx; sy = by * nz - fy
            clampScroll(); invalidate()
            return true
        }
        override fun onScaleBegin(det: ScaleGestureDetector): Boolean { scroller.forceFinished(true); cancelStroke(); return true }
        override fun onScaleEnd(det: ScaleGestureDetector) { schedulePatch() }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            sx += dx; sy += dy; clampScroll(); thumbUntil = System.currentTimeMillis() + 1500; invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (mode == 1 && zoom <= 1.02f) {
                val page = (Math.round(sx / max(1, width)) + if (vx < -600) 1 else if (vx > 600) -1 else 0).coerceIn(0, max(0, rects.size - 1))
                scroller.startScroll(sx.toInt(), sy.toInt(), (page * width - sx).toInt(), 0, 260)
            } else scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, max(0f, contentW * zoom - width).toInt(), 0, max(0f, contentH * zoom - height).toInt())
            thumbUntil = System.currentTimeMillis() + 2500
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool != ViewTool.NONE) return false
            val target = if (zoom > 1.05f) 1f else 2.5f
            val bx = (e.x - offX()) / zoom; val by = (e.y - offY()) / zoom
            zoom = target; sx = bx * target - e.x; sy = by * target - e.y
            clampScroll(); invalidate(); schedulePatch(); return true
        }
        override fun onLongPress(e: MotionEvent) { if (tool == ViewTool.NONE && drag == Drag.NONE) startSelectionAt(e.x, e.y) }
    })

    private fun handleTap(x: Float, y: Float) {
        val pt = screenToPage(x, y) ?: return
        val p = pt[0].toInt()
        if (tool == ViewTool.TAP) { listener.onToolTap(p, pt[1], pt[2]); return }
        if (tool == ViewTool.POINTS) {
            if (pointsPage != p) { points.clear(); pointsPage = p }
            points.add(pt[1]); points.add(pt[2]); listener.onPointsChanged(points.size / 2); invalidate(); return
        }
        if (tool != ViewTool.NONE) return
        if (selPage >= 0) { clearSelection(); return }
        val info = infos[p]
        val slop = 6f / (pageScale(p) * zoom) * density
        info?.widgets?.firstOrNull { !it.readOnly && it.rect.contains(pt[1], pt[2]) }?.let { deselectAnnot(); listener.onWidget(it); return }
        info?.annots?.lastOrNull { RectF(it.rect).apply { inset(-slop, -slop) }.contains(pt[1], pt[2]) }?.let {
            selectedAnnot = it; annotDrag = null; listener.onAnnotSelected(it); invalidate(); return
        }
        if (selectedAnnot != null) { deselectAnnot(); return }
        info?.links?.firstOrNull { it.rect.contains(pt[1], pt[2]) }?.let { listener.onLink(it); return }
        listener.onTap()
    }

    private fun cancelStroke() {
        if (drag == Drag.TOOL) {
            if (tool == ViewTool.INK && strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
            dragRect = null; lineA = null; lineB = null; drag = Drag.NONE; invalidate()
        }
    }

    fun commitInk() {
        inkJob?.cancel()
        if (strokes.isNotEmpty() && strokePage >= 0) {
            val out = strokes.filter { it.size >= 2 }.map { s -> if (s.size == 2) floatArrayOf(s[0], s[1], s[0] + 0.1f, s[1] + 0.1f) else s.toFloatArray() }
            val pr = if (pressureN > 0) pressureSum / pressureN else 1f
            strokes.clear(); pressureSum = 0f; pressureN = 0
            if (out.isNotEmpty()) listener.onInk(strokePage, out, pr)
        }
    }

    fun takePoints(): Pair<Int, FloatArray> { val r = pointsPage to points.toFloatArray(); points.clear(); listener.onPointsChanged(0); invalidate(); return r }
    fun undoPoint() { if (points.size >= 2) { points.removeAt(points.size - 1); points.removeAt(points.size - 1); listener.onPointsChanged(points.size / 2); invalidate() } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val isStylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (isStylus) stylusSeen = true
        if (e.actionMasked == MotionEvent.ACTION_DOWN) penDown = isStylus
        val action = e.actionMasked
        val x = e.x; val y = e.y

        if (action == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y; lastX = x; lastY = y
            drag = Drag.NONE
            // fast-scroll thumb
            if (mode != 1 && contentH * zoom > height * 2 && System.currentTimeMillis() < thumbUntil && x > width - 28 * density && thumbRect().let { y in it.top - 12 * density..it.bottom + 12 * density }) {
                drag = Drag.THUMB; scroller.forceFinished(true); return true
            }
            val drawing = tool == ViewTool.INK || tool == ViewTool.ERASER || tool == ViewTool.DRAG_RECT || tool == ViewTool.DRAG_LINE || tool == ViewTool.MARKUP
            val palm = (tool == ViewTool.INK || tool == ViewTool.ERASER) && stylusOnly && stylusSeen && !isStylus
            if (drawing && !palm) {
                val pt = screenToPage(x, y)
                if (pt != null) { drag = Drag.TOOL; beginTool(pt, e.pressure); return true }
            }
            if (tool == ViewTool.NONE) {
                if (selPage >= 0 && selQuads.isNotEmpty()) {
                    val f = selQuads.first(); val l = selQuads.last()
                    val a = pageToScreen(selPage, f[4], f[5]); val b = pageToScreen(selPage, l[6], l[7])
                    val r = 26 * density
                    if (hypot(x - b.x, y - b.y - 9 * density) < r) { drag = Drag.SEL_B; return true }
                    if (hypot(x - a.x, y - a.y - 9 * density) < r) { drag = Drag.SEL_A; return true }
                }
                selectedAnnot?.let { a ->
                    val r = screenRect(a.page, a.rect).apply { inset(-3 * density, -3 * density) }
                    if (a.resizable && hypot(x - r.right, y - r.bottom) < 24 * density) { drag = Drag.ANNOT_RESIZE; annotDrag = RectF(a.rect); return true }
                    if (r.contains(x, y) && a.type !in MARKUP_TYPES) { drag = Drag.ANNOT_MOVE; annotDrag = RectF(a.rect) }
                }
            }
        }

        when (drag) {
            Drag.THUMB -> {
                if (action == MotionEvent.ACTION_MOVE) { sy = (y / max(1f, height.toFloat())).coerceIn(0f, 1f) * max(0f, contentH * zoom - height); thumbUntil = System.currentTimeMillis() + 1500; invalidate() }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { drag = Drag.NONE; schedulePatch() }
                return true
            }
            Drag.TOOL -> {
                if (e.pointerCount > 1) { cancelStroke(); scaleDetector.onTouchEvent(e); return true }
                val pt = screenToPage(x, y, if (tool == ViewTool.INK) strokePage else dragPage)
                if (pt != null) when (action) {
                    MotionEvent.ACTION_MOVE -> {
                        if (tool == ViewTool.INK) for (h in 0 until e.historySize) screenToPage(e.getHistoricalX(h), e.getHistoricalY(h), strokePage)?.let { moveTool(it, e.getHistoricalPressure(h)) }
                        moveTool(pt, e.pressure)
                    }
                    MotionEvent.ACTION_UP -> { moveTool(pt, e.pressure); endTool(); drag = Drag.NONE }
                    MotionEvent.ACTION_CANCEL -> cancelStroke()
                }
                invalidate(); return true
            }
            Drag.SEL_A, Drag.SEL_B -> {
                if (action == MotionEvent.ACTION_MOVE) {
                    screenToPage(x, y - 30 * density, selPage)?.let { pt -> if (drag == Drag.SEL_A) selA = floatArrayOf(pt[1], pt[2]) else selB = floatArrayOf(pt[1], pt[2]); updateSelection(false) }
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { drag = Drag.NONE; updateSelection(true) }
                gestures.onTouchEvent(e)
                return true
            }
            Drag.ANNOT_MOVE, Drag.ANNOT_RESIZE -> {
                val a = selectedAnnot
                if (a != null && e.pointerCount == 1) {
                    val k = pageScale(a.page) * zoom
                    if (action == MotionEvent.ACTION_MOVE && hypot(x - downX, y - downY) > 6 * density) {
                        val dx = (x - downX) / k; val dy = (y - downY) / k
                        annotDrag = if (drag == Drag.ANNOT_MOVE) RectF(a.rect).apply { offset(dx, dy) }
                        else RectF(a.rect.left, a.rect.top, max(a.rect.left + 8f, a.rect.right + dx), max(a.rect.top + 8f, a.rect.bottom + dy))
                        invalidate(); return true
                    }
                    if (action == MotionEvent.ACTION_UP) {
                        val moved = hypot(x - downX, y - downY) > 6 * density
                        val wasMove = drag == Drag.ANNOT_MOVE
                        val r = annotDrag
                        drag = Drag.NONE
                        if (moved && r != null) {
                            if (wasMove) listener.onAnnotMoved(a, r.left - a.rect.left, r.top - a.rect.top) else listener.onAnnotResized(a, r)
                            return true
                        }
                        annotDrag = null
                    }
                }
                if (action == MotionEvent.ACTION_CANCEL) { drag = Drag.NONE; annotDrag = null }
                if (action == MotionEvent.ACTION_MOVE) return true
            }
            Drag.NONE -> {}
        }

        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (scroller.isFinished) { snapPage(); schedulePatch() }
        }
        return true
    }

    private fun beginTool(pt: FloatArray, pressure: Float) {
        val p = pt[0].toInt()
        when (tool) {
            ViewTool.INK -> {
                inkJob?.cancel()
                if (strokePage != p) { commitInk(); strokePage = p }
                strokes.add(arrayListOf(pt[1], pt[2])); pressureSum += if (penDown) pressure else 1f; pressureN++
            }
            ViewTool.ERASER -> listener.onErase(p, pt[1], pt[2], 9f / (pageScale(p) * zoom) * density)
            ViewTool.DRAG_RECT -> { dragPage = p; dragRect = RectF(pt[1], pt[2], pt[1], pt[2]) }
            ViewTool.DRAG_LINE -> { dragPage = p; lineA = PointF(pt[1], pt[2]); lineB = PointF(pt[1], pt[2]) }
            ViewTool.MARKUP -> { dragPage = p; selPage = p; selA = floatArrayOf(pt[1], pt[2]); selB = floatArrayOf(pt[1], pt[2]); selQuads = emptyList() }
            else -> {}
        }
    }

    private fun moveTool(pt: FloatArray, pressure: Float) {
        when (tool) {
            ViewTool.INK -> strokes.lastOrNull()?.let { s ->
                val n = s.size
                if (n < 2 || hypot(s[n - 2] - pt[1], s[n - 1] - pt[2]) > 0.4f) { s.add(pt[1]); s.add(pt[2]); pressureSum += if (penDown) pressure else 1f; pressureN++ }
            }
            ViewTool.ERASER -> listener.onErase(pt[0].toInt(), pt[1], pt[2], 9f / (pageScale(pt[0].toInt()) * zoom) * density)
            ViewTool.DRAG_RECT -> dragRect?.let { it.right = pt[1]; it.bottom = pt[2] }
            ViewTool.DRAG_LINE -> lineB = PointF(pt[1], pt[2])
            ViewTool.MARKUP -> { selB = floatArrayOf(pt[1], pt[2]); updateSelection(false) }
            else -> {}
        }
    }

    private fun endTool() {
        when (tool) {
            ViewTool.INK -> { inkJob?.cancel(); inkJob = scope.launch { delay(1100); commitInk() } }
            ViewTool.DRAG_RECT -> dragRect?.let { r ->
                dragRect = null
                val n = RectF(min(r.left, r.right), min(r.top, r.bottom), max(r.left, r.right), max(r.top, r.bottom))
                if (n.width() > 3f && n.height() > 3f) listener.onRect(dragPage, n)
            }
            ViewTool.DRAG_LINE -> { val a = lineA; val b = lineB; lineA = null; lineB = null
                if (a != null && b != null && hypot(a.x - b.x, a.y - b.y) > 3f) listener.onLine(dragPage, a.x, a.y, b.x, b.y) }
            ViewTool.MARKUP -> {
                val d = doc; val p = selPage; val a = selA; val b = selB
                selJob?.cancel()
                if (d != null && a != null && b != null && p >= 0) scope.launch {
                    val q = withContext(Mu) { d.selectionQuads(p, a[0], a[1], b[0], b[1]) }
                    selPage = -1; selA = null; selB = null; selQuads = emptyList(); invalidate()
                    if (q.isNotEmpty()) listener.onMarkup(p, q)
                }
            }
            else -> {}
        }
    }

    fun selectionBounds(): RectF? {
        if (selPage < 0 || selQuads.isEmpty()) return null
        val r = RectF(selQuads.first().quadBounds())
        for (q in selQuads) r.union(q.quadBounds())
        return r
    }

    companion object {
        private val MARKUP_TYPES = intArrayOf(8, 9, 10, 11)
    }
}
