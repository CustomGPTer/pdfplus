@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.pdfplus.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.pdfplus.App
import uk.jj.pdfplus.MuBg
import uk.jj.pdfplus.data.Bookmark
import uk.jj.pdfplus.data.Library
import uk.jj.pdfplus.extras.Ai
import uk.jj.pdfplus.extras.Speaker
import uk.jj.pdfplus.pdf.AnnotInfo
import uk.jj.pdfplus.pdf.LayerInfo
import uk.jj.pdfplus.pdf.OutlineItem
import uk.jj.pdfplus.pdf.PageOps
import uk.jj.pdfplus.pdf.Para
import uk.jj.pdfplus.pdf.PdfTools
import uk.jj.pdfplus.ui.ConfirmDialog
import uk.jj.pdfplus.ui.Field
import uk.jj.pdfplus.ui.FormDialog
import uk.jj.pdfplus.ui.SWATCHES
import uk.jj.pdfplus.ui.humanSize
import uk.jj.pdfplus.ui.importUri
import uk.jj.pdfplus.ui.readBytes
import uk.jj.pdfplus.ui.shareText
import uk.jj.pdfplus.ui.signatureDir
import uk.jj.pdfplus.ui.toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@Composable
fun PageThumb(s: Session, i: Int, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, s, i, s.changeTick) { value = s.read { it.thumbnail(i, 180) }?.asImageBitmap() }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, "Page ${i + 1}", Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Sheet(onClose: () -> Unit, title: String, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FullScreen(onClose: () -> Unit, title: String, actions: @Composable () -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Close") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    actions()
                }
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
fun ReaderPanels(s: Session, speaker: Speaker, panel: Panel, onClose: () -> Unit, onPanel: (Panel) -> Unit) {
    when (panel) {
        Panel.NONE -> {}
        Panel.CONTENTS -> ContentsPanel(s, onClose)
        Panel.VIEW -> ViewPanel(s, onClose)
        Panel.ANNOTS -> AnnotsPanel(s, onClose)
        Panel.PAGES -> PagesPanel(s, onClose)
        Panel.AI -> AiPanel(s, onClose)
        Panel.SIGNATURES -> SignaturesPanel(s, onClose)
        Panel.LAYERS -> LayersPanel(s, onClose)
        Panel.SCALE -> ScalePanel(s, onClose)
        Panel.REFLOW -> ReflowPanel(s, onClose)
        Panel.VOICE -> VoicePanel(speaker, onClose)
        Panel.PROPERTIES -> PropertiesPanel(s, onClose)
    }
}

@Composable
private fun ContentsPanel(s: Session, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val outline by produceState<List<OutlineItem>?>(null, s) { value = s.read { it.outline() } ?: emptyList() }
    var marks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { marks = withContext(Dispatchers.IO) { App.db.bookmarks(s.path) } }
    FullScreen(onClose, "Contents") {
        TabRow(tab) { listOf("Outline", "Pages", "Bookmarks").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
        when (tab) {
            0 -> when {
                outline == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                outline!!.isEmpty() -> Text("This document has no outline. Use the Pages tab to jump around.", Modifier.padding(24.dp))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(outline!!) { o ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = o.page >= 0) { s.goTo(o.page); onClose() }.padding(start = (16 + o.level * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                            Text(o.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (o.page >= 0) Text("${o.page + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            1 -> {
                val state = rememberLazyGridState(initialFirstVisibleItemIndex = maxOf(0, s.page - 3))
                LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize().padding(8.dp), state, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.pageCount) { i ->
                        Column(Modifier.clickable { s.goTo(i); onClose() }, horizontalAlignment = Alignment.CenterHorizontally) {
                            PageThumb(s, i, Modifier.fillMaxWidth().aspectRatio(0.72f).border(if (i == s.page) 3.dp else 1.dp, if (i == s.page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                val here = marks.any { it.page == s.page }
                Button(onClick = { scope.launch { withContext(Dispatchers.IO) { if (here) App.db.deleteBookmarkAt(s.path, s.page) else App.db.addBookmark(s.path, s.page, "Page ${s.page + 1}") }; tick++ } }, modifier = Modifier.padding(16.dp)) {
                    Text(if (here) "Remove bookmark from page ${s.page + 1}" else "Bookmark page ${s.page + 1}")
                }
                LazyColumn {
                    items(marks) { b ->
                        Row(Modifier.fillMaxWidth().clickable { s.goTo(b.page); onClose() }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(b.label.ifBlank { "Page ${b.page + 1}" }, Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { App.db.deleteBookmark(b.id) }; tick++ } }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in options) FilterChip(selected == k, { onPick(k) }, { Text(l) })
    }
}

@Composable
private fun ViewPanel(s: Session, onClose: () -> Unit) {
    Sheet(onClose, "View and colours") {
        Text("Page layout", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("0" to "Scroll", "1" to "One page", "2" to "Two pages"), s.viewMode.toString()) { s.setViewPrefs(mode = it.toInt()) }
        Spacer(Modifier.height(12.dp))
        Text("Colours (remembered for this document)", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("day" to "Day", "night" to "Night", "sepia" to "Sepia", "grey" to "Grey", "custom" to "Custom"), s.colourMode) { s.setViewPrefs(colour = it) }
        if (s.colourMode == "night") Text("Night mode flips light and dark but keeps the colours in pictures recognisable.", style = MaterialTheme.typography.bodySmall)
        if (s.colourMode == "custom") {
            Text("Paper", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) { for (c in listOf(0xFFFFFFFF, 0xFFF4ECD8, 0xFFE8F0E4, 0xFFE6EEF7, 0xFFFFF4F0, 0xFF1E1E1E, 0xFF10202B, 0xFF000000).map { it.toInt() }) Swatch(c, App.prefs.customPaper == c) { App.prefs.customPaper = c; s.applyColours() } }
            Text("Ink", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) { for (c in listOf(0xFF000000, 0xFF3B2F1E, 0xFF1F3A5F, 0xFF234B2C, 0xFFE0E0E0, 0xFFB8C7D9, 0xFFFFD98A, 0xFF8FE39A).map { it.toInt() }) Swatch(c, App.prefs.customInk == c) { App.prefs.customInk = c; s.applyColours() } }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use these as my defaults", Modifier.weight(1f))
            TextButton(onClick = { App.prefs.viewMode = s.viewMode; App.prefs.colourMode = s.colourMode }) { Text("Set") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Go to page", Modifier.weight(1f))
            var p by remember { mutableStateOf("") }
            OutlinedTextField(p, { p = it.filter { ch -> ch.isDigit() } }, Modifier.width(96.dp), singleLine = true, placeholder = { Text("${s.page + 1}") })
            TextButton(onClick = { p.toIntOrNull()?.let { s.goTo(it - 1); onClose() } }) { Text("Go") }
        }
    }
}

@Composable
private fun AnnotsPanel(s: Session, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val list by produceState<List<AnnotInfo>?>(null, s, s.changeTick) { value = s.read { it.allAnnots() } ?: emptyList() }
    FullScreen(onClose, "Notes and markups", actions = {
        TextButton(enabled = !list.isNullOrEmpty(), onClick = {
            val text = list!!.joinToString("\n") { "Page ${it.page + 1} · ${it.typeName}" + (if (it.author.isNotBlank()) " · ${it.author}" else "") + if (it.contents.isNotBlank()) "\n${it.contents}\n" else "" }
            ctx.shareText("${s.name}\n\n$text", "Notes from ${s.name}")
        }) { Text("Export") }
    }) {
        when {
            list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list!!.isEmpty() -> Text("Nothing has been marked up in this document yet.", Modifier.padding(24.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list!!) { a ->
                    Column(Modifier.fillMaxWidth().clickable { s.view?.scrollToPage(a.page, yPoints = a.rect.top); onClose() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Page ${a.page + 1} · ${a.typeName}" + if (a.author.isNotBlank()) " · ${a.author}" else "", style = MaterialTheme.typography.labelLarge)
                        if (a.contents.isNotBlank()) Text(a.contents, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PagesPanel(s: Session, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateListOf<Int>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf(false) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val f = uri?.let { ctx.importUri(it, App.workFile("insert").also { d -> d.deleteRecursively() }, "pdf") } ?: return@rememberLauncherForActivityResult
        val at = (selected.maxOrNull() ?: (s.pageCount - 1)) + 1
        s.edit(structure = true) { PageOps.insertFrom(it, at, f.absolutePath) }
        selected.clear()
    }
    fun arrange(order: List<Int>, keep: List<Int>) { s.edit(structure = true) { PageOps.arrange(it, order) }; selected.clear(); selected.addAll(keep) }
    FullScreen(onClose, if (selected.isEmpty()) "Pages" else "${selected.size} selected", actions = {
        TextButton(onClick = { if (selected.size == s.pageCount) selected.clear() else { selected.clear(); selected.addAll(0 until s.pageCount) } }) { Text(if (selected.size == s.pageCount) "Clear" else "Select all") }
    }) {
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.pageCount) { i ->
                val sel = i in selected
                Column(Modifier.clickable { if (sel) selected.remove(i) else selected.add(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    PageThumb(s, i, Modifier.fillMaxWidth().aspectRatio(0.72f).border(if (sel) 4.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp)) {
            val any = selected.isNotEmpty()
            val pages = selected.sorted()
            TextButton(enabled = any, onClick = { s.edit(structure = true) { PageOps.rotate(it, pages, -90) } }) { Text("Rotate left") }
            TextButton(enabled = any, onClick = { s.edit(structure = true) { PageOps.rotate(it, pages, 90) } }) { Text("Rotate right") }
            TextButton(enabled = any && pages.first() > 0, onClick = {
                val order = (0 until s.pageCount).toMutableList()
                for (p in pages) { order.removeAt(p); order.add(p - 1, p) }
                arrange(order, pages.map { it - 1 })
            }) { Text("Move earlier") }
            TextButton(enabled = any && pages.last() < s.pageCount - 1, onClick = {
                val order = (0 until s.pageCount).toMutableList()
                for (p in pages.reversed()) { order.removeAt(p); order.add(p + 1, p) }
                arrange(order, pages.map { it + 1 })
            }) { Text("Move later") }
            TextButton(enabled = any && selected.size < s.pageCount, onClick = { confirmDelete = true }) { Text("Delete") }
            TextButton(enabled = any, onClick = {
                s.save {
                    scope.launch {
                        val out = Library.uniqueFile(App.outputDir(), s.name.removeSuffix(".pdf") + " (pages).pdf")
                        val ok = withContext(MuBg) { runCatching { PdfTools.extract(s.path, pages, out.absolutePath); true }.getOrDefault(false) }
                        if (ok) { Library.register(out); ctx.toast("Saved ${pages.size} page(s) to Documents/PDF+/${out.name}") } else ctx.toast("Could not extract those pages.")
                    }
                }
            }) { Text("Extract") }
            TextButton(enabled = any, onClick = { cropping = true }) { Text("Crop") }
            TextButton(onClick = { val at = (selected.maxOrNull() ?: (s.pageCount - 1)) + 1; s.edit(structure = true) { PageOps.insertBlank(it, at) } }) { Text("Add blank") }
            TextButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) { Text("Add from PDF") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete ${selected.size} page(s)?", "You can undo this until you close the document.", "Delete", { confirmDelete = false }) {
        val gone = selected.toSet()
        arrange((0 until s.pageCount).filter { it !in gone }, emptyList())
    }
    if (cropping) {
        val l = remember { Field("Left (mm)", "10", number = true) }; val t = remember { Field("Top (mm)", "10", number = true) }
        val r = remember { Field("Right (mm)", "10", number = true) }; val b = remember { Field("Bottom (mm)", "10", number = true) }
        FormDialog("Crop margins", listOf(l, t, r, b), "Crop", "Trims this much off each edge of the selected pages. Nothing is deleted from the file, so it can be undone.", onDismiss = { cropping = false }) {
            cropping = false
            fun pt(f: Field) = (f.value.toFloatOrNull() ?: 0f) * 72f / 25.4f
            val pages = selected.sorted()
            s.edit(structure = true) { PageOps.crop(it, pages, pt(l), pt(t), pt(r), pt(b)) }
        }
    }
}

@Composable
private fun AiPanel(s: Session, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun run(prompt: String, page: Int) {
        busy = true; answer = ""
        scope.launch {
            answer = runCatching {
                val (text, cut) = Ai.documentText(s, page)
                if (text.isBlank()) "This document has no readable text. Run OCR on it first." else
                    Ai.ask(Ai.SYSTEM, "$prompt\n\nDOCUMENT TEXT" + (if (cut) " (first part only, the document is long)" else "") + ":\n$text") + if (cut) "\n\n(Only the first part of this long document was sent.)" else ""
            }.getOrElse { "Could not get an answer: ${it.message}" }
            busy = false
        }
    }
    Sheet(onClose, "Ask AI") {
        Text("The document's text is sent to ${Ai.PROVIDERS.firstOrNull { it.first == App.prefs.aiProvider }?.second ?: "your AI provider"} using your own key. Nothing is sent until you press a button.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !busy, onClick = { run("Summarise this document in a short paragraph followed by the key points as a list.", -1) }) { Text("Summarise document") }
            TextButton(enabled = !busy, onClick = { run("Summarise this page in plain language.", s.page) }) { Text("Summarise this page") }
        }
        OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("Ask a question about the document") }, minLines = 2)
        Button(enabled = !busy && question.isNotBlank(), onClick = { run("Question: $question", -1) }, modifier = Modifier.padding(vertical = 8.dp)) { Text("Ask") }
        if (busy) CircularProgressIndicator()
        if (answer.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(answer, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun SignaturesPanel(s: Session, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var drawing by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    val files = remember(tick) { signatureDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { ctx.readBytes(it) }?.let { File(signatureDir(), "sig-${System.currentTimeMillis()}.png").writeBytes(it); tick++ }
    }
    fun use(f: File) {
        val bytes = f.readBytes()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return
        s.pendingImage = bytes; s.pendingImageAspect = o.outWidth.toFloat() / o.outHeight; s.pendingLabel = "Signature"
        if (s.tool != RTool.SIGN) s.pickTool(RTool.SIGN)
        ctx.toast("Tap the page where you want to sign.")
        onClose()
    }
    Sheet(onClose, "My signatures") {
        if (files.isEmpty()) Text("No signatures saved yet. Draw one, type one, or use a photo of your signature.")
        for (f in files) {
            val bmp = remember(f) { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).clickable { use(f) }, Alignment.Center) { bmp?.let { Image(it, "Signature", Modifier.padding(6.dp)) } }
                TextButton(onClick = { f.delete(); tick++ }) { Text("Delete") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { drawing = true }) { Text("Draw") }
            TextButton(onClick = { typing = true }) { Text("Type") }
            TextButton(onClick = { pick.launch("image/*") }) { Text("From a picture") }
        }
    }
    if (drawing) SignaturePad(onClose = { drawing = false }) { png -> File(signatureDir(), "sig-${System.currentTimeMillis()}.png").writeBytes(png); drawing = false; tick++ }
    if (typing) {
        val f = remember { Field("Your name") }
        FormDialog("Typed signature", listOf(f), "Save", onDismiss = { typing = false }) {
            typing = false
            if (f.value.isNotBlank()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 120f; typeface = Typeface.create("cursive", Typeface.ITALIC); color = android.graphics.Color.rgb(20, 30, 90) }
                val w = p.measureText(f.value).toInt() + 40
                val bmp = Bitmap.createBitmap(w, 190, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawText(f.value, 20f, 130f, p)
                FileOutputStream(File(signatureDir(), "sig-${System.currentTimeMillis()}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tick++
            }
        }
    }
}

@Composable
private fun SignaturePad(onClose: () -> Unit, onSave: (ByteArray) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    FullScreen(onClose, "Draw your signature", actions = {
        TextButton(onClick = { strokes.clear(); current = emptyList() }) { Text("Clear") }
        TextButton(enabled = strokes.isNotEmpty(), onClick = {
            val all = strokes.flatten()
            val minX = all.minOf { it.x } - 12f; val minY = all.minOf { it.y } - 12f
            val w = (all.maxOf { it.x } - minX + 12f).toInt().coerceAtLeast(8); val h = (all.maxOf { it.y } - minY + 12f).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = android.graphics.Color.rgb(20, 30, 90) }
            for (st in strokes) { val path = android.graphics.Path(); st.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x - minX, o.y - minY) else path.lineTo(o.x - minX, o.y - minY) }; c.drawPath(path, p) }
            val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            onSave(bos.toByteArray())
        }) { Text("Save") }
    }) {
        Box(Modifier.fillMaxSize().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).pointerInput(Unit) {
            detectDragGestures(onDragStart = { current = listOf(it) }, onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() }, onDragCancel = { current = emptyList() }) { change, _ -> current = current + change.position }
        }) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val ink = Color(0xFF141E5A)
                for (st in strokes + listOf(current)) for (i in 1 until st.size) drawLine(ink, st[i - 1], st[i], strokeWidth = 6f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun LayersPanel(s: Session, onClose: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val layers by produceState<List<LayerInfo>?>(null, s, tick) { value = s.read { it.layers() } ?: emptyList() }
    Sheet(onClose, "Layers") {
        when {
            layers == null -> CircularProgressIndicator()
            layers!!.isEmpty() -> Text("This document has no layers. Drawings exported from CAD with layers switched on will list them here.")
            else -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (l in layers!!) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.name, Modifier.weight(1f))
                    Switch(l.visible, { on -> s.edit { it.setLayerVisible(l.index, on) }; tick++ })
                }
            }
        }
    }
}

@Composable
private fun ScalePanel(s: Session, onClose: () -> Unit) {
    val mm = 25.4f / 72f
    Sheet(onClose, "Drawing scale") {
        Text("Pick the scale printed on the drawing, or set it from a dimension you know.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(false, { s.setScale(mm, "mm"); onClose() }, { Text("Paper size (mm)") })
            for (n in listOf(5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 1250, 2500)) FilterChip(false, { s.setScale(mm * n / 1000f, "m"); onClose() }, { Text("1:$n") })
        }
        Text("Scales assume the page is shown at its true paper size. If the PDF was shrunk to fit, use the option below instead.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { if (s.tool != RTool.CALIBRATE) s.pickTool(RTool.CALIBRATE); onClose() }, modifier = Modifier.padding(top = 8.dp)) { Text("Set from a known length") }
    }
}

@Composable
private fun ReflowPanel(s: Session, onClose: () -> Unit) {
    var size by remember { mutableFloatStateOf(App.prefs.float("reflowSize", 18f)) }
    val night = s.colourMode == "night"
    val bg = when (s.colourMode) { "night" -> Color(0xFF0E0E0E); "sepia" -> Color(0xFFF4ECD8); else -> MaterialTheme.colorScheme.surface }
    val fg = if (night) Color(0xFFD8D8D8) else if (s.colourMode == "sepia") Color(0xFF3B2F1E) else MaterialTheme.colorScheme.onSurface
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = s.page)
    FullScreen({ s.goTo(state.firstVisibleItemIndex); onClose() }, "Reading view", actions = {
        TextButton(onClick = { size = (size - 2f).coerceAtLeast(12f); App.prefs.set("reflowSize", size) }) { Text("A−") }
        TextButton(onClick = { size = (size + 2f).coerceAtMost(40f); App.prefs.set("reflowSize", size) }) { Text("A+") }
    }) {
        LazyColumn(Modifier.fillMaxSize().background(bg), state) {
            items(s.pageCount) { i ->
                val paras by produceState<List<Para>?>(null, s, i) { value = s.read { it.paragraphs(i) } ?: emptyList() }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text("Page ${i + 1}", style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.5f))
                    when {
                        paras == null -> Text("…", color = fg)
                        paras!!.isEmpty() -> Text("No text on this page (it may be a scan or a drawing).", color = fg.copy(alpha = 0.6f), fontSize = (size - 3).sp)
                        else -> for (p in paras!!) Text(p.text, color = fg, fontSize = size.sp, lineHeight = (size * 1.5f).sp, modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun VoicePanel(speaker: Speaker, onClose: () -> Unit) {
    val voices = remember(speaker.ready) { speaker.voices() }
    var rate by remember { mutableFloatStateOf(App.prefs.ttsRate) }
    var chosen by remember { mutableStateOf(App.prefs.ttsVoice) }
    Sheet(onClose, "Reading voice") {
        Text("Read aloud uses the voices already installed on your phone, so it works offline and nothing is sent anywhere. More voices can be added in the phone's Settings under Text-to-speech.", style = MaterialTheme.typography.bodySmall)
        Text("Speed ${"%.1f".format(rate)}×", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Slider(rate, { rate = it }, valueRange = 0.5f..2.5f, onValueChangeFinished = { App.prefs.ttsRate = rate; speaker.applySettings() })
        TextButton(onClick = { speaker.sample() }) { Text("Play a sample") }
        if (voices.isEmpty()) Text("No voices were found on this phone.")
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(voices) { v ->
                Row(Modifier.fillMaxWidth().clickable { chosen = v.voice.name; App.prefs.ttsVoice = v.voice.name; speaker.applySettings(); speaker.sample() }.padding(vertical = 10.dp)) {
                    Text(if (chosen == v.voice.name) "●" else "○", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp)); Text(v.label)
                }
            }
        }
    }
}

@Composable
private fun PropertiesPanel(s: Session, onClose: () -> Unit) {
    val info by produceState<Map<String, String>?>(null, s, s.changeTick) { value = s.read { it.info() } ?: emptyMap() }
    var editing by remember { mutableStateOf(false) }
    Sheet(onClose, "Properties") {
        val f = File(s.path)
        val rows = listOf("File" to f.name, "Folder" to (f.parent ?: ""), "Size" to humanSize(f.length()), "Pages" to s.pageCount.toString()) + (info ?: emptyMap()).filterValues { it.isNotBlank() }.toList()
        for ((k, v) in rows) Row(Modifier.padding(vertical = 4.dp)) { Text(k, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v) }
        TextButton(onClick = { editing = true }) { Text("Edit title, author and keywords") }
    }
    if (editing) {
        val m = info ?: emptyMap()
        val t = remember { Field("Title", m["Title"].orEmpty()) }; val a = remember { Field("Author", m["Author"].orEmpty()) }
        val su = remember { Field("Subject", m["Subject"].orEmpty()) }; val k = remember { Field("Keywords", m["Keywords"].orEmpty()) }
        FormDialog("Edit properties", listOf(t, a, su, k), "Save", onDismiss = { editing = false }) { editing = false; s.edit { it.setInfo(t.value, a.value, su.value, k.value) } }
    }
}
