package uk.jj.pdfplus.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * File-level tools. Each opens its own document handles, so they are safe to run on the MuBg thread
 * while another document is open in the reader. Every tool writes a new file and leaves the input untouched.
 */
object PdfTools {

    private const val FONT_KEY = "PdfPlusF1"
    private const val GS_KEY = "PdfPlusGS"

    fun openPdf(path: String, password: String? = null): PDFDocument {
        val d = Document.openDocument(path)
        if (d.needsPassword() && (password == null || !d.authenticatePassword(password))) { d.destroy(); throw NeedsPassword() }
        return d.asPDF() ?: run { d.destroy(); throw IllegalArgumentException("Not a PDF file") }
    }

    private const val SAVE = "garbage=2,compress"

    // ---------- Pages ----------

    fun merge(inputs: List<String>, out: String, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val dst = PDFDocument()
        try {
            inputs.forEachIndexed { n, p ->
                progress(n, inputs.size)
                val src = openPdf(p)
                try { for (i in 0 until src.countPages()) dst.graftPage(-1, src, i) } finally { src.destroy() }
            }
            dst.save(out, SAVE)
        } finally { dst.destroy() }
    }

    /** Copies the listed pages (0-based, in the order given) into a new file. */
    fun extract(input: String, pages: List<Int>, out: String, password: String? = null) {
        val src = openPdf(input, password)
        val dst = PDFDocument()
        try {
            for (i in pages) if (i in 0 until src.countPages()) dst.graftPage(-1, src, i)
            dst.save(out, SAVE)
        } finally { dst.destroy(); src.destroy() }
    }

    /** Splits into files of [every] pages each. Returns the files written. */
    fun split(input: String, every: Int, outDir: File, baseName: String): List<File> {
        val src = openPdf(input)
        val out = ArrayList<File>()
        try {
            val n = src.countPages()
            var start = 0
            var part = 1
            while (start < n) {
                val end = min(n, start + max(1, every))
                val dst = PDFDocument()
                try {
                    for (i in start until end) dst.graftPage(-1, src, i)
                    val f = File(outDir, "$baseName - part $part.pdf")
                    dst.save(f.absolutePath, SAVE); out.add(f)
                } finally { dst.destroy() }
                start = end; part++
            }
        } finally { src.destroy() }
        return out
    }

    /**
     * Rebuilds a file from a page plan: [order] lists source page numbers in their new order (pages left
     * out are deleted); [rotate] maps a source page number to extra clockwise degrees.
     */
    fun rearrange(input: String, order: List<Int>, rotate: Map<Int, Int>, out: String) {
        val src = openPdf(input)
        try {
            for ((page, deg) in rotate) {
                if (page !in 0 until src.countPages() || deg % 360 == 0) continue
                val obj = src.findPage(page)
                val cur = runCatching { obj.getInheritable("Rotate").asInteger() }.getOrDefault(0)
                obj.put("Rotate", ((cur + deg) % 360 + 360) % 360)
            }
            src.rearrangePages(order.filter { it in 0 until src.countPages() }.toIntArray())
            src.save(out, SAVE)
        } finally { src.destroy() }
    }

    /** Inserts all pages of [other] (or one blank page when [other] is null) before page [at]. */
    fun insert(input: String, at: Int, other: String?, out: String) {
        val src = openPdf(input)
        try {
            if (other == null) {
                val b = (src.loadPage(min(at, src.countPages() - 1).coerceAtLeast(0))).bounds
                val pg = src.addPage(Rect(0f, 0f, b.x1 - b.x0, b.y1 - b.y0), 0, src.newDictionary(), "")
                src.insertPage(at, pg)
            } else {
                val o = openPdf(other)
                try { for (i in 0 until o.countPages()) src.graftPage(at + i, o, i) } finally { o.destroy() }
            }
            src.save(out, SAVE)
        } finally { src.destroy() }
    }

    /** Trims each listed page by the given margins (points, measured on the upright page). */
    fun crop(input: String, pages: List<Int>, left: Float, top: Float, right: Float, bottom: Float, out: String) {
        val src = openPdf(input)
        try {
            for (i in pages) {
                val pg = src.loadPage(i) as? PDFPage ?: continue
                val b = pg.bounds
                val r = Rect(b.x0 + left, b.y0 + top, b.x1 - right, b.y1 - bottom)
                if (r.x1 - r.x0 > 20 && r.y1 - r.y0 > 20) pg.setCropBox(r.transform(Matrix.Inverted(pg.transform)).normalised())
                pg.destroy()
            }
            src.save(out, SAVE)
        } finally { src.destroy() }
    }

    private fun Rect.normalised() = Rect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))

    // ---------- Stamping text onto pages ----------

    private fun pdfString(s: String): String {
        val sb = StringBuilder("(")
        for (ch in s) {
            val c = if (ch.code in 32..255) ch.code else '?'.code
            when {
                ch == '(' || ch == ')' || ch == '\\' -> sb.append('\\').append(ch)
                c > 126 -> sb.append('\\').append(String.format(Locale.ROOT, "%03o", c))
                else -> sb.append(c.toChar())
            }
        }
        return sb.append(')').toString()
    }

    fun textWidth(font: Font, s: String, size: Float): Float {
        var w = 0f
        for (ch in s) w += font.advanceGlyph(font.encodeCharacter(if (ch.code in 32..255) ch.code else '?'.code))
        return w * size
    }

    private fun f(v: Float) = String.format(Locale.ROOT, "%.3f", v)

    private fun resources(doc: PDFDocument, page: PDFObject): PDFObject {
        var res = page.getInheritable("Resources")
        if (res == null || res.isNull) { res = doc.newDictionary(); page.put("Resources", res) }
        return res.resolve()
    }

    private fun subDict(doc: PDFDocument, parent: PDFObject, key: String): PDFObject {
        var d = parent.get(key)
        if (d == null || d.isNull) { d = doc.newDictionary(); parent.put(key, d) }
        return d.resolve()
    }

    /** Appends drawing commands written in upright top-left page coordinates (points, y down). */
    private fun appendContent(doc: PDFDocument, pageNo: Int, body: String) {
        val pg = doc.loadPage(pageNo) as PDFPage
        val inv = Matrix.Inverted(pg.transform)
        val obj = doc.findPage(pageNo)
        val cm = "${f(inv.a)} ${f(inv.b)} ${f(inv.c)} ${f(inv.d)} ${f(inv.e)} ${f(inv.f)} cm\n"
        val old = obj.get("Contents")
        val arr = doc.newArray()
        arr.push(doc.addStream("q\n"))
        if (old != null && !old.isNull) { if (old.isArray) for (k in 0 until old.size()) arr.push(old.get(k)) else arr.push(old) }
        arr.push(doc.addStream("\nQ\nq\n$cm$body\nQ\n"))
        obj.put("Contents", arr)
        pg.destroy()
    }

    private fun ensureFont(doc: PDFDocument, pageNo: Int, fontObj: PDFObject, opacity: Float) {
        val res = resources(doc, doc.findPage(pageNo))
        subDict(doc, res, "Font").put(FONT_KEY, fontObj)
        if (opacity < 1f) {
            val gs = doc.newDictionary(); gs.put("ca", opacity); gs.put("CA", opacity)
            subDict(doc, res, "ExtGState").put(GS_KEY, gs)
        }
    }

    private fun textCmd(text: String, size: Float, x: Float, y: Float, rgb: FloatArray, angleDeg: Float = 0f, opacity: Float = 1f, invisible: Boolean = false, hScale: Float = 100f): String {
        val a = Math.toRadians(angleDeg.toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return buildString {
            if (opacity < 1f) append("/$GS_KEY gs\n")
            append("BT\n/$FONT_KEY ${f(size)} Tf\n")
            if (invisible) append("3 Tr\n")
            if (hScale != 100f) append("${f(hScale)} Tz\n")
            append("${f(rgb[0])} ${f(rgb[1])} ${f(rgb[2])} rg\n")
            append("${f(c)} ${f(-s)} ${f(-s)} ${f(-c)} ${f(x)} ${f(y)} Tm\n")
            append(pdfString(text)).append(" Tj\nET\n")
        }
    }

    /** position: 0 = bottom centre, 1 = bottom right, 2 = bottom left, 3 = top centre, 4 = top right, 5 = top left. */
    fun pageNumbers(input: String, out: String, position: Int, start: Int, format: String, size: Float, skipFirst: Boolean) {
        val doc = openPdf(input)
        val font = Font("Helvetica")
        try {
            val fo = doc.addSimpleFont(font, 0)
            val n = doc.countPages()
            for (i in 0 until n) {
                if (skipFirst && i == 0) continue
                val label = format.replace("{n}", (start + i - if (skipFirst) 1 else 0).toString()).replace("{total}", (n - if (skipFirst) 1 else 0).toString())
                stampEdge(doc, i, fo, font, label, size, position, floatArrayOf(0.2f, 0.2f, 0.2f))
            }
            doc.save(out, SAVE)
        } finally { font.destroy(); doc.destroy() }
    }

    private fun stampEdge(doc: PDFDocument, pageNo: Int, fo: PDFObject, font: Font, text: String, size: Float, position: Int, rgb: FloatArray) {
        if (text.isBlank()) return
        val pg = doc.loadPage(pageNo); val b = pg.bounds; pg.destroy()
        val w = textWidth(font, text, size)
        val margin = 28f
        val x = when (position % 3) { 0 -> (b.x0 + b.x1 - w) / 2f; 1 -> b.x1 - margin - w; else -> b.x0 + margin }
        val y = if (position < 3) b.y1 - margin + size * 0.3f else b.y0 + margin
        ensureFont(doc, pageNo, fo, 1f)
        appendContent(doc, pageNo, textCmd(text, size, x, y, rgb))
    }

    fun headerFooter(input: String, out: String, header: String, footer: String, size: Float) {
        val doc = openPdf(input)
        val font = Font("Helvetica")
        try {
            val fo = doc.addSimpleFont(font, 0)
            val n = doc.countPages()
            for (i in 0 until n) {
                fun fill(s: String) = s.replace("{n}", (i + 1).toString()).replace("{total}", n.toString())
                stampEdge(doc, i, fo, font, fill(header), size, 3, floatArrayOf(0.25f, 0.25f, 0.25f))
                stampEdge(doc, i, fo, font, fill(footer), size, 0, floatArrayOf(0.25f, 0.25f, 0.25f))
            }
            doc.save(out, SAVE)
        } finally { font.destroy(); doc.destroy() }
    }

    fun watermark(input: String, out: String, text: String, size: Float, opacity: Float, angle: Float, color: Int) {
        val doc = openPdf(input)
        val font = Font("Helvetica-Bold")
        try {
            val fo = doc.addSimpleFont(font, 0)
            val w = textWidth(font, text, size)
            val a = Math.toRadians(angle.toDouble())
            for (i in 0 until doc.countPages()) {
                val pg = doc.loadPage(i); val b = pg.bounds; pg.destroy()
                val cx = (b.x0 + b.x1) / 2f; val cy = (b.y0 + b.y1) / 2f
                val x = cx - (cos(a) * w / 2).toFloat() - (sin(a) * size * 0.35).toFloat()
                val y = cy + (sin(a) * w / 2).toFloat() + (cos(a) * size * 0.35).toFloat()
                ensureFont(doc, i, fo, opacity)
                appendContent(doc, i, textCmd(text, size, x, y, color.toRgb(), angle, opacity))
            }
            doc.save(out, SAVE)
        } finally { font.destroy(); doc.destroy() }
    }

    class OcrLine(val text: String, val x: Float, val y: Float, val w: Float, val h: Float)

    /** Adds invisible, selectable text over a page: used to make scans searchable. Coordinates in points. */
    fun addTextLayer(doc: PDFDocument, pageNo: Int, lines: List<OcrLine>, font: Font, fontObj: PDFObject) {
        if (lines.isEmpty()) return
        ensureFont(doc, pageNo, fontObj, 1f)
        val sb = StringBuilder()
        for (l in lines) {
            val size = max(4f, l.h * 0.8f)
            val natural = textWidth(font, l.text, size)
            if (natural <= 0f) continue
            val scale = (l.w / natural * 100f).coerceIn(20f, 500f)
            sb.append(textCmd(l.text, size, l.x, l.y + l.h * 0.82f, floatArrayOf(0f, 0f, 0f), invisible = true, hScale = scale))
        }
        appendContent(doc, pageNo, sb.toString())
    }

    /** Draws replacement text straight onto the page (used by the basic text editor). */
    fun drawText(doc: PDFDocument, pageNo: Int, text: String, x: Float, baseline: Float, size: Float, color: Int, fontName: String) {
        val font = Font(fontName)
        try {
            val fo = doc.addSimpleFont(font, 0)
            val res = resources(doc, doc.findPage(pageNo))
            val key = "PdfPlus" + fontName.filter { it.isLetterOrDigit() }
            subDict(doc, res, "Font").put(key, fo)
            appendContent(doc, pageNo, textCmd(text, size, x, baseline, color.toRgb()).replace("/$FONT_KEY ", "/$key "))
        } finally { font.destroy() }
    }

    // ---------- Security ----------

    /** permissions: print, copy, edit, annotate. Passwords must not contain commas. */
    fun protect(input: String, out: String, openPw: String, ownerPw: String, print: Boolean, copy: Boolean, edit: Boolean, annotate: Boolean, password: String? = null) {
        var perm = 0xFFFFF0C0.toInt()
        if (print) perm = perm or 4 or 2048
        if (edit) perm = perm or 8 or 1024
        if (copy) perm = perm or 16 or 512
        if (annotate) perm = perm or 32 or 256
        val owner = ownerPw.ifEmpty { openPw }
        val doc = openPdf(input, password)
        try {
            val sb = StringBuilder("garbage=2,compress,encrypt=aes-256,permissions=$perm,owner-password=$owner")
            if (openPw.isNotEmpty()) sb.append(",user-password=$openPw")
            doc.save(out, sb.toString())
        } finally { doc.destroy() }
    }

    fun unlock(input: String, password: String, out: String) {
        val doc = openPdf(input, password)
        try { doc.save(out, "garbage=2,compress,decrypt") } finally { doc.destroy() }
    }

    fun flatten(input: String, out: String) {
        val doc = openPdf(input)
        try { doc.bake(true, true); doc.save(out, SAVE) } finally { doc.destroy() }
    }

    fun setMetadata(input: String, out: String, title: String, author: String, subject: String, keywords: String) {
        val doc = openPdf(input)
        try {
            doc.setMetaData(Document.META_INFO_TITLE, title); doc.setMetaData(Document.META_INFO_AUTHOR, author)
            doc.setMetaData(Document.META_INFO_SUBJECT, subject); doc.setMetaData(Document.META_INFO_KEYWORDS, keywords)
            doc.save(out, SAVE)
        } finally { doc.destroy() }
    }

    fun metadata(input: String): Map<String, String> {
        val d = Document.openDocument(input)
        return try {
            if (d.needsPassword()) return mapOf("Encryption" to "Password protected")
            linkedMapOf(
                "Title" to d.getMetaData(Document.META_INFO_TITLE), "Author" to d.getMetaData(Document.META_INFO_AUTHOR),
                "Subject" to d.getMetaData(Document.META_INFO_SUBJECT), "Keywords" to d.getMetaData(Document.META_INFO_KEYWORDS),
                "Created with" to d.getMetaData(Document.META_INFO_CREATOR), "Producer" to d.getMetaData(Document.META_INFO_PRODUCER),
                "Format" to d.getMetaData(Document.META_FORMAT), "Encryption" to d.getMetaData(Document.META_ENCRYPTION),
                "Pages" to d.countPages().toString(),
            ).filterValues { !it.isNullOrBlank() }.mapValues { it.value ?: "" }
        } finally { d.destroy() }
    }

    // ---------- Compress ----------

    /** level 0 = light, 1 = balanced, 2 = smallest. Returns bytes saved (may be negative: caller keeps the original then). */
    fun compress(input: String, out: String, level: Int, progress: (Int, Int) -> Unit = { _, _ -> }): Long {
        val quality = intArrayOf(82, 68, 50)[level]
        val maxDim = intArrayOf(2400, 1600, 1100)[level]
        val doc = openPdf(input)
        try {
            val n = doc.countObjects()
            for (num in 1 until n) {
                if (num % 20 == 0) progress(num, n)
                runCatching {
                    val obj = doc.newIndirect(num, 0)
                    val dict = obj.resolve()
                    if (!obj.isStream || dict.get("Subtype")?.asName() != "Image") return@runCatching
                    if (dict.get("ImageMask")?.asBoolean() == true) return@runCatching
                    val mask = dict.get("Mask"); if (mask != null && mask.isArray) return@runCatching
                    val w = dict.get("Width").asInteger(); val h = dict.get("Height").asInteger()
                    val raw = obj.readRawStream()
                    if (raw.size < 20_000 || w < 64 || h < 64) return@runCatching
                    val img = doc.loadImage(obj)
                    val pix = img.toPixmap()
                    val png = try { pix.asPNG().asByteArray() } finally { pix.destroy(); img.destroy() }
                    val opts = BitmapFactory.Options()
                    var sample = 1
                    while (max(w, h) / (sample * 2) >= maxDim) sample *= 2
                    opts.inSampleSize = sample
                    val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return@runCatching
                    val scaled = if (max(bmp.width, bmp.height) > maxDim) {
                        val k = maxDim.toFloat() / max(bmp.width, bmp.height)
                        Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
                    } else bmp
                    val grey = dict.get("ColorSpace")?.let { it.isName && it.asName() == "DeviceGray" } == true
                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                    val jpg = bos.toByteArray()
                    val nw = scaled.width; val nh = scaled.height
                    if (scaled !== bmp) scaled.recycle(); bmp.recycle()
                    if (jpg.size >= raw.size * 0.9 || grey) return@runCatching
                    val buf = Buffer(); buf.writeBytes(jpg)
                    obj.writeRawStream(buf)
                    dict.put("Filter", doc.newName("DCTDecode")); dict.delete("DecodeParms"); dict.delete("Decode")
                    dict.put("ColorSpace", doc.newName("DeviceRGB")); dict.put("BitsPerComponent", 8)
                    dict.put("Width", nw); dict.put("Height", nh)
                }
            }
            if (level > 0) runCatching { doc.subsetFonts() }
            doc.save(out, "garbage=4,compress,compress-fonts")
        } finally { doc.destroy() }
        return File(input).length() - File(out).length()
    }

    // ---------- Convert ----------

    fun toImages(input: String, outDir: File, baseName: String, dpi: Int, jpeg: Boolean, pages: List<Int>? = null, progress: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        val d = PdfDoc.open(input)
        val out = ArrayList<File>()
        try {
            val list = pages ?: (0 until d.pageCount).toList()
            list.forEachIndexed { k, i ->
                progress(k, list.size)
                d.measureMore(i, 1)
                var scale = dpi / 72f
                val s = d.sizes[i]
                val limit = 4096f / max(s[0], s[1])
                if (scale > limit) scale = limit
                val bmp = d.render(i, scale, 0, 0, (s[0] * scale).toInt(), (s[1] * scale).toInt(), false)
                val f = File(outDir, "$baseName - page ${i + 1}." + if (jpeg) "jpg" else "png")
                FileOutputStream(f).use { bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, it) }
                bmp.recycle(); out.add(f)
            }
        } finally { d.close() }
        return out
    }

    fun loadUpright(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = runCatching {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        }.getOrDefault(0f)
        if (rot != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(rot) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true); bmp.recycle(); bmp = r
        }
        return bmp
    }

    /** fit: true = each page is A4 with the picture centred, false = page matches the picture. */
    fun imagesToPdf(images: List<String>, out: String, fit: Boolean, quality: Int = 85, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val doc = PDFDocument()
        try {
            images.forEachIndexed { n, path ->
                progress(n, images.size)
                val bmp = loadUpright(path, 2600) ?: return@forEachIndexed
                val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                addImagePage(doc, bos.toByteArray(), bmp.width, bmp.height, fit); bmp.recycle()
            }
            doc.save(out, SAVE)
        } finally { doc.destroy() }
    }

    fun addImagePage(doc: PDFDocument, jpeg: ByteArray, w: Int, h: Int, fit: Boolean) {
        val img = Image(jpeg)
        try {
            val ref = doc.addImage(img)
            val pw: Float; val ph: Float; val dw: Float; val dh: Float
            if (fit) {
                val land = w > h
                pw = if (land) 842f else 595f; ph = if (land) 595f else 842f
                val k = min((pw - 36f) / w, (ph - 36f) / h); dw = w * k; dh = h * k
            } else {
                val k = min(1f, 1440f / max(w, h)).let { if (max(w, h) * it < 400f) 400f / max(w, h) else it }
                pw = w * k; ph = h * k; dw = pw; dh = ph
            }
            val res = doc.newDictionary(); val xo = doc.newDictionary(); xo.put("Im0", ref); res.put("XObject", xo)
            val content = "q ${f(dw)} 0 0 ${f(dh)} ${f((pw - dw) / 2)} ${f((ph - dh) / 2)} cm /Im0 Do Q"
            doc.insertPage(-1, doc.addPage(Rect(0f, 0f, pw, ph), 0, res, content))
        } finally { img.destroy() }
    }

    /** Converts anything the engine can lay out (text, HTML, EPUB, XPS, images, basic Office files) into a PDF. */
    fun anyToPdf(input: String, out: String) {
        val d = Document.openDocument(input)
        try {
            if (d.isReflowable) d.layout(595f, 842f, 11f)
            val w = DocumentWriter(out, "pdf", "compress")
            try {
                for (i in 0 until d.countPages()) {
                    val p = d.loadPage(i)
                    val dev = w.beginPage(p.bounds)
                    p.run(dev, Matrix.Identity(), null)
                    w.endPage(); p.destroy()
                }
            } finally { w.close(); w.destroy() }
        } finally { d.destroy() }
    }

    fun toText(input: String, out: String, password: String? = null) {
        val d = PdfDoc.open(input, password)
        try {
            File(out).bufferedWriter().use { w ->
                for (i in 0 until d.pageCount) { w.write(d.pageText(i)); w.write("\n\u000C\n") }
            }
        } finally { d.close() }
    }
}
