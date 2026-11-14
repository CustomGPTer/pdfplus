@file:OptIn(ExperimentalMaterial3Api::class)

package uk.jj.pdfplus.reader

import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.artifex.mupdf.fitz.PDFAnnotation
import kotlinx.coroutines.launch
import uk.jj.pdfplus.App
import uk.jj.pdfplus.data.Library
import uk.jj.pdfplus.extras.Speaker
import uk.jj.pdfplus.pdf.Editor
import uk.jj.pdfplus.sign.DigitalId
import uk.jj.pdfplus.ui.ChoiceDialog
import uk.jj.pdfplus.ui.ConfirmDialog
import uk.jj.pdfplus.ui.Field
import uk.jj.pdfplus.ui.FormDialog
import uk.jj.pdfplus.ui.SWATCHES
import uk.jj.pdfplus.ui.copyText
import uk.jj.pdfplus.ui.openUrl
import uk.jj.pdfplus.ui.printPdf
import uk.jj.pdfplus.ui.readBytes
import uk.jj.pdfplus.ui.shareFiles
import uk.jj.pdfplus.ui.toast
import java.io.File

enum class Group(val label: String, val tools: List<RTool>) {
    NONE("", emptyList()),
    ANNOTATE("Annotate", listOf(RTool.HIGHLIGHT, RTool.UNDERLINE, RTool.STRIKE, RTool.SQUIGGLE, RTool.PEN, RTool.MARKER, RTool.ERASER, RTool.TEXT, RTool.CALLOUT, RTool.NOTE, RTool.RECT, RTool.ELLIPSE, RTool.CLOUD, RTool.LINE, RTool.ARROW, RTool.STAMP, RTool.IMAGE, RTool.REDACT_TEXT, RTool.REDACT)),
    SIGN("Fill & sign", listOf(RTool.SIGN, RTool.TICK, RTool.CROSS, RTool.DATE, RTool.TEXT, RTool.CERT_SIGN)),
    FORMS("Forms", listOf(RTool.FIELD_TEXT, RTool.FIELD_CHECK, RTool.FIELD_SIGN)),
    EDIT("Edit", listOf(RTool.EDIT_TEXT, RTool.EDIT_IMAGE, RTool.TEXT, RTool.IMAGE)),
    MEASURE("Measure", listOf(RTool.MEASURE, RTool.MEASURE_PATH, RTool.MEASURE_AREA, RTool.CALIBRATE)),
}

enum class Panel { NONE, CONTENTS, VIEW, ANNOTS, PAGES, AI, SIGNATURES, LAYERS, SCALE, REFLOW, VOICE, PROPERTIES }

@Composable
fun ReaderScreen(s: Session, speaker: Speaker, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var group by remember(s) { mutableStateOf(Group.NONE) }
    var panel by remember(s) { mutableStateOf(Panel.NONE) }
    var searching by remember(s) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRedact by remember { mutableStateOf(false) }
    val night = s.colourMode == "night"

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bytes = uri?.let { ctx.readBytes(it) } ?: return@rememberLauncherForActivityResult
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) { ctx.toast("That picture could not be read."); return@rememberLauncherForActivityResult }
        s.pendingImage = bytes; s.pendingImageAspect = o.outWidth.toFloat() / o.outHeight; s.pendingLabel = ""
        if (s.tool != RTool.IMAGE) s.pickTool(RTool.IMAGE)
        ctx.toast("Tap the page where the picture should go.")
    }

    s.onExternalLink = { url -> ctx.openUrl(url) }
    LaunchedEffect(s.toast) { s.toast?.let { ctx.toast(it); s.toast = null } }
    DisposableEffect(s) { onDispose { if (speaker.session === s) speaker.stop() } }

    BackHandler {
        when {
            searching -> { searching = false; s.search("") }
            s.tool != RTool.NONE -> s.pickTool(s.tool)
            group != Group.NONE -> group = Group.NONE
            s.selection != null -> s.view?.clearSelection()
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(if (night) Color.Black else Color(0xFFDADCE0))) {
        androidx.compose.runtime.key(s) {
            AndroidView(factory = { c -> PdfView(c, App.instance.scope).also { s.attach(it) } }, modifier = Modifier.fillMaxSize())
        }
        if (s.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        s.error?.let { Text(it, Modifier.align(Alignment.Center).padding(32.dp), color = MaterialTheme.colorScheme.error) }

        // ---- top ----
        AnimatedVisibility(s.barsVisible || searching, Modifier.align(Alignment.TopCenter)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
                Column(Modifier.statusBarsPadding()) {
                    if (searching) SearchBar(s) { searching = false; s.search("") } else Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(s.name.removeSuffix(".pdf"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text("Page ${s.page + 1} of ${s.pageCount}" + if (s.unsaved) "  ·  not saved" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onClick = { panel = Panel.CONTENTS }) { Icon(Icons.Default.Menu, "Contents") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                @Composable fun item(label: String, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { menu = false; action() })
                                item("View and colours") { panel = Panel.VIEW }
                                item("Reading view") { panel = Panel.REFLOW }
                                item(if (speaker.session === s) "Stop reading aloud" else "Read aloud") { if (speaker.session === s) speaker.stop() else speaker.start(s, s.page) }
                                item("Notes and markups") { panel = Panel.ANNOTS }
                                item("Pages") { panel = Panel.PAGES }
                                item("Layers") { panel = Panel.LAYERS }
                                if (App.prefs.aiEnabled) item("Ask AI") { panel = Panel.AI }
                                HorizontalDivider()
                                item("Save", s.unsaved) { s.save { ctx.toast("Saved") } }
                                item("Save a copy") { copyDialog(s, false) { ok, f -> ctx.toast(if (ok) "Copy saved to Documents/PDF+/${f.name}" else "Could not save the copy") } }
                                item("Save a flattened copy") { copyDialog(s, true) { ok, f -> ctx.toast(if (ok) "Flattened copy saved to Documents/PDF+/${f.name}" else "Could not save the copy") } }
                                item("Share") { s.save { ctx.shareFiles(listOf(File(s.path))) } }
                                item("Print") { s.save { ctx.printPdf(File(s.path)) } }
                                item("Properties") { panel = Panel.PROPERTIES }
                                item("Close this tab") { Reader.closeTab(s); if (Reader.current == null) onBack() }
                            }
                        }
                    }
                    if (Reader.sessions.size > 1 && !searching) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in Reader.sessions) FilterChip(
                            selected = t === s, onClick = { Reader.current = t },
                            label = { Text(t.name.removeSuffix(".pdf").take(22), maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, "Close tab", Modifier.size(16.dp).clickable { Reader.closeTab(t); if (Reader.current == null) onBack() }) },
                        )
                    }
                }
            }
        }

        // ---- bottom ----
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (speaker.session === s) SpeakBar(speaker) { panel = Panel.VOICE }
            val sel = s.selection
            val ann = s.selectedAnnot
            when {
                sel != null && s.tool == RTool.NONE -> ActionBar {
                    Act("Copy") { scope.launch { ctx.copyText(s.selectionText()); s.view?.clearSelection(); ctx.toast("Copied") } }
                    Act("Highlight") { s.markupSelection(PDFAnnotation.TYPE_HIGHLIGHT) }
                    Act("Underline") { s.markupSelection(PDFAnnotation.TYPE_UNDERLINE) }
                    Act("Strike") { s.markupSelection(PDFAnnotation.TYPE_STRIKE_OUT) }
                    Act("Squiggle") { s.markupSelection(PDFAnnotation.TYPE_SQUIGGLY) }
                    Act("Redact") { s.markupSelection(PDFAnnotation.TYPE_REDACT) }
                    Act("Search") { scope.launch { val t = s.selectionText().trim().take(60); s.view?.clearSelection(); searching = true; s.search(t) } }
                    Act("Read from here") { speaker.start(s, sel.page); s.view?.clearSelection() }
                }
                ann != null && s.tool == RTool.NONE -> ActionBar {
                    Text(ann.typeName, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
                    Act(if (ann.type == PDFAnnotation.TYPE_FREE_TEXT) "Edit text" else "Note") { s.dialog = RDialog.EditAnnot(ann) }
                    for (c in SWATCHES.take(8)) Swatch(c, false) { s.edit(ann.page) { it.editAnnot(ann.page, ann.index, null, c) } }
                    if (ann.type == PDFAnnotation.TYPE_REDACT) Act("Apply") { confirmRedact = true }
                    Act("Delete") { s.edit(ann.page) { it.deleteAnnot(ann.page, ann.index) } }
                }
            }
            AnimatedVisibility(s.barsVisible || group != Group.NONE) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp) {
                    Column(Modifier.navigationBarsPadding()) {
                        if (group == Group.NONE) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (g in Group.entries.drop(1)) TextButton(onClick = { group = g; if (g == Group.SIGN) Unit }) { Text(g.label) }
                            TextButton(onClick = { panel = Panel.PAGES }) { Text("Pages") }
                        } else ToolStrip(s, group, onDone = { if (s.tool != RTool.NONE) s.pickTool(s.tool); group = Group.NONE }, onPanel = { panel = it }, onPickImage = { pickImage.launch("image/*") }, onRedact = { confirmRedact = true })
                    }
                }
            }
        }
    }

    if (confirmRedact) ConfirmDialog("Apply redactions?", "Everything under the redaction marks in this document is removed for good and replaced with black boxes. This cannot be undone after you save.", "Redact", { confirmRedact = false }) {
        scope.launch {
            val n = s.read { it.applyRedactions(-1, true) } ?: 0
            s.afterChange(-1)
            ctx.toast(if (n > 0) "$n redaction${if (n == 1) "" else "s"} applied. Save to make it permanent." else "There are no redaction marks to apply.")
        }
    }

    ReaderDialogs(s, onPanel = { panel = it })
    ReaderPanels(s, speaker, panel, onClose = { panel = Panel.NONE }, onPanel = { panel = it })
}

private fun copyDialog(s: Session, flatten: Boolean, done: (Boolean, File) -> Unit) {
    val base = s.name.removeSuffix(".pdf")
    val f = Library.uniqueFile(App.outputDir(), if (flatten) "$base (flattened).pdf" else "$base (copy).pdf")
    s.saveCopy(f, flatten) { ok -> done(ok, f) }
}

@Composable
private fun SearchBar(s: Session, onClose: () -> Unit) {
    var q by remember { mutableStateOf(s.searchQuery) }
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        OutlinedTextField(q, { q = it; s.search(it) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find in document") })
        Text(
            if (s.searchHits.isEmpty()) (if (s.searching) "…" else if (q.length >= 2) "0" else "") else "${s.searchIndex + 1}/${s.searchHits.size}" + if (s.searching) "…" else "",
            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge,
        )
        IconButton(onClick = { s.stepSearch(-1) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { s.stepSearch(1) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
    }
}

@Composable
private fun ActionBar(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun Act(label: String, onClick: () -> Unit) = TextButton(onClick = onClick) { Text(label) }

@Composable
fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(4.dp).size(26.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onClick)
    )
}

@Composable
private fun ToolStrip(s: Session, group: Group, onDone: () -> Unit, onPanel: (Panel) -> Unit, onPickImage: () -> Unit, onRedact: () -> Unit) {
    val ctx = LocalContext.current
    val t = s.tool
    Column {
        // settings row for the active tool
        val showColours = t in listOf(RTool.HIGHLIGHT, RTool.UNDERLINE, RTool.STRIKE, RTool.SQUIGGLE, RTool.PEN, RTool.MARKER, RTool.RECT, RTool.ELLIPSE, RTool.CLOUD, RTool.LINE, RTool.ARROW, RTool.TEXT, RTool.CALLOUT, RTool.NOTE, RTool.MEASURE, RTool.MEASURE_PATH, RTool.MEASURE_AREA, RTool.TICK, RTool.CROSS, RTool.DATE)
        if (showColours) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val current = when (t) { RTool.HIGHLIGHT -> s.markupColor; RTool.PEN, RTool.MARKER -> s.inkColor; RTool.TEXT, RTool.CALLOUT, RTool.NOTE, RTool.TICK, RTool.CROSS, RTool.DATE -> s.textColor; else -> s.shapeColor }
            for (c in SWATCHES) Swatch(c, c == current) {
                when (t) { RTool.HIGHLIGHT -> s.markupColor = c; RTool.PEN, RTool.MARKER -> s.inkColor = c; RTool.TEXT, RTool.CALLOUT, RTool.NOTE, RTool.TICK, RTool.CROSS, RTool.DATE -> s.textColor = c; else -> s.shapeColor = c }
                s.toolSettingsChanged()
            }
        }
        if (t in listOf(RTool.PEN, RTool.MARKER, RTool.RECT, RTool.ELLIPSE, RTool.CLOUD, RTool.LINE, RTool.ARROW)) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Thickness", style = MaterialTheme.typography.labelMedium)
            Slider(s.inkWidth, { s.inkWidth = it; s.toolSettingsChanged() }, valueRange = 0.5f..8f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        }
        if (t in listOf(RTool.TEXT, RTool.CALLOUT, RTool.DATE)) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size ${s.textSize.toInt()}", style = MaterialTheme.typography.labelMedium)
            Slider(s.textSize, { s.textSize = it }, valueRange = 6f..36f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        }
        if (group == Group.MEASURE) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scale: 1 pt = ${"%.4f".format(s.unitsPerPoint)} ${s.unit}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { onPanel(Panel.SCALE) }) { Text("Change scale") }
            if ((t == RTool.MEASURE_PATH || t == RTool.MEASURE_AREA) && s.pointCount > 0) {
                TextButton(onClick = { s.view?.undoPoint() }) { Text("Undo point") }
                TextButton(onClick = { s.finishPoints() }, enabled = s.pointCount >= if (t == RTool.MEASURE_AREA) 3 else 2) { Text("Finish") }
            }
        }
        val hint = when (t) {
            RTool.HIGHLIGHT, RTool.UNDERLINE, RTool.STRIKE, RTool.SQUIGGLE, RTool.REDACT_TEXT -> "Drag across the words."
            RTool.PEN, RTool.MARKER -> "Draw with one finger or a pen. Two fingers still move the page."
            RTool.TEXT, RTool.RECT, RTool.ELLIPSE, RTool.CLOUD, RTool.REDACT, RTool.FIELD_TEXT, RTool.FIELD_CHECK, RTool.FIELD_SIGN, RTool.CERT_SIGN -> "Drag out a box on the page."
            RTool.CALLOUT -> "Drag from the thing you are pointing at to where the note should sit."
            RTool.LINE, RTool.ARROW, RTool.MEASURE, RTool.CALIBRATE -> "Drag from one end to the other."
            RTool.MEASURE_PATH, RTool.MEASURE_AREA -> "Tap each corner in turn, then Finish."
            RTool.NOTE, RTool.STAMP, RTool.SIGN, RTool.IMAGE, RTool.TICK, RTool.CROSS, RTool.DATE -> "Tap the page where it should go."
            RTool.EDIT_TEXT -> "Tap a line of text to change it."
            RTool.EDIT_IMAGE -> "Tap a picture to move, replace or delete it."
            RTool.ERASER -> "Rub over pen strokes to remove them."
            else -> ""
        }
        if (hint.isNotEmpty()) Text(hint, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { s.undo() }, enabled = s.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = { s.redo() }, enabled = s.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tool in group.tools) FilterChip(selected = t == tool, label = { Text(tool.label, fontSize = 13.sp) }, onClick = {
                    when (tool) {
                        RTool.SIGN -> if (s.tool == RTool.SIGN) s.pickTool(RTool.SIGN) else onPanel(Panel.SIGNATURES)
                        RTool.IMAGE -> if (s.tool == RTool.IMAGE) s.pickTool(RTool.IMAGE) else onPickImage()
                        RTool.CERT_SIGN -> if (!DigitalId.exists()) { ctx.toast("Create or import a digital ID in Settings first.") } else s.pickTool(tool)
                        else -> s.pickTool(tool)
                    }
                })
                if (group == Group.ANNOTATE) FilterChip(false, onClick = onRedact, label = { Text("Apply redactions", fontSize = 13.sp) })
                if (group == Group.FORMS) FilterChip(s.view?.formHighlight != false, onClick = { s.view?.let { it.formHighlight = !it.formHighlight; it.invalidate(); s.changeTick++ } }, label = { Text("Show fields", fontSize = 13.sp) })
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun SpeakBar(sp: Speaker, onVoice: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reading aloud", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { sp.skip(-1) }) { Text("Back") }
            TextButton(onClick = { if (sp.speaking) sp.pause() else sp.resume() }) { Text(if (sp.speaking) "Pause" else "Play") }
            TextButton(onClick = { sp.skip(1) }) { Text("Next") }
            TextButton(onClick = onVoice) { Text("Voice") }
            TextButton(onClick = { sp.stop() }) { Text("Stop") }
        }
    }
}

val STAMPS = listOf("Approved", "NotApproved", "Draft", "Final", "Confidential", "ForComment", "Expired", "AsIs", "Sold", "TopSecret", "ForPublicRelease", "NotForPublicRelease", "Experimental", "Departmental")

@Composable
private fun ReaderDialogs(s: Session, onPanel: (Panel) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val close = { s.dialog = null }
    when (val d = s.dialog) {
        null -> {}
        is RDialog.Password -> {
            val f = remember(d) { Field("Password", password = true) }
            FormDialog("This PDF is locked", listOf(f), "Open", if (d.wrong) "That password did not work. Try again." else "Enter the password to open it.", onDismiss = { close(); Reader.closeTab(s) }) { s.dialog = null; s.open(f.value) }
        }
        is RDialog.TextBox -> {
            val f = remember(d) { Field("Text", lines = 3) }
            FormDialog(if (d.calloutTo != null) "Callout" else "Text box", listOf(f), "Add", onDismiss = close) {
                close()
                if (f.value.isNotBlank()) { val size = s.textSize; val c = s.textColor; s.edit(d.page) { it.addFreeText(d.page, d.rect, f.value, size, c, d.calloutTo) } }
            }
        }
        is RDialog.Note -> {
            val f = remember(d) { Field("Note", lines = 3) }
            FormDialog("Sticky note", listOf(f), "Add", onDismiss = close) { close(); val c = s.markupColor; s.edit(d.page) { it.addNote(d.page, d.x, d.y, f.value, c) } }
        }
        is RDialog.EditAnnot -> {
            val f = remember(d) { Field("Text", d.a.contents, lines = 3) }
            FormDialog(d.a.typeName, listOf(f), "Save", onDismiss = close) { close(); s.edit(d.a.page) { it.editAnnot(d.a.page, d.a.index, f.value, null) } }
        }
        is RDialog.FieldText -> {
            val f = remember(d) { Field(d.w.label.ifBlank { "Value" }, d.w.value, lines = if (d.w.multiline) 3 else 1) }
            FormDialog("Fill in field", listOf(f), "Done", onDismiss = close) { close(); s.edit(d.w.page) { it.setWidgetText(d.w.page, d.w.index, f.value) } }
        }
        is RDialog.FieldChoice -> ChoiceDialog(d.w.label.ifBlank { "Choose" }, d.w.options, d.w.options.indexOf(d.w.value), close) { i -> s.edit(d.w.page) { it.setWidgetChoice(d.w.page, d.w.index, d.w.options[i]) } }
        is RDialog.SignatureField -> {
            if (d.w.signed) {
                var info by remember(d) { mutableStateOf("Checking…") }
                LaunchedEffect(d) {
                    info = s.read { doc ->
                        val w = doc.rawWidget(d.w.page, d.w.index)
                        val changed = runCatching { w?.incrementalChangesSinceSigning() }.getOrNull()
                        "This box holds a digital signature." + when (changed) { true -> "\n\nThe document has been changed since it was signed."; false -> "\n\nThe document has not been changed since it was signed."; else -> "" } +
                            "\n\nPDF+ does not check who issued the signer's certificate, so treat the name as unverified."
                    } ?: "This box holds a digital signature."
                }
                ConfirmDialog("Signed", info, "Close", close) {}
            } else ChoiceDialog("Signature box", listOf("Sign with my drawn signature", "Sign with my digital ID"), -1, close) { i ->
                if (i == 0) onPanel(Panel.SIGNATURES) else if (!DigitalId.exists()) ctx.toast("Create or import a digital ID in Settings first.") else s.dialog = RDialog.CertSign(d.w.page, RectF(d.w.rect), d.w.index)
            }
        }
        is RDialog.Calibrate -> {
            val v = remember(d) { Field("Real length of that line", number = true) }
            var unit by remember { mutableStateOf("m") }
            FormDialog("Set the scale", listOf(v), "Set", "Type how long the line you just drew is in real life.", onDismiss = close, extra = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (u in listOf("mm", "m", "in", "ft")) FilterChip(unit == u, { unit = u }, { Text(u) }) }
            }) {
                val real = v.value.toFloatOrNull()
                if (real != null && real > 0 && d.points > 0) { s.setScale(real / d.points, unit); close(); s.pickTool(RTool.MEASURE) } else ctx.toast("Enter a number greater than zero.")
            }
        }
        is RDialog.EditLine -> {
            val f = remember(d) { Field("Text", d.line.text, lines = 2) }
            var family by remember { mutableStateOf("sans") }
            FormDialog("Edit text", listOf(f), "Replace", "The line is redrawn in a standard font, so its look may change slightly.", onDismiss = close, extra = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for ((k, l) in listOf("sans" to "Sans", "serif" to "Serif", "mono" to "Mono")) FilterChip(family == k, { family = k }, { Text(l) }) }
            }) { close(); s.edit(d.line.page) { Editor.replaceLine(it, d.line, f.value, family) } }
        }
        is RDialog.Picture -> ChoiceDialog("Picture", listOf("Make movable (lift it off the page)", "Delete picture"), -1, close) { i ->
            val h = d.hit
            if (i == 1) s.edit(h.page) { Editor.removeImage(it, h.page, h.rect) }
            else scope.launch {
                val full = s.read { Editor.imageAt(it, h.page, h.rect.centerX(), h.rect.centerY(), true) }
                val png = full?.png
                if (png == null) ctx.toast("That picture could not be lifted.") else {
                    s.edit(h.page) { if (Editor.removeImage(it, h.page, h.rect)) it.addImageStamp(h.page, h.rect, png, "") }
                    s.pickTool(s.tool); ctx.toast("Tap the picture, then drag it or pull its corner.")
                }
            }
        }
        is RDialog.StampPick -> ChoiceDialog("Stamp", STAMPS.map { it.replace(Regex("([a-z])([A-Z])"), "$1 $2") }, -1, close) { i -> s.edit(d.page) { it.addStamp(d.page, RectF(d.x - 70f, d.y - 20f, d.x + 70f, d.y + 20f), STAMPS[i]) } }
        is RDialog.CertSign -> {
            val pw = remember(d) { Field("Digital ID password", password = true) }
            val reason = remember(d) { Field("Reason (optional)") }
            val place = remember(d) { Field("Location (optional)") }
            FormDialog("Sign with digital ID", listOf(pw, reason, place), "Sign", "The file is saved as part of signing. Any later change will show as made after signing.", onDismiss = close) {
                val id = DigitalId.load(pw.value)
                if (id == null) ctx.toast("Wrong password for the digital ID.") else {
                    close()
                    scope.launch {
                        val ok = s.read { doc ->
                            val idx = if (d.existing >= 0) d.existing else Editor.addSignatureField(doc, d.page, d.rect)
                            val w = doc.rawWidget(d.page, idx) ?: return@read false
                            val signer = DigitalId.signer(id)
                            val done = w.sign(signer, 31, null, reason.value.ifBlank { null }, place.value.ifBlank { null })
                            if (done) doc.save()
                            done
                        } == true
                        s.afterChange(d.page)
                        ctx.toast(if (ok) "Signed and saved." else "Signing failed.")
                        if (s.tool == RTool.CERT_SIGN) s.pickTool(RTool.CERT_SIGN)
                    }
                }
            }
        }
        is RDialog.Message -> ConfirmDialog(d.title, d.text, "Close", close) {}
    }
}
