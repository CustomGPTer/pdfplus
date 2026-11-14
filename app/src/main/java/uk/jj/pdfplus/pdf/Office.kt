package uk.jj.pdfplus.pdf

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Basic, on-device exports to Word and Excel. Text and simple tables carry over; page layout does not. */
object Office {

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch.code < 32 && ch != '\t' && ch != '\n' -> {}
            ch.isSurrogate() || ch.code == 0xFFFE || ch.code == 0xFFFF -> sb.append(ch)
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun ZipOutputStream.put(name: String, body: String) {
        putNextEntry(ZipEntry(name)); write(body.toByteArray(Charsets.UTF_8)); closeEntry()
    }

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    fun toDocx(input: String, out: String, password: String? = null, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val d = PdfDoc.open(input, password)
        try {
            val body = StringBuilder()
            for (i in 0 until d.pageCount) {
                progress(i, d.pageCount)
                val blocks = runCatching { d.text(i).blocks }.getOrNull() ?: emptyArray()
                for (b in blocks) {
                    val lines = b.lines ?: continue
                    val sb = StringBuilder()
                    var heightSum = 0f; var heightN = 0; var bold = 0; var chars = 0
                    for (line in lines) {
                        val cs = line.chars ?: continue
                        val t = buildString { cs.forEach { appendCodePoint(it.c) } }.trim()
                        if (t.isEmpty()) continue
                        for (c in cs) {
                            heightSum += abs(c.quad.ll_y - c.quad.ul_y); heightN++
                            if (c.flags and 8 != 0) bold++
                            chars++
                        }
                        if (sb.isNotEmpty()) { if (sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter()) sb.setLength(sb.length - 1) else sb.append(' ') }
                        sb.append(t)
                    }
                    if (sb.isBlank()) continue
                    val pt = if (heightN > 0) (heightSum / heightN * 0.78f).coerceIn(6f, 48f) else 11f
                    val half = (pt * 2).roundToInt()
                    val isBold = chars > 0 && bold > chars / 2
                    body.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:r><w:rPr>")
                    if (isBold) body.append("<w:b/>")
                    body.append("<w:sz w:val=\"$half\"/></w:rPr><w:t xml:space=\"preserve\">").append(esc(sb.toString())).append("</w:t></w:r></w:p>")
                }
                if (i < d.pageCount - 1) body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
            ZipOutputStream(FileOutputStream(File(out))).use { z ->
                z.put("[Content_Types].xml", XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
                z.put("_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
                z.put("word/document.xml", XML + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                    body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
            }
        } finally { d.close() }
    }

    private class Cell(val x: Float, val y: Float, val h: Float, val text: String)

    private fun colName(i: Int): String {
        var n = i; val sb = StringBuilder()
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    fun toXlsx(input: String, out: String, password: String? = null, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val d = PdfDoc.open(input, password)
        try {
            val sheets = ArrayList<String>()
            for (i in 0 until d.pageCount) {
                progress(i, d.pageCount)
                val cells = ArrayList<Cell>()
                val blocks = runCatching { d.text(i).blocks }.getOrNull() ?: emptyArray()
                for (b in blocks) for (line in b.lines ?: continue) {
                    val t = buildString { line.chars?.forEach { appendCodePoint(it.c) } }.trim()
                    if (t.isNotEmpty()) cells.add(Cell(line.bbox.x0, (line.bbox.y0 + line.bbox.y1) / 2f, line.bbox.y1 - line.bbox.y0, t))
                }
                // rows: cells whose centres sit within half a line of each other
                cells.sortBy { it.y }
                val rows = ArrayList<ArrayList<Cell>>()
                for (c in cells) {
                    val last = rows.lastOrNull()
                    if (last != null && abs(last[0].y - c.y) < maxOf(3f, last[0].h * 0.5f)) last.add(c) else rows.add(arrayListOf(c))
                }
                // columns: cluster the left edges
                val edges = cells.map { it.x }.sorted()
                val cols = ArrayList<Float>()
                for (e in edges) if (cols.isEmpty() || e - cols.last() > 14f) cols.add(e)
                val sb = StringBuilder(XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                rows.forEachIndexed { r, row ->
                    sb.append("<row r=\"${r + 1}\">")
                    val used = HashSet<Int>()
                    for (c in row.sortedBy { it.x }) {
                        var ci = cols.indexOfLast { it <= c.x + 0.5f }.coerceAtLeast(0)
                        while (!used.add(ci)) ci++
                        val ref = colName(ci) + (r + 1)
                        val num = c.text.replace(",", "").replace("£", "").replace("$", "").trim()
                        if (num.matches(Regex("-?\\d+(\\.\\d+)?")) && num.length < 15 && !(num.length > 1 && num.startsWith("0") && !num.startsWith("0."))) {
                            sb.append("<c r=\"$ref\"><v>$num</v></c>")
                        } else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(c.text)).append("</t></is></c>")
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                sheets.add(sb.toString())
            }
            if (sheets.isEmpty()) sheets.add(XML + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>")
            ZipOutputStream(FileOutputStream(File(out))).use { z ->
                val types = StringBuilder(XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                val wb = StringBuilder(XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
                val rels = StringBuilder(XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                sheets.forEachIndexed { k, _ ->
                    types.append("<Override PartName=\"/xl/worksheets/sheet${k + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
                    wb.append("<sheet name=\"Page ${k + 1}\" sheetId=\"${k + 1}\" r:id=\"rId${k + 1}\"/>")
                    rels.append("<Relationship Id=\"rId${k + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${k + 1}.xml\"/>")
                }
                z.put("[Content_Types].xml", types.append("</Types>").toString())
                z.put("_rels/.rels", XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
                z.put("xl/workbook.xml", wb.append("</sheets></workbook>").toString())
                z.put("xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString())
                sheets.forEachIndexed { k, s -> z.put("xl/worksheets/sheet${k + 1}.xml", s) }
            }
        } finally { d.close() }
    }
}
