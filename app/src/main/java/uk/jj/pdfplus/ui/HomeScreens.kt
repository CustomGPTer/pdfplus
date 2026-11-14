package uk.jj.pdfplus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.pdfplus.App
import uk.jj.pdfplus.data.Collection
import uk.jj.pdfplus.data.Library
import uk.jj.pdfplus.data.PdfFile
import uk.jj.pdfplus.data.TextHit
import uk.jj.pdfplus.reader.Reader
import java.io.File

@Composable
fun Thumb(f: PdfFile, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, f.path, f.mtime) { value = Library.thumb(f)?.asImageBitmap() }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color.White), Alignment.Center) {
        if (bmp != null) Image(bmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else Text("PDF", color = Color(0xFFB0B4BA), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun FileRow(f: PdfFile, onOpen: () -> Unit, actions: FileActions?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(f, Modifier.size(48.dp, 64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name.removeSuffix(".pdf"), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val bits = listOfNotNull(if (f.pages > 0) "${f.pages} page${if (f.pages == 1) "" else "s"}" else null, humanSize(f.size), humanDate(f.mtime), if (f.lastPage > 0 && f.pages > 0) "at page ${f.lastPage + 1}" else null)
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (f.tags.isNotBlank()) Text(f.tagList.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        if (f.favourite) Icon(Icons.Default.Star, "Favourite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        if (actions != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, { menu = false }) {
                @Composable fun item(label: String, action: () -> Unit) = DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                item(if (f.favourite) "Remove from favourites" else "Add to favourites") { actions.favourite(f) }
                item("Tags") { actions.dialog = FileDialog.Tags(f) }
                item("Collections") { actions.dialog = FileDialog.Collections(f) }
                item("Rename") { actions.dialog = FileDialog.Rename(f) }
                item("Share") { actions.share(f) }
                if (f.source == "private") item("Move out of private folder") { actions.setPrivate(f, false) } else item("Move to private folder") { actions.setPrivate(f, true) }
                if (f.source != "private") { if (f.folder == uk.jj.pdfplus.extras.Drive.syncedDir().absolutePath) item("Stop syncing this file") { actions.setSynced(f, false) } else item("Keep in sync across devices") { actions.setSynced(f, true) } }
                if (f.lastOpened > 0) item("Remove from recent") { actions.clearRecent(f) }
                item("Delete") { actions.dialog = FileDialog.Delete(f) }
            }
        } else Spacer(Modifier.width(16.dp))
    }
}

sealed class FileDialog {
    class Rename(val f: PdfFile) : FileDialog()
    class Delete(val f: PdfFile) : FileDialog()
    class Tags(val f: PdfFile) : FileDialog()
    class Collections(val f: PdfFile) : FileDialog()
}

class FileActions(private val ctx: android.content.Context, private val scope: kotlinx.coroutines.CoroutineScope) {
    var dialog by mutableStateOf<FileDialog?>(null)
    private fun io(block: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { block() }; Library.changed() } }
    fun favourite(f: PdfFile) = io { App.db.setFavourite(f.path, !f.favourite) }
    fun clearRecent(f: PdfFile) = io { App.db.clearRecent(f.path) }
    fun share(f: PdfFile) = ctx.shareFiles(listOf(File(f.path)))
    fun setPrivate(f: PdfFile, private: Boolean) = scope.launch {
        Reader.sessions.firstOrNull { it.path == f.path }?.let { Reader.closeTab(it) }
        val ok = withContext(Dispatchers.IO) { Library.setPrivate(f, private) }
        ctx.toast(if (!ok) "Could not move the file." else if (private) "Moved to the private folder. Other apps can no longer see it." else "Moved to Documents/PDF+.")
    }

    fun setSynced(f: PdfFile, on: Boolean) = scope.launch {
        Reader.sessions.firstOrNull { it.path == f.path }?.let { Reader.closeTab(it) }
        val ok = withContext(Dispatchers.IO) { Library.moveTo(f, if (on) uk.jj.pdfplus.extras.Drive.syncedDir() else App.outputDir()) }
        ctx.toast(if (!ok) "Could not move the file." else if (on) "Moved to Documents/PDF+/Synced. Turn on file sync in Settings, Google Drive, if it is off." else "Moved to Documents/PDF+. The copy in Drive is left where it is.")
        if (ok && on) uk.jj.pdfplus.extras.Drive.sync(ctx, force = true)
    }

    @Composable
    fun Dialogs() {
        when (val d = dialog) {
            null -> {}
            is FileDialog.Rename -> {
                val f = remember(d) { Field("Name", d.f.name.removeSuffix(".pdf")) }
                FormDialog("Rename", listOf(f), "Rename", onDismiss = { dialog = null }) {
                    dialog = null
                    scope.launch { val ok = withContext(Dispatchers.IO) { Library.rename(d.f, f.value) }; if (!ok) ctx.toast("Could not rename. A file with that name may already exist.") }
                }
            }
            is FileDialog.Delete -> ConfirmDialog("Delete this file?", "${d.f.name} will be deleted from your phone. This cannot be undone.", "Delete", { dialog = null }) {
                scope.launch {
                    Reader.sessions.firstOrNull { it.path == d.f.path }?.let { Reader.closeTab(it) }
                    val ok = withContext(Dispatchers.IO) { Library.delete(d.f) }
                    if (!ok) ctx.toast("Could not delete the file.")
                }
            }
            is FileDialog.Tags -> {
                val f = remember(d) { Field("Tags, separated by commas", d.f.tagList.joinToString(", ")) }
                val all = remember { App.db.allTags() }
                FormDialog("Tags", listOf(f), "Save", if (all.isEmpty()) "" else "Used before: " + all.joinToString(", "), onDismiss = { dialog = null }) {
                    dialog = null; io { App.db.setTags(d.f.path, f.value.split(',').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.distinct()) }
                }
            }
            is FileDialog.Collections -> {
                var tick by remember { mutableIntStateOf(0) }
                val cols = remember(tick) { App.db.collections() }
                val mine = remember(tick) { App.db.collectionsOf(d.f.path) }
                val f = remember(d) { Field("New collection name") }
                FormDialog("Collections", listOf(f), "Done", onDismiss = { dialog = null }, extra = {
                    TextButton(onClick = { if (f.value.isNotBlank()) { val id = App.db.addCollection(f.value.trim()); if (id > 0) App.db.setInCollection(id, d.f.path, true); f.value = ""; tick++ } }) { Text("Create and add") }
                    for (c in cols) Row(Modifier.fillMaxWidth().clickable { App.db.setInCollection(c.id, d.f.path, c.id !in mine); tick++ }.padding(vertical = 8.dp)) {
                        Text(if (c.id in mine) "☑" else "☐", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text(c.name)
                    }
                }) { dialog = null; Library.changed() }
            }
        }
    }
}

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        query, onChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true, placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear") } },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
fun SearchResults(query: String) {
    var names by remember { mutableStateOf<List<PdfFile>>(emptyList()) }
    var hits by remember { mutableStateOf<List<TextHit>>(emptyList()) }
    LaunchedEffect(query, Library.revision.intValue) {
        delay(220)
        withContext(Dispatchers.IO) { val n = App.db.byName(query); val h = if (query.length >= 3) App.db.searchText(query) else emptyList(); names = n; hits = h }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (names.isNotEmpty()) item { SectionTitle("File names") }
        items(names, key = { "n" + it.path }) { f -> FileRow(f, { Reader.open(f.path) }, null) }
        if (hits.isNotEmpty()) item { SectionTitle("Inside documents") }
        items(hits) { h ->
            Column(Modifier.fillMaxWidth().clickable { Reader.open(h.path, h.page) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("${h.name.removeSuffix(".pdf")} · page ${h.page + 1}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(h.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        if (names.isEmpty() && hits.isEmpty()) item { Text("Nothing found for \"$query\".", Modifier.padding(24.dp)) }
        if (Library.status.value.isNotEmpty()) item { Text(Library.status.value + ". Results inside documents fill in as indexing finishes.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
fun HomeTab(needsAccess: Boolean, onGrant: () -> Unit, onUnlockPrivate: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { FileActions(ctx, scope) }
    var query by remember { mutableStateOf("") }
    val rev = Library.revision.intValue
    val recent by produceState(emptyList<PdfFile>(), rev) { value = withContext(Dispatchers.IO) { App.db.recent(40) } }
    val total by produceState(0, rev) { value = withContext(Dispatchers.IO) { App.db.allFiles().size } }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, "Search names and inside documents")
        if (Library.scanning.value) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (query.length >= 2) { SearchResults(query); return@Column }
        if (needsAccess) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Let PDF+ find your PDFs", style = MaterialTheme.typography.titleMedium)
                Text("To list every PDF on this phone, and save your changes back into them, PDF+ needs the \"All files access\" permission. Files stay on your phone.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onGrant) { Text("Allow access") }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (recent.isNotEmpty()) item { SectionTitle("Recent") }
            items(recent, key = { it.path }) { f -> FileRow(f, { Reader.open(f.path) }, actions) }
            if (recent.isEmpty()) item {
                Text(if (total == 0) "No PDFs found yet. Open one from another app, or scan a document from the Tools tab." else "$total PDFs found. Open one from the Files tab and it will appear here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    actions.Dialogs()
}

@Composable
fun FilesTab(privateUnlocked: Boolean, onUnlockPrivate: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { FileActions(ctx, scope) }
    var view by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("name") }
    var folder by remember { mutableStateOf<String?>(null) }
    var collection by remember { mutableStateOf<Collection?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    val rev = Library.revision.intValue
    val files by produceState(emptyList<PdfFile>(), rev, view, collection, tag) {
        value = withContext(Dispatchers.IO) {
            when (view) {
                "fav" -> App.db.favourites()
                "col" -> collection?.let { App.db.collectionFiles(it.id) } ?: emptyList()
                "tag" -> tag?.let { App.db.withTag(it) } ?: emptyList()
                "private" -> App.db.allFiles("private")
                else -> App.db.allFiles()
            }
        }
    }
    val collections by produceState(emptyList<Collection>(), rev, view) { value = withContext(Dispatchers.IO) { App.db.collections() } }
    val tags by produceState(emptyList<String>(), rev, view) { value = withContext(Dispatchers.IO) { App.db.allTags() } }
    val sorted = remember(files, sort) { when (sort) { "date" -> files.sortedByDescending { it.mtime }; "size" -> files.sortedByDescending { it.size }; else -> files } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((k, l) in listOf("all" to "All", "folders" to "Folders", "fav" to "Favourites", "col" to "Collections", "tag" to "Tags", "drive" to "Drive", "private" to "Private"))
                FilterChip(view == k, { view = k; folder = null; collection = null; tag = null; if (k == "private" && !privateUnlocked) onUnlockPrivate() }, { Text(l) })
        }
        if (view == "drive") { DriveFiles(); return@Column }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${sorted.size} file${if (sorted.size == 1) "" else "s"}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((k, l) in listOf("name" to "Name", "date" to "Newest", "size" to "Largest")) TextButton(onClick = { sort = k }) { Text(l, color = if (sort == k) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        when {
            view == "private" && !privateUnlocked -> Column(Modifier.padding(24.dp)) {
                Text("The private folder is locked. Files in it are kept inside PDF+ where other apps cannot see them.")
                TextButton(onClick = onUnlockPrivate) { Text("Unlock") }
            }
            view == "folders" && folder == null -> {
                val groups = remember(sorted) { sorted.groupBy { it.folder }.toSortedMap() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(groups.keys.toList()) { dir ->
                        Row(Modifier.fillMaxWidth().clickable { folder = dir }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(dir.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
                                Text(dir.removePrefix("/storage/emulated/0/"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${groups[dir]?.size ?: 0}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            view == "col" && collection == null -> LazyColumn(Modifier.fillMaxSize()) {
                if (collections.isEmpty()) item { Text("No collections yet. Use the menu on any file to add it to one.", Modifier.padding(24.dp)) }
                items(collections) { c ->
                    Row(Modifier.fillMaxWidth().clickable { collection = c }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, Modifier.weight(1f)); Text("${c.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { App.db.deleteCollection(c.id) }; Library.changed() } }) { Text("Remove") }
                    }
                }
            }
            view == "tag" && tag == null -> LazyColumn(Modifier.fillMaxSize()) {
                if (tags.isEmpty()) item { Text("No tags yet. Use the menu on any file to tag it.", Modifier.padding(24.dp)) }
                items(tags) { t -> Text("#$t", Modifier.fillMaxWidth().clickable { tag = t }.padding(horizontal = 16.dp, vertical = 14.dp)) }
            }
            else -> {
                val shown = if (view == "folders") sorted.filter { it.folder == folder } else sorted
                val back = when { view == "folders" -> folder?.substringAfterLast('/'); view == "col" -> collection?.name; view == "tag" -> tag?.let { "#$it" }; else -> null }
                LazyColumn(Modifier.fillMaxSize()) {
                    if (back != null) item { TextButton(onClick = { folder = null; collection = null; tag = null }, modifier = Modifier.padding(start = 8.dp)) { Text("‹ $back") } }
                    items(shown, key = { it.path }) { f -> FileRow(f, { Reader.open(f.path) }, actions) }
                    if (shown.isEmpty()) item { Text("Nothing here yet.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    actions.Dialogs()
}

/** A simple in-app picker over the library, used by the tools. */
@Composable
fun FilePickerDialog(title: String, multi: Boolean, onDismiss: () -> Unit, onPicked: (List<PdfFile>) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all by produceState(emptyList<PdfFile>()) { value = withContext(Dispatchers.IO) { App.db.recent(30) + App.db.allFiles() }.distinctBy { it.path } }
    val chosen = remember { androidx.compose.runtime.mutableStateListOf<PdfFile>() }
    val shown = remember(all, q) { if (q.isBlank()) all else all.filter { it.name.contains(q, true) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = 32.dp, bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (multi) TextButton(enabled = chosen.isNotEmpty(), onClick = { onPicked(chosen.toList()) }) { Text("Use ${chosen.size}") }
                }
                SearchField(q, { q = it }, "Filter by name")
                if (multi && chosen.isNotEmpty()) Text("Order: " + chosen.joinToString(" → ") { it.name.removeSuffix(".pdf").take(18) }, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (multi) Text(if (f in chosen) "☑" else "☐", Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.primary)
                            Box(Modifier.weight(1f)) { FileRow(f, { if (multi) { if (f in chosen) chosen.remove(f) else chosen.add(f) } else onPicked(listOf(f)) }, null) }
                        }
                    }
                }
            }
        }
    }
}
