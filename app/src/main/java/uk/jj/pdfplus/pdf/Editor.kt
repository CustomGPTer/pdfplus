package uk.jj.pdfplus.pdf

import android.graphics.Color
import android.graphics.RectF
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DefaultColorSpaces
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Shade
import com.artifex.mupdf.fitz.StrokeState
import com.artifex.mupdf.fitz.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TextLineInfo(val page: Int, val rect: RectF, val text: String, val size: Float, val color: Int, val baseline: Float, val bold: Boolean)
class ImageHit(val page: Int, val rect: RectF, val png: ByteArray?)

/** Basic content editing: replace a line of text, lift or delete a picture, add form fields. Runs on the reader thread. */
object Editor {

    fun lineAt(d: PdfDoc, page: Int, x: Float, y: Float): TextLineInfo? {
        val blocks = runCatching { d.text(page).blocks }.getOrNull() ?: return null
        for (b in blocks) for (line in b.lines ?: continue) {
            val bb = line.bbox
            if (x < bb.x0 - 2 || x > bb.x1 + 2 || y < bb.y0 - 2 || y > bb.y1 + 2) continue
            val cs = line.chars?.filter { !it.isWhitespace } ?: continue
            if (cs.isEmpty()) continue
            val text = buildString { line.chars.forEach { appendCodePoint(it.c) } }.trimEnd()
            val heights = cs.map { abs(it.quad.ll_y - it.quad.ul_y) }.sorted()
            val h = heights[heights.size / 2]
            val base = cs.map { it.origin.y }.sorted()[cs.size / 2]
            val argb = cs[0].argb
            val bold = cs.count { it.flags and 8 != 0 } > cs.size / 2
            return TextLineInfo(page, bb.toRectF(), text, max(4f, h * 0.86f), Color.rgb(Color.red(argb), Color.green(argb), Color.blue(argb)), base, bold)
        }
        return null
    }

    /** Removes the original line and draws [newText] in its place with a standard font. */
    fun replaceLine(d: PdfDoc, line: TextLineInfo, newText: String, family: String): Boolean {
        val font = when (family) {
            "serif" -> if (line.bold) "Times-Bold" else "Times-Roman"
            "mono" -> if (line.bold) "Courier-Bold" else "Courier"
            else -> if (line.bold) "Helvetica-Bold" else "Helvetica"
        }
        val ok = d.operation("Edit text") { pdf ->
            val pg = d.pdfPage(line.page) ?: return@operation false
            val a = pg.createAnnotation(PDFAnnotation.TYPE_REDACT)
            val r = line.rect
            a.rect = Rect(r.left + 0.3f, r.top + r.height() * 0.12f, r.right - 0.3f, r.bottom - r.height() * 0.12f)
            a.update()
            // keep pictures and line art, remove only the text under the mark
            pg.applyRedactions(false, 0, 0, 0)
            pg.update()
            d.invalidate()
            if (newText.isNotBlank()) PdfTools.drawText(pdf, line.page, newText, r.left, line.baseline, line.size, line.color, font)
            true
        } == true
        d.invalidate()
        return ok
    }

    /** Finds the pictures drawn on a page and where they sit. */
    fun images(d: PdfDoc, page: Int, withPixels: Boolean): List<ImageHit> {
        val out = ArrayList<ImageHit>()
        val dev = object : Device() {
            override fun close() {}
            override fun fillPath(path: Path?, evenOdd: Boolean, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
            override fun strokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
            override fun clipPath(path: Path?, evenOdd: Boolean, ctm: Matrix?) {}
            override fun clipStrokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?) {}
            override fun fillText(text: Text?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
            override fun strokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
            override fun clipText(text: Text?, ctm: Matrix?) {}
            override fun clipStrokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?) {}
            override fun ignoreText(text: Text?, ctm: Matrix?) {}
            override fun fillShade(shd: Shade?, ctm: Matrix?, alpha: Float, cp: Int) {}
            override fun fillImage(img: Image?, ctm: Matrix?, alpha: Float, cp: Int) {
                if (img == null || ctm == null) return
                val xs = floatArrayOf(ctm.e, ctm.a + ctm.e, ctm.c + ctm.e, ctm.a + ctm.c + ctm.e)
                val ys = floatArrayOf(ctm.f, ctm.b + ctm.f, ctm.d + ctm.f, ctm.b + ctm.d + ctm.f)
                val r = RectF(xs.min(), ys.min(), xs.max(), ys.max())
                if (r.width() < 6f || r.height() < 6f) return
                val png = if (withPixels) runCatching { val p = img.toPixmap(); try { p.asPNG().asByteArray() } finally { p.destroy() } }.getOrNull() else null
                out.add(ImageHit(page, r, png))
            }
            override fun fillImageMask(img: Image?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
            override fun clipImageMask(img: Image?, ctm: Matrix?) {}
            override fun popClip() {}
            override fun beginMask(area: Rect?, luminosity: Boolean, cs: ColorSpace?, bc: FloatArray?, cp: Int) {}
            override fun endMask() {}
            override fun beginGroup(area: Rect?, cs: ColorSpace?, isolated: Boolean, knockout: Boolean, blendmode: Int, alpha: Float) {}
            override fun endGroup() {}
            override fun beginTile(area: Rect?, view: Rect?, xstep: Float, ystep: Float, ctm: Matrix?, id: Int, docId: Int): Int = 0
            override fun endTile() {}
            override fun renderFlags(set: Int, clear: Int) {}
            override fun setDefaultColorSpaces(dcs: DefaultColorSpaces?) {}
            override fun beginLayer(name: String?) {}
            override fun endLayer() {}
            override fun beginStructure(standard: Int, raw: String?, uid: Int) {}
            override fun endStructure() {}
            override fun beginMetatext(meta: Int, text: String?) {}
            override fun endMetatext() {}
        }
        runCatching { d.page(page).runPageContents(dev, Matrix.Identity(), null) }
        return out
    }

    fun imageAt(d: PdfDoc, page: Int, x: Float, y: Float, withPixels: Boolean): ImageHit? {
        val list = images(d, page, false).filter { it.rect.contains(x, y) }
        val hit = list.minByOrNull { it.rect.width() * it.rect.height() } ?: return null
        if (!withPixels) return hit
        return images(d, page, true).firstOrNull { abs(it.rect.left - hit.rect.left) < 0.5f && abs(it.rect.top - hit.rect.top) < 0.5f && abs(it.rect.width() - hit.rect.width()) < 0.5f }
    }

    /** Deletes the picture inside [rect] from the page content (text and line art are kept). */
    fun removeImage(d: PdfDoc, page: Int, rect: RectF): Boolean {
        val ok = d.operation("Remove picture") {
            val pg = d.pdfPage(page) ?: return@operation false
            val a = pg.createAnnotation(PDFAnnotation.TYPE_REDACT)
            a.rect = Rect(rect.left + 1f, rect.top + 1f, rect.right - 1f, rect.bottom - 1f)
            a.update()
            pg.applyRedactions(false, 1, 0, 1)
            pg.update(); true
        } == true
        d.invalidate()
        return ok
    }

    // ---- Form fields ----

    private fun pdfRect(d: PdfDoc, page: Int, rect: RectF): Rect {
        val pg = d.pdfPage(page)!!
        val r = rect.toMu().transform(Matrix.Inverted(pg.transform))
        return Rect(min(r.x0, r.x1), min(r.y0, r.y1), max(r.x0, r.x1), max(r.y0, r.y1))
    }

    private fun acroForm(pdf: PDFDocument): PDFObject {
        val root = pdf.trailer.get("Root").resolve()
        var af = root.get("AcroForm")
        if (af == null || af.isNull) { af = pdf.addObject(pdf.newDictionary()); root.put("AcroForm", af) }
        af = af.resolve()
        if (af.get("Fields").let { it == null || it.isNull }) af.put("Fields", pdf.newArray())
        if (af.get("DA").let { it == null || it.isNull }) af.put("DA", "/Helv 0 Tf 0 g")
        var dr = af.get("DR")
        if (dr == null || dr.isNull) { dr = pdf.newDictionary(); af.put("DR", dr) }
        dr = dr.resolve()
        var fonts = dr.get("Font")
        if (fonts == null || fonts.isNull) { fonts = pdf.newDictionary(); dr.put("Font", fonts) }
        fonts = fonts.resolve()
        if (fonts.get("Helv").let { it == null || it.isNull }) {
            val f = Font("Helvetica")
            try { fonts.put("Helv", pdf.addSimpleFont(f, 0)) } finally { f.destroy() }
        }
        af.put("NeedAppearances", true)
        return af
    }

    private fun attach(pdf: PDFDocument, page: Int, widget: PDFObject) {
        val pageObj = pdf.findPage(page)
        widget.put("P", pageObj)
        val ref = pdf.addObject(widget)
        var annots = pageObj.get("Annots")
        if (annots == null || annots.isNull) { annots = pdf.newArray(); pageObj.put("Annots", annots) }
        annots.resolve().push(ref)
        acroForm(pdf).get("Fields").resolve().push(ref)
    }

    private fun uniqueName(pdf: PDFDocument, base: String): String {
        val n = runCatching { acroForm(pdf).get("Fields").resolve().size() }.getOrDefault(0)
        return "$base ${n + 1}"
    }

    fun addTextField(d: PdfDoc, page: Int, rect: RectF, multiline: Boolean): Boolean {
        val ok = d.operation("Add text field") { pdf ->
            val w = pdf.newDictionary()
            w.put("Type", pdf.newName("Annot")); w.put("Subtype", pdf.newName("Widget")); w.put("FT", pdf.newName("Tx"))
            w.put("T", uniqueName(pdf, "Text")); w.put("Rect", pdfRect(d, page, rect)); w.put("F", 4)
            w.put("DA", "/Helv 0 Tf 0 g")
            if (multiline) w.put("Ff", 4096)
            val mk = pdf.newDictionary(); val bc = pdf.newArray(); bc.push(0.4f); bc.push(0.4f); bc.push(0.4f); mk.put("BC", bc); w.put("MK", mk)
            attach(pdf, page, w); true
        } == true
        d.invalidate()
        return ok
    }

    fun addCheckbox(d: PdfDoc, page: Int, rect: RectF): Boolean {
        val ok = d.operation("Add tick box") { pdf ->
            val r = pdfRect(d, page, rect)
            val bw = r.x1 - r.x0; val bh = r.y1 - r.y0
            fun form(body: String): PDFObject {
                val dict = pdf.newDictionary()
                dict.put("Type", pdf.newName("XObject")); dict.put("Subtype", pdf.newName("Form")); dict.put("BBox", Rect(0f, 0f, bw, bh))
                return pdf.addStream(body, dict)
            }
            val border = "q 0.8 w 0.4 0.4 0.4 RG 0.4 0.4 ${bw - 0.8f} ${bh - 0.8f} re S Q\n"
            val tick = "q ${max(1f, min(bw, bh) * 0.12f)} w 0 0 0 RG 1 J 1 j ${bw * 0.2f} ${bh * 0.5f} m ${bw * 0.42f} ${bh * 0.24f} l ${bw * 0.8f} ${bh * 0.8f} l S Q\n"
            val n = pdf.newDictionary(); n.put("Yes", form(border + tick)); n.put("Off", form(border))
            val ap = pdf.newDictionary(); ap.put("N", n)
            val w = pdf.newDictionary()
            w.put("Type", pdf.newName("Annot")); w.put("Subtype", pdf.newName("Widget")); w.put("FT", pdf.newName("Btn"))
            w.put("T", uniqueName(pdf, "Tick")); w.put("Rect", r); w.put("F", 4)
            w.put("AP", ap); w.put("AS", pdf.newName("Off")); w.put("V", pdf.newName("Off"))
            attach(pdf, page, w); true
        } == true
        d.invalidate()
        return ok
    }

    fun addSignatureField(d: PdfDoc, page: Int, rect: RectF): Int {
        var index = -1
        d.operation("Add signature field") {
            val pg = d.pdfPage(page) ?: return@operation
            val w = pg.createSignature()
            w.rect = rect.toMu(); w.update(); pg.update()
            index = (pg.widgets?.size ?: 0) - 1
        }
        return index
    }
}

/** Page-level changes made directly to the open document (each is one undo step). */
object PageOps {
    fun rotate(d: PdfDoc, pages: List<Int>, degrees: Int) = d.operation("Rotate pages") { pdf ->
        for (i in pages) {
            val o = pdf.findPage(i)
            val cur = runCatching { o.getInheritable("Rotate").asInteger() }.getOrDefault(0)
            o.put("Rotate", ((cur + degrees) % 360 + 360) % 360)
        }
    }

    fun arrange(d: PdfDoc, order: List<Int>) = d.operation("Arrange pages") { it.rearrangePages(order.toIntArray()) }

    fun insertBlank(d: PdfDoc, at: Int) = d.operation("Insert page") { pdf ->
        val s = d.sizes[min(max(0, at - 1), d.pageCount - 1)]
        pdf.insertPage(at, pdf.addPage(Rect(0f, 0f, s[0], s[1]), 0, pdf.newDictionary(), ""))
    }

    fun insertFrom(d: PdfDoc, at: Int, path: String) = d.operation("Insert pages") { pdf ->
        val o = PdfTools.openPdf(path)
        try { for (i in 0 until o.countPages()) pdf.graftPage(at + i, o, i) } finally { o.destroy() }
    }

    fun crop(d: PdfDoc, pages: List<Int>, left: Float, top: Float, right: Float, bottom: Float) = d.operation("Crop pages") {
        for (i in pages) {
            val pg = d.pdfPage(i) ?: continue
            val b = pg.bounds
            val r = Rect(b.x0 + left, b.y0 + top, b.x1 - right, b.y1 - bottom)
            if (r.x1 - r.x0 < 20f || r.y1 - r.y0 < 20f) continue
            val t = r.transform(Matrix.Inverted(pg.transform))
            pg.setCropBox(Rect(min(t.x0, t.x1), min(t.y0, t.y1), max(t.x0, t.x1), max(t.y0, t.y1)))
        }
    }
}
