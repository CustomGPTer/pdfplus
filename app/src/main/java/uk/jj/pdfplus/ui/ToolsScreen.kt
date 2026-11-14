package uk.jj.pdfplus.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.pdfplus.App
import uk.jj.pdfplus.MuBg
import uk.jj.pdfplus.data.Library
import uk.jj.pdfplus.data.PdfFile
import uk.jj.pdfplus.extras.Ocr
import uk.jj.pdfplus.pdf.NeedsPassword
import uk.jj.pdfplus.pdf.Office
import uk.jj.pdfplus.pdf.PdfTools
import uk.jj.pdfplus.reader.Reader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class T(val title: String, val about: String, val section: String) {
    SCAN("Scan to PDF", "Photograph paper with the camera. Edges are found and straightened, and the text is made searchable.", "Create"),
    IMG2PDF("Pictures to PDF", "Turn photos or screenshots into one PDF.", "Create"),
    ANY2PDF("Other file to PDF", "Text, web pages, ebooks and basic Word, Excel or PowerPoint files.", "Create"),
    OCR("Make searchable (OCR)", "Reads the words on scanned pages so you can search, select and copy them.", "Improve"),
    COMPRESS("Compress", "Make a PDF smaller for email by squeezing its pictures.", "Improve"),
    FLATTEN("Flatten", "Bake notes, drawings and filled-in form answers into the pages so they cannot be changed.", "Improve"),
    MERGE("Merge", "Join several PDFs into one, in the order you pick them.", "Pages"),
    SPLIT("Split", "Cut a PDF into smaller files every so many pages.", "Pages"),
    NUMBERS("Page numbers", "Print a number on every page.", "Pages"),
    HEADFOOT("Header and footer", "Print a line of text at the top and bottom of every page.", "Pages"),
    WATERMARK("Watermark", "Print faint text such as DRAFT across every page.", "Pages"),
    PROTECT("Add a password", "Lock a PDF so it needs a password to open, and choose what others may do with it.", "Protect"),
    UNLOCK("Remove a password", "Save an unlocked copy of a PDF whose password you know.", "Protect"),
    PDF2DOCX("PDF to Word", "Basic: the text and headings come across, the page layout does not.", "Convert"),
    PDF2XLSX("PDF to Excel", "Basic: simple tables come across as rows and columns, one sheet per page.", "Convert"),
    PDF2IMG("PDF to pictures", "Save each page as a JPG or PNG.", "Convert"),
    PDF2TXT("PDF to plain text", "Save just the words as a text file.", "Convert"),
}

private class Result(val files: List<File>, val message: String, val mime: String = "application/pdf")

@Composable
fun ToolsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<T?>(null) }
    var params by remember { mutableStateOf<Pair<T, List<PdfFile>>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<Result?>(null) }
    val out = remember { App.outputDir() }

    fun run(label: String, work: suspend ((Int, Int) -> Unit) -> Result) {
        busy = label; progress = 0f
        scope.launch {
            val r = runCatching { work { a, b -> progress = if (b > 0) a.toFloat() / b else 0f } }
            busy = null
            r.onSuccess { res -> res.files.filter { it.name.endsWith(".pdf", true) }.forEach { Library.register(it) }; result = res }
                .onFailure { e -> ctx.toast(if (e is NeedsPassword) "That PDF is locked. Use \"Remove a password\" first." else "That did not work: ${e.message ?: "unknown error"}") }
        }
    }
    fun named(f: PdfFile, suffix: String, ext: String = "pdf") = Library.uniqueFile(out, f.name.removeSuffix(".pdf") + " ($suffix).$ext")

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf ?: return@rememberLauncherForActivityResult
        run("Saving your scan") { p ->
            val raw = ctx.importUri(pdf.uri, App.workFile("scan").also { it.deleteRecursively() }, "pdf") ?: throw RuntimeException("the scan could not be read")
            val target = Library.uniqueFile(out, "Scan " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.UK).format(Date()) + ".pdf")
            val n = runCatching { Ocr.makeSearchable(raw.absolutePath, target.absolutePath, p) }.getOrDefault(-1)
            if (n < 0 || !target.exists()) raw.copyTo(target, overwrite = true)
            Result(listOf(target), "Scan saved" + if (n > 0) " and made searchable." else ".")
        }
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        run("Building PDF") { p ->
            val dir = App.workFile("images").also { it.deleteRecursively() }
            val files = uris.mapNotNull { ctx.importUri(it, dir, "jpg") }
            val target = Library.uniqueFile(out, "Pictures " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.UK).format(Date()) + ".pdf")
            withContext(MuBg) { PdfTools.imagesToPdf(files.map { it.absolutePath }, target.absolutePath, true, progress = p) }
            Result(listOf(target), "${files.size} picture(s) saved as one PDF.")
        }
    }
    val pickAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run("Converting to PDF") {
            val src = ctx.importUri(uri, App.workFile("convert").also { it.deleteRecursively() }) ?: throw RuntimeException("the file could not be read")
            val target = Library.uniqueFile(out, src.name.substringBeforeLast('.') + ".pdf")
            withContext(MuBg) { PdfTools.anyToPdf(src.absolutePath, target.absolutePath) }
            Result(listOf(target), "Converted to PDF.")
        }
    }

    fun start(t: T) {
        when (t) {
            T.SCAN -> {
                val opts = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(60)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
                GmsDocumentScanning.getClient(opts).getStartScanIntent(ctx as Activity)
                    .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { ctx.toast("The scanner could not start. It needs Google Play services and may be downloading on first use; try again in a minute.") }
            }
            T.IMG2PDF -> pickImages.launch("image/*")
            T.ANY2PDF -> pickAny.launch(arrayOf("*/*"))
            else -> picking = t
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        for (section in T.entries.map { it.section }.distinct()) {
            item { SectionTitle(section) }
            items(T.entries.filter { it.section == section }) { t ->
                Column(Modifier.fillMaxWidth().clickable { start(t) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(t.title, style = MaterialTheme.typography.bodyLarge)
                    Text(t.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("Results are saved in Documents/PDF+ and the original file is never changed.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
    }

    picking?.let { t ->
        FilePickerDialog(if (t == T.MERGE) "Pick the PDFs to merge, in order" else "Pick a PDF", multi = t == T.MERGE, onDismiss = { picking = null }) { files ->
            picking = null
            val f = files.first()
            when (t) {
                T.MERGE -> if (files.size < 2) ctx.toast("Pick at least two PDFs to merge.") else run("Merging") { p ->
                    val target = named(f, "merged"); withContext(MuBg) { PdfTools.merge(files.map { it.path }, target.absolutePath, p) }; Result(listOf(target), "${files.size} PDFs merged.")
                }
                T.OCR -> run("Reading the pages") { p ->
                    val target = named(f, "searchable"); val n = Ocr.makeSearchable(f.path, target.absolutePath, p)
                    if (n == 0) { target.delete(); throw RuntimeException("every page already has text, so there was nothing to read") }
                    Result(listOf(target), "$n page(s) made searchable.")
                }
                T.FLATTEN -> run("Flattening") { val target = named(f, "flattened"); withContext(MuBg) { PdfTools.flatten(f.path, target.absolutePath) }; Result(listOf(target), "Flattened copy saved.") }
                T.PDF2DOCX -> run("Converting to Word") { p -> val target = named(f, "text", "docx"); withContext(MuBg) { Office.toDocx(f.path, target.absolutePath, progress = p) }; Result(listOf(target), "Word file saved. Text only: check the layout before you send it.", "application/vnd.openxmlformats-officedocument.wordprocessingml.document") }
                T.PDF2XLSX -> run("Converting to Excel") { p -> val target = named(f, "tables", "xlsx"); withContext(MuBg) { Office.toXlsx(f.path, target.absolutePath, progress = p) }; Result(listOf(target), "Excel file saved. Check the columns line up before you rely on it.", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") }
                T.PDF2TXT -> run("Saving text") { val target = named(f, "text", "txt"); withContext(MuBg) { PdfTools.toText(f.path, target.absolutePath) }; Result(listOf(target), "Text file saved.", "text/plain") }
                else -> params = t to files
            }
        }
    }

    params?.let { (t, files) ->
        val f = files.first()
        val close = { params = null }
        when (t) {
            T.SPLIT -> {
                val n = remember { Field("Pages in each file", "1", number = true) }
                FormDialog("Split", listOf(n), "Split", "${f.name} has ${f.pages} pages.", onDismiss = close) {
                    close(); val every = n.value.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    run("Splitting") { val list = withContext(MuBg) { PdfTools.split(f.path, every, out, f.name.removeSuffix(".pdf")) }; Result(list, "Split into ${list.size} files.") }
                }
            }
            T.COMPRESS -> {
                var level by remember { mutableIntStateOf(1) }
                FormDialog("Compress", emptyList(), "Compress", "Smaller files have softer pictures. Text stays sharp at every level.", onDismiss = close, extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Light", "Balanced", "Smallest").forEachIndexed { i, l -> FilterChip(level == i, { level = i }, { Text(l) }) } }
                }) {
                    close()
                    run("Compressing") { p ->
                        val target = named(f, "smaller")
                        val saved = withContext(MuBg) { PdfTools.compress(f.path, target.absolutePath, level, p) }
                        if (saved <= 0) { target.delete(); throw RuntimeException("this file is already as small as it will go") }
                        Result(listOf(target), "Now ${humanSize(target.length())}, down from ${humanSize(f.size)}.")
                    }
                }
            }
            T.PROTECT -> {
                val open = remember { Field("Password to open (leave empty for none)", password = true) }
                val owner = remember { Field("Password to change permissions", password = true) }
                var print by remember { mutableStateOf(true) }; var copy by remember { mutableStateOf(true) }
                var edit by remember { mutableStateOf(false) }; var annotate by remember { mutableStateOf(true) }
                FormDialog("Add a password", listOf(open, owner), "Protect", onDismiss = close, extra = {
                    for ((l, v, set) in listOf(Triple("Allow printing", print) { b: Boolean -> print = b }, Triple("Allow copying text", copy) { b: Boolean -> copy = b }, Triple("Allow editing pages", edit) { b: Boolean -> edit = b }, Triple("Allow notes and form filling", annotate) { b: Boolean -> annotate = b }))
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(l, Modifier.weight(1f)); Switch(v, set) }
                }) {
                    if (open.value.isEmpty() && owner.value.isEmpty()) ctx.toast("Enter at least one password.")
                    else if (open.value.contains(',') || owner.value.contains(',')) ctx.toast("Passwords cannot contain commas.")
                    else { close(); run("Protecting") { val target = named(f, "protected"); withContext(MuBg) { PdfTools.protect(f.path, target.absolutePath, open.value, owner.value, print, copy, edit, annotate) }; Result(listOf(target), "Protected copy saved. Keep the password safe: it cannot be recovered.") } }
                }
            }
            T.UNLOCK -> {
                val pw = remember { Field("Current password", password = true) }
                FormDialog("Remove a password", listOf(pw), "Unlock", onDismiss = close) {
                    close(); run("Unlocking") { val target = named(f, "unlocked"); withContext(MuBg) { PdfTools.unlock(f.path, pw.value, target.absolutePath) }; Result(listOf(target), "Unlocked copy saved.") }
                }
            }
            T.NUMBERS -> {
                val fmt = remember { Field("Text ({n} = number, {total} = last page)", "Page {n} of {total}") }
                val start = remember { Field("Start counting at", "1", number = true) }
                var pos by remember { mutableIntStateOf(0) }; var skip by remember { mutableStateOf(false) }
                FormDialog("Page numbers", listOf(fmt, start), "Add", onDismiss = close, extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Bottom centre", "Bottom right", "Top right").forEachIndexed { i, l -> val v = intArrayOf(0, 1, 4)[i]; FilterChip(pos == v, { pos = v }, { Text(l) }) } }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Skip the first page", Modifier.weight(1f)); Switch(skip, { skip = it }) }
                }) {
                    close(); run("Adding numbers") { val target = named(f, "numbered"); withContext(MuBg) { PdfTools.pageNumbers(f.path, target.absolutePath, pos, start.value.toIntOrNull() ?: 1, fmt.value, 10f, skip) }; Result(listOf(target), "Numbered copy saved.") }
                }
            }
            T.HEADFOOT -> {
                val h = remember { Field("Header") }; val ft = remember { Field("Footer") }
                FormDialog("Header and footer", listOf(h, ft), "Add", "Use {n} for the page number and {total} for the last page.", onDismiss = close) {
                    close(); run("Adding header and footer") { val target = named(f, "headed"); withContext(MuBg) { PdfTools.headerFooter(f.path, target.absolutePath, h.value, ft.value, 9f) }; Result(listOf(target), "Copy saved.") }
                }
            }
            T.WATERMARK -> {
                val text = remember { Field("Text", "DRAFT") }
                var opacity by remember { mutableFloatStateOf(0.18f) }; var diagonal by remember { mutableStateOf(true) }
                FormDialog("Watermark", listOf(text), "Add", onDismiss = close, extra = {
                    Text("Strength"); androidx.compose.material3.Slider(opacity, { opacity = it }, valueRange = 0.05f..0.6f)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Diagonal", Modifier.weight(1f)); Switch(diagonal, { diagonal = it }) }
                }) {
                    close(); if (text.value.isNotBlank()) run("Adding watermark") {
                        val target = named(f, "watermarked")
                        withContext(MuBg) { PdfTools.watermark(f.path, target.absolutePath, text.value, (420f / maxOf(3, text.value.length)).coerceIn(28f, 96f), opacity, if (diagonal) 45f else 0f, 0xFF808080.toInt()) }
                        Result(listOf(target), "Watermarked copy saved.")
                    }
                }
            }
            T.PDF2IMG -> {
                var jpeg by remember { mutableStateOf(true) }; var dpi by remember { mutableIntStateOf(150) }
                FormDialog("PDF to pictures", emptyList(), "Convert", onDismiss = close, extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(jpeg, { jpeg = true }, { Text("JPG") }); FilterChip(!jpeg, { jpeg = false }, { Text("PNG") }) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (d in listOf(100, 150, 300)) FilterChip(dpi == d, { dpi = d }, { Text(if (d == 100) "Small" else if (d == 150) "Medium" else "Print quality") }) }
                }) {
                    close(); run("Saving pictures") { p ->
                        val dir = File(out, f.name.removeSuffix(".pdf") + " pages").apply { mkdirs() }
                        val list = withContext(MuBg) { PdfTools.toImages(f.path, dir, f.name.removeSuffix(".pdf"), dpi, jpeg, progress = p) }
                        Result(list, "${list.size} picture(s) saved in Documents/PDF+/${dir.name}.", if (jpeg) "image/jpeg" else "image/png")
                    }
                }
            }
            else -> close()
        }
    }

    busy?.let { BusyDialog(it, progress) }
    result?.let { r ->
        AlertDialog(onDismissRequest = { result = null }, title = { Text("Done") },
            text = { Text(r.message + if (r.files.size == 1) "\n\nSaved as Documents/PDF+/${r.files[0].name}" else "") },
            confirmButton = {
                TextButton(onClick = { result = null; val f = r.files.first(); if (f.name.endsWith(".pdf", true)) Reader.open(f.absolutePath) else ctx.openWith(f, r.mime) }) { Text("Open") }
            },
            dismissButton = { Row { TextButton(onClick = { ctx.shareFiles(r.files, r.mime) }) { Text("Share") }; TextButton(onClick = { result = null }) { Text("Close") } } })
    }
}
