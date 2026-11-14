package uk.jj.pdfplus.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.jj.pdfplus.App
import uk.jj.pdfplus.extras.Drive
import uk.jj.pdfplus.extras.DriveItem
import uk.jj.pdfplus.reader.Reader

/** Returns a function that signs in to Google (showing Google's own consent screen when needed). */
@Composable
fun rememberDriveSignIn(onToken: (String?) -> Unit): () -> Unit {
    val activity = LocalContext.current as Activity
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res -> onToken(Drive.fromConsent(activity, res.data)) }
    return { Drive.signIn(activity, { launcher.launch(it) }, onToken) }
}

private fun chosenFolders(): List<Pair<String, String>> = App.prefs.driveFolders.lines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
private fun saveFolders(list: List<Pair<String, String>>) { App.prefs.driveFolders = list.joinToString("\n") { "${it.first}\t${it.second}" } }

@Composable
fun DriveSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = App.prefs
    p.version.value
    var picking by remember { mutableStateOf(false) }
    val signIn = rememberDriveSignIn { t ->
        if (t == null) ctx.toast("Google sign-in did not finish.") else scope.launch { p.driveAccount = Drive.accountName(t); ctx.toast("Connected to Google Drive.") }
    }
    SectionTitle("Google Drive")
    if (!Drive.connected) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open PDFs kept in Google Drive and keep your reading in step between devices. PDF+ can only read the Drive folders you pick, and can only change files it uploaded itself.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = signIn) { Text("Connect") }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Connected as ${p.driveAccount}", Modifier.weight(1f))
        TextButton(onClick = { Drive.signOut() }) { Text("Disconnect") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Drive folders to show")
            Text(chosenFolders().joinToString(", ") { it.second }.ifBlank { "None chosen yet" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { picking = true }) { Text("Choose") }
    }
    for ((label, about, on, set) in listOf(
        Quad("Sync reading positions", "Last page, bookmarks, favourites, tags and a few settings follow you to your other devices. Matched by file name.", p.syncState) { v: Boolean -> p.syncState = v },
        Quad("Sync the Synced folder", "PDFs in Documents/PDF+/Synced are copied to a \"PDF+ Sync\" folder in your Drive and to your other devices. Newest copy wins. Deleting is never synced.", p.syncFiles) { v: Boolean -> p.syncFiles = v },
    )) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label); Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(on, set)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Drive.status.value.ifBlank { if (p.long("lastSync") > 0) "Last synced ${humanDate(p.long("lastSync"))}" else "Not synced yet" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = p.syncState || p.syncFiles, onClick = { Drive.sync(ctx, force = true) { ctx.toast(it) } }) { Text("Sync now") }
    }
    if (picking) FolderPicker(onClose = { picking = false })
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun FolderPicker(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var trail by remember { mutableStateOf(listOf("root" to "My Drive")) }
    var items by remember { mutableStateOf<List<DriveItem>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val chosen = remember(tick) { chosenFolders() }
    LaunchedEffect(trail) {
        items = null
        items = runCatching { Drive.silentToken(ctx)?.let { Drive.folders(it, trail.last().first) } }.getOrNull() ?: emptyList()
    }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { if (trail.size > 1) TextButton(onClick = { trail = trail.dropLast(1) }) { Text("Up") } },
        title = { Text(trail.last().second, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("Tick the folders PDF+ should show. Tap a name to look inside it.", style = MaterialTheme.typography.bodySmall)
                if (items == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(items ?: emptyList()) { f ->
                        val on = chosen.any { it.first == f.id }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on) "☑" else "☐", Modifier.clickable { saveFolders(if (on) chosen.filter { it.first != f.id } else chosen + (f.id to f.name)); tick++ }.padding(10.dp), color = MaterialTheme.colorScheme.primary)
                            Text(f.name, Modifier.weight(1f).clickable { trail = trail + (f.id to f.name) }.padding(vertical = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (items?.isEmpty() == true) item { Text("No folders in here.", Modifier.padding(12.dp)) }
                }
            }
        })
}

/** The Drive view on the Files tab: PDFs in the chosen folders. Tapping one keeps a copy on the phone and opens it. */
@Composable
fun DriveFiles() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<DriveItem>?>(null) }
    var problem by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    val folders = chosenFolders()
    val signIn = rememberDriveSignIn { if (it != null) reload++ }
    LaunchedEffect(reload, App.prefs.driveFolders) {
        if (!Drive.connected || folders.isEmpty()) return@LaunchedEffect
        items = null; problem = ""
        val t = Drive.silentToken(ctx)
        if (t == null) { problem = "Google needs you to sign in again."; items = emptyList(); return@LaunchedEffect }
        items = runCatching { folders.flatMap { Drive.pdfs(t, it.first) }.distinctBy { it.id }.sortedBy { it.name.lowercase() } }.getOrElse { problem = "Could not reach Google Drive."; emptyList() }
    }
    when {
        !Drive.connected -> Column(Modifier.padding(24.dp)) { Text("Connect Google Drive in Settings to see PDFs stored there.") }
        folders.isEmpty() -> Column(Modifier.padding(24.dp)) { Text("Choose which Drive folders to show in Settings, under Google Drive.") }
        else -> Column(Modifier.fillMaxSize()) {
            if (items == null || busy.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (busy.isNotEmpty()) Text(busy, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            if (problem.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(problem, Modifier.weight(1f)); TextButton(onClick = signIn) { Text("Sign in") } }
            LazyColumn(Modifier.fillMaxSize()) {
                items(items ?: emptyList(), key = { it.id }) { f ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = busy.isEmpty()) {
                        scope.launch {
                            busy = "Getting ${f.name} from Drive…"
                            val file = runCatching { Drive.silentToken(ctx)?.let { Drive.fetch(it, f) } }.getOrNull()
                            busy = ""
                            if (file == null) ctx.toast("Could not download that file.") else Reader.open(file.absolutePath)
                        }
                    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("☁", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name.removeSuffix(".pdf"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(humanSize(f.size) + " · " + humanDate(f.modified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (items?.isEmpty() == true && problem.isEmpty()) item { Text("No PDFs in the chosen Drive folders.", Modifier.padding(24.dp)) }
                item { Text("Opening a Drive file keeps a copy in Documents/PDF+/Drive. Changes you make are saved to that copy, not back to Drive.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
