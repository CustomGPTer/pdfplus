package uk.jj.pdfplus.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.jj.pdfplus.App
import uk.jj.pdfplus.BuildConfig
import uk.jj.pdfplus.data.Library
import uk.jj.pdfplus.extras.Ai
import uk.jj.pdfplus.extras.Updates
import uk.jj.pdfplus.sign.DigitalId

@Composable
private fun Toggle(label: String, about: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Line(label: String, about: String = "", action: String = "", onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (about.isNotEmpty()) Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action.isNotEmpty()) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in options) FilterChip(selected == k, { onPick(k) }, { Text(l) })
    }
}

@Composable
fun SettingsTab(onAuthenticate: (String, () -> Unit) -> Unit, onUpdate: (Updates.Release) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = App.prefs
    p.version.value
    var dialog by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var idTick by remember { mutableIntStateOf(0) }
    var pendingP12 by remember { mutableStateOf<ByteArray?>(null) }
    val pickP12 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { ctx.readBytes(it) }?.let { pendingP12 = it; dialog = "importId" } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionTitle("Look")
        ChipRow(THEMES, p.theme) { p.theme = it }
        Spacer(Modifier.height(6.dp))
        ChipRow(listOf("system" to "Follow phone", "light" to "Light", "dark" to "Dark"), p.darkMode) { p.darkMode = it }

        SectionTitle("Reading and editing")
        Toggle("Save changes automatically", "Notes and edits are written into the PDF when you leave it. Turn off to save by hand.", p.autoSave) { p.autoSave = it }
        Toggle("Keep the screen on while reading", "", p.keepAwake) { p.keepAwake = it }
        Toggle("Pen draws, finger moves the page", "Once a stylus has been used, fingers and palms stop drawing.", p.stylusOnlyInk) { p.stylusOnlyInk = it }
        Line("Your name on notes", p.author.ifBlank { "Not set. Notes are added without a name." }, "Change") { dialog = "author" }

        SectionTitle("Library")
        Line("Look for PDFs again", if (Library.scanning.value) "Scanning…" else "Scans the whole phone for PDF files.", "Scan") { Library.scan() }
        Line("Folders to leave out", p.excludedFolders.joinToString(", ") { it.substringAfterLast('/') }.ifBlank { "None" }, "Edit") { dialog = "excluded" }
        Toggle("Search inside documents", "Builds an index of the words in every PDF, once, in the background. " + Library.status.value, p.indexText) { p.indexText = it; if (it) Library.startIndexing() }

        DriveSettings()

        SectionTitle("Security")
        Toggle("Lock PDF+", "Ask for your fingerprint, face or phone PIN when the app opens.", p.appLock) { on ->
            if (on) onAuthenticate("Turn on app lock") { p.appLock = true } else onAuthenticate("Turn off app lock") { p.appLock = false }
        }
        idTick.let {
            if (DigitalId.exists()) Line("Digital ID", "A certificate for digitally signing PDFs is stored in the app.", "Remove") { dialog = "deleteId" }
            else {
                Line("Digital ID", "Needed for certificate signatures. A self-made ID shows as unverified to other people until they choose to trust it.", "Create") { dialog = "createId" }
                Line("", "Already have a .p12 or .pfx file from a certificate provider?", "Import") { pickP12.launch(arrayOf("*/*")) }
            }
        }

        SectionTitle("AI (optional)")
        Toggle("Summaries and questions", "Uses your own account with an AI provider. Off by default. Text is only sent when you press a button in a document.", p.aiEnabled) { p.aiEnabled = it }
        if (p.aiEnabled) {
            ChipRow(Ai.PROVIDERS, p.aiProvider) { p.aiProvider = it; p.aiModel = "" }
            Line("API key", if (p.aiKey.isBlank()) "Not set" else "Saved (ends ${p.aiKey.takeLast(4)})", "Change") { dialog = "aiKey" }
            Line("Model", p.aiModel.ifBlank { "Not chosen" }, "Choose") {
                scope.launch {
                    runCatching { Ai.models(p.aiProvider, p.aiKey) }.onSuccess { models = it; if (it.isEmpty()) ctx.toast("No models were returned for that key.") else dialog = "aiModel" }
                        .onFailure { ctx.toast("Could not list models: ${it.message}") }
                }
            }
        }

        SectionTitle("Updates")
        Toggle("Tell me about new versions", "Checks torusapps.com about once a day. Nothing is installed without you.", p.updateCheck) { p.updateCheck = it }
        Line("Version ${BuildConfig.VERSION_NAME}", "", "Check now") {
            scope.launch { val r = Updates.check(); if (r != null) onUpdate(r) else ctx.toast("You have the latest version, or torusapps.com could not be reached.") }
        }

        SectionTitle("About")
        Line("PDF+ ${BuildConfig.VERSION_NAME}", uk.jj.pdfplus.TorusLinks.DEVELOPER)
        Line("Website", "torusapps.com/pdfplus", "Open") { ctx.openUrl(uk.jj.pdfplus.TorusLinks.PAGE) }
        Line("More apps", "Other free apps by the same developer", "Open") { ctx.openUrl(uk.jj.pdfplus.TorusLinks.HOME) }
        Line("Privacy policy", "torusapps.com/privacy", "Open") { ctx.openUrl(uk.jj.pdfplus.TorusLinks.PRIVACY) }
        Line("Email", uk.jj.pdfplus.TorusLinks.EMAIL, "Write") { ctx.openUrl("mailto:" + uk.jj.pdfplus.TorusLinks.EMAIL) }
        Line("Source code", "github.com/${Updates.REPO}", "Open") { ctx.openUrl("https://github.com/${Updates.REPO}") }
        Text(
            "PDF+ is free software released under the GNU Affero General Public License, version 3. Its source code is published at github.com/${Updates.REPO}.\n\n" +
                "It is built on MuPDF by Artifex Software (AGPL v3), Bouncy Castle (MIT licence), Google ML Kit and the Android Jetpack libraries (Apache 2.0).\n\n" +
                "PDF+ has no adverts and no tracking. Your files stay on your phone unless you choose to share them.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(32.dp))
    }

    val close = { dialog = "" }
    when (dialog) {
        "author" -> { val f = remember { Field("Name", p.author) }; FormDialog("Your name on notes", listOf(f), "Save", onDismiss = close) { p.author = f.value.trim(); close() } }
        "excluded" -> {
            val f = remember { Field("One folder per line", p.excludedFolders.joinToString("\n"), lines = 4) }
            FormDialog("Folders to leave out", listOf(f), "Save", "Type the full path, for example /storage/emulated/0/WhatsApp", onDismiss = close) {
                p.excludedFolders = f.value.lines().map { it.trim().trimEnd('/') }.filter { it.startsWith("/") }.toSet(); close(); Library.scan()
            }
        }
        "aiKey" -> { val f = remember { Field("API key", p.aiKey, password = true) }; FormDialog("AI key", listOf(f), "Save", "The key is kept on this phone only and is sent only to the provider you picked.", onDismiss = close) { p.aiKey = f.value.trim(); close() } }
        "aiModel" -> ChoiceDialog("Model", models, models.indexOf(p.aiModel), close) { p.aiModel = models[it] }
        "createId" -> {
            val n = remember { Field("Full name", p.author) }; val e = remember { Field("Email (optional)") }; val o = remember { Field("Organisation (optional)") }
            val pw = remember { Field("Choose a password for this ID", password = true) }
            FormDialog("Create a digital ID", listOf(n, e, o, pw), "Create", "You will type this password each time you sign. It cannot be recovered.", onDismiss = close) {
                if (n.value.isBlank() || pw.value.length < 4) ctx.toast("Enter your name and a password of at least 4 characters.")
                else { close(); scope.launch { val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { DigitalId.create(n.value.trim(), e.value.trim(), o.value.trim(), pw.value) }; idTick++; ctx.toast(if (ok) "Digital ID created." else "The ID could not be created.") } }
            }
        }
        "importId" -> {
            val pw = remember { Field("Password for the file", password = true) }
            FormDialog("Import a digital ID", listOf(pw), "Import", onDismiss = { pendingP12 = null; close() }) {
                val ok = pendingP12?.let { DigitalId.import(it, pw.value) } == true
                pendingP12 = null; close(); idTick++
                ctx.toast(if (ok) "Digital ID imported." else "That file or password was not accepted.")
            }
        }
        "deleteId" -> ConfirmDialog("Remove the digital ID?", "Documents you have already signed stay signed. You will not be able to sign new ones with this ID.", "Remove", close) { DigitalId.delete(); idTick++ }
    }
}
