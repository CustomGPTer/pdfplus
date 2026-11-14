package uk.jj.pdfplus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.pdfplus.data.Library
import uk.jj.pdfplus.extras.Speaker
import uk.jj.pdfplus.extras.Updates
import uk.jj.pdfplus.reader.Reader
import uk.jj.pdfplus.reader.ReaderScreen
import uk.jj.pdfplus.ui.FilesTab
import uk.jj.pdfplus.ui.HomeTab
import uk.jj.pdfplus.ui.PdfPlusTheme
import uk.jj.pdfplus.ui.SettingsTab
import uk.jj.pdfplus.ui.ToolsTab
import uk.jj.pdfplus.ui.importUri
import uk.jj.pdfplus.ui.openUrl
import uk.jj.pdfplus.ui.toast
import java.io.File

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(false)
    private var privateUnlocked by mutableStateOf(false)
    private var hasAccess by mutableStateOf(false)
    private var update by mutableStateOf<Updates.Release?>(null)
    private var leftAt = 0L
    private lateinit var speaker: Speaker

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        speaker = Speaker(this, App.instance.scope)
        locked = App.prefs.appLock
        if (savedInstanceState == null) handleIntent(intent)
        setContent { PdfPlusTheme { Root() } }
        if (locked) unlock()
        maybeCheckUpdate()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onResume() {
        super.onResume()
        val access = Environment.isExternalStorageManager()
        if (access != hasAccess || access) { hasAccess = access; Library.scan() }
        uk.jj.pdfplus.extras.Drive.sync(this)
        if (App.prefs.appLock && leftAt > 0 && System.currentTimeMillis() - leftAt > 60_000 && !locked) { locked = true; privateUnlocked = false; unlock() }
    }

    override fun onPause() { super.onPause(); leftAt = System.currentTimeMillis(); Reader.saveAll(); uk.jj.pdfplus.extras.Drive.sync(this) }
    override fun onDestroy() { super.onDestroy(); speaker.shutdown() }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        uri ?: return
        App.instance.scope.launch {
            val file = withContext(Dispatchers.IO) { resolve(uri) }
            if (file == null) toast("That file could not be opened.") else { Library.register(file); Reader.open(file.absolutePath) }
        }
    }

    /** Uses the real file when it can be reached directly (so edits save in place); otherwise keeps a copy in Documents/PDF+/Opened. */
    private fun resolve(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.canRead() }
        if (Environment.isExternalStorageManager()) runCatching {
            contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { p -> File(p).takeIf { it.canRead() && it.canWrite() }?.let { return it } }
            }
        }
        return importUri(uri, File(App.outputDir(), "Opened"), "pdf")
    }

    private fun authenticate(title: String, onOk: () -> Unit) {
        val kinds = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(kinds) != BiometricManager.BIOMETRIC_SUCCESS) { toast("Set up a screen lock on this phone first."); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(kinds).build())
    }

    private fun unlock() = authenticate("Unlock PDF+") { locked = false }

    private fun maybeCheckUpdate() {
        val p = App.prefs
        if (!p.updateCheck || System.currentTimeMillis() - p.long("lastUpdateCheck") < 20L * 3600_000) return
        App.instance.scope.launch { p.set("lastUpdateCheck", System.currentTimeMillis()); Updates.check()?.let { if (p.str("skippedVersion") != it.version) update = it } }
    }

    @Composable
    private fun Root() {
        val current = Reader.current
        LaunchedEffect(current != null, App.prefs.keepAwake) {
            if (current != null && App.prefs.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                locked -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Text("PDF+ is locked", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { unlock() }) { Text("Unlock") }
                }
                current != null -> ReaderScreen(current, speaker) { Reader.hide() }
                else -> Home()
            }
        }
        update?.let { r ->
            AlertDialog(onDismissRequest = { update = null }, title = { Text("PDF+ ${r.version} is available") },
                text = { Text(r.notes.ifBlank { "A newer version is ready to download." } + "\n\nThe download opens in your browser. Install it over the top and your settings are kept.") },
                confirmButton = { TextButton(onClick = { update = null; openUrl(r.apkUrl.ifBlank { r.pageUrl }) }) { Text("Download") } },
                dismissButton = { Row { TextButton(onClick = { App.prefs.set("skippedVersion", r.version); update = null }) { Text("Skip this version") }; TextButton(onClick = { update = null }) { Text("Later") } } })
        }
    }

    @Composable
    private fun Home() {
        var tab by remember { mutableIntStateOf(0) }
        val tabs = listOf("Home" to Icons.Default.Home, "Files" to Icons.Default.Menu, "Tools" to Icons.Default.Build, "Settings" to Icons.Default.Settings)
        val grant = {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
            Unit
        }
        val body: @Composable () -> Unit = {
            when (tab) {
                0 -> HomeTab(!hasAccess, grant) {}
                1 -> FilesTab(privateUnlocked) { authenticate("Open the private folder") { privateUnlocked = true } }
                2 -> ToolsTab()
                else -> SettingsTab({ title, ok -> authenticate(title, ok) }) { update = it }
            }
        }
        BoxWithConstraints {
            if (maxWidth >= 600.dp) Row(Modifier.fillMaxSize()) {
                NavigationRail { tabs.forEachIndexed { i, (l, ic) -> NavigationRailItem(tab == i, { tab = i }, icon = { Icon(ic, l) }, label = { Text(l) }) } }
                Column(Modifier.fillMaxSize().statusBarsPadding()) { OpenTabs(); body() }
            } else Scaffold(bottomBar = { NavigationBar { tabs.forEachIndexed { i, (l, ic) -> NavigationBarItem(tab == i, { tab = i }, icon = { Icon(ic, l) }, label = { Text(l) }) } } }) { pad ->
                Column(Modifier.fillMaxSize().padding(pad)) { OpenTabs(); body() }
            }
        }
    }

    /** Documents still open in the background: one tap goes back to them. */
    @Composable
    private fun OpenTabs() {
        if (Reader.sessions.isEmpty()) return
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open:", style = MaterialTheme.typography.labelMedium)
            for (s in Reader.sessions) TextButton(onClick = { Reader.current = s }) { Text(s.name.removeSuffix(".pdf").take(18), maxLines = 1) }
        }
    }
}
