package uk.jj.pdfplus.extras

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.jj.pdfplus.App
import uk.jj.pdfplus.data.Library
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.coroutines.resume

class DriveItem(val id: String, val name: String, val size: Long, val modified: Long, val folder: Boolean)

/**
 * Google Drive: browse PDFs in folders the user picked (read-only), keep reading state in step between
 * devices (hidden app-data file), and mirror the "Synced" folder (files this app uploaded itself).
 */
object Drive {
    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/drive.readonly",
        "https://www.googleapis.com/auth/drive.appdata",
        "https://www.googleapis.com/auth/drive.file",
    )
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FOLDER = "application/vnd.google-apps.folder"
    private const val STATE_FILE = "pdfplus-state.json"
    private const val SYNC_FOLDER = "PDF+ Sync"

    val status = mutableStateOf("")
    private var token: String? = null
    private var tokenAt = 0L
    private var syncing = false

    val connected: Boolean get() = App.prefs.driveAccount.isNotEmpty()
    fun syncedDir(): File = File(App.outputDir(), "Synced").apply { mkdirs() }
    fun driveDir(): File = File(App.outputDir(), "Drive").apply { mkdirs() }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map { Scope(it) }).build()

    /** Returns a token without showing anything, or null if Google needs the user to approve first. */
    suspend fun silentToken(ctx: Context): String? {
        token?.let { if (System.currentTimeMillis() - tokenAt < 40 * 60_000) return it }
        return suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(ctx).authorize(request())
                .addOnSuccessListener { r -> val t = if (r.hasResolution()) null else r.accessToken; if (t != null) { token = t; tokenAt = System.currentTimeMillis() }; if (cont.isActive) cont.resume(t) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }

    /** Starts sign-in. If Google needs a consent screen, [launch] is called with it; otherwise [done] gets the token. */
    fun signIn(activity: Activity, launch: (IntentSenderRequest) -> Unit, done: (String?) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else { token = r.accessToken; tokenAt = System.currentTimeMillis(); done(token) }
            }
            .addOnFailureListener { done(null) }
    }

    fun fromConsent(activity: Activity, data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()?.also { token = it; tokenAt = System.currentTimeMillis() }

    fun signOut() {
        token = null
        App.prefs.driveAccount = ""; App.prefs.driveFolders = ""; App.prefs.syncState = false; App.prefs.syncFiles = false
    }

    // ---- HTTP ----

    private fun open(url: String, t: String, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000; c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $t")
        return c
    }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Google Drive error $code")
        return text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun list(t: String, q: String, spaces: String = "drive"): List<DriveItem> {
        val out = ArrayList<DriveItem>()
        var page: String? = null
        do {
            val url = "$API/files?q=${enc(q)}&spaces=$spaces&pageSize=500&orderBy=name&fields=${enc("nextPageToken,files(id,name,size,modifiedTime,mimeType)")}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val j = JSONObject(read(open(url, t)))
            val a = j.optJSONArray("files") ?: JSONArray()
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                out.add(DriveItem(f.getString("id"), f.optString("name"), f.optString("size", "0").toLongOrNull() ?: 0, runCatching { Instant.parse(f.optString("modifiedTime")).toEpochMilli() }.getOrDefault(0), f.optString("mimeType") == FOLDER))
            }
            page = j.optString("nextPageToken").ifEmpty { null }
        } while (page != null && out.size < 5000)
        return out
    }

    suspend fun accountName(t: String): String = withContext(Dispatchers.IO) {
        runCatching { JSONObject(read(open("$API/about?fields=${enc("user(displayName,emailAddress)")}", t))).getJSONObject("user").let { it.optString("emailAddress").ifEmpty { it.optString("displayName") } } }.getOrDefault("Google account")
    }

    suspend fun folders(t: String, parent: String): List<DriveItem> = withContext(Dispatchers.IO) {
        list(t, "'${esc(parent)}' in parents and mimeType='$FOLDER' and trashed=false")
    }

    /** PDFs inside a chosen folder, including its sub-folders (up to four levels down). */
    suspend fun pdfs(t: String, folderId: String): List<DriveItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<DriveItem>()
        var level = listOf(folderId)
        repeat(4) {
            val next = ArrayList<String>()
            for (id in level) {
                val items = list(t, "'${esc(id)}' in parents and (mimeType='application/pdf' or mimeType='$FOLDER') and trashed=false")
                out.addAll(items.filter { !it.folder }); next.addAll(items.filter { it.folder }.map { it.id })
            }
            level = next
            if (level.isEmpty() || out.size > 4000) return@withContext out
        }
        out
    }

    private fun download(t: String, id: String, dest: File) {
        val c = open("$API/files/$id?alt=media", t)
        if (c.responseCode !in 200..299) throw RuntimeException("Google Drive error ${c.responseCode}")
        val tmp = File(dest.parentFile, dest.name + ".part")
        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    /** Fetches a Drive PDF into Documents/PDF+/Drive (skipped if the same copy is already there). */
    suspend fun fetch(t: String, item: DriveItem): File = withContext(Dispatchers.IO) {
        val dest = File(driveDir(), item.name.replace('/', '-').let { if (it.endsWith(".pdf", true)) it else "$it.pdf" })
        if (!dest.exists() || App.prefs.str("driveCopy:${dest.name}") != "${item.id}:${item.modified}") {
            download(t, item.id, dest)
            App.prefs.set("driveCopy:${dest.name}", "${item.id}:${item.modified}")
        }
        Library.register(dest); dest
    }

    /** Creates ([id] null) or replaces a file's content. Returns the file id. */
    private fun upload(t: String, id: String?, name: String, parent: String?, mime: String, bytes: () -> java.io.InputStream, length: Long): String {
        val start = open(if (id == null) "$UPLOAD/files?uploadType=resumable" else "$UPLOAD/files/$id?uploadType=resumable", t, if (id == null) "POST" else "PATCH")
        start.doOutput = true
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        start.setRequestProperty("X-Upload-Content-Type", mime)
        val meta = JSONObject()
        if (id == null) { meta.put("name", name); if (parent != null) meta.put("parents", JSONArray().put(parent)) }
        start.outputStream.use { it.write(meta.toString().toByteArray()) }
        if (start.responseCode !in 200..299) throw RuntimeException("Google Drive error ${start.responseCode}")
        val session = start.getHeaderField("Location") ?: throw RuntimeException("Google Drive did not start the upload")
        val put = URL(session).openConnection() as HttpURLConnection
        put.requestMethod = "PUT"; put.doOutput = true; put.connectTimeout = 20000; put.readTimeout = 120000
        put.setRequestProperty("Content-Type", mime)
        put.setFixedLengthStreamingMode(length)
        bytes().use { i -> put.outputStream.use { o -> i.copyTo(o, 1 shl 16) } }
        return JSONObject(read(put)).getString("id")
    }

    // ---- Reading state ----

    private fun localState(): JSONObject {
        val files = JSONObject()
        val marks = App.db.allBookmarks().groupBy { it.path }
        for (f in App.db.everyFile()) {
            if (f.source == "private" || (f.lastOpened == 0L && !f.favourite && f.tags.isEmpty() && marks[f.path] == null)) continue
            val o = JSONObject().put("opened", f.lastOpened).put("page", f.lastPage).put("zoom", f.zoom.toDouble()).put("fav", f.favourite)
                .put("tags", f.tags).put("colour", f.colourMode).put("view", f.viewMode)
            val b = JSONArray()
            marks[f.path]?.forEach { b.put(JSONObject().put("page", it.page).put("label", it.label).put("created", it.created)) }
            o.put("marks", b)
            val old = files.optJSONObject(f.name)
            if (old == null || old.optLong("opened") < f.lastOpened) files.put(f.name, o)
        }
        val p = App.prefs
        val settings = JSONObject().put("theme", p.theme).put("dark", p.darkMode).put("viewMode", p.viewMode).put("colourMode", p.colourMode)
            .put("author", p.author).put("ttsRate", p.ttsRate.toDouble()).put("changed", p.long("settingsChanged"))
        return JSONObject().put("files", files).put("settings", settings)
    }

    private fun applyState(remote: JSONObject) {
        val files = remote.optJSONObject("files") ?: JSONObject()
        val byName = App.db.everyFile().filter { it.source != "private" }.groupBy { it.name }
        for (name in files.keys()) {
            val r = files.getJSONObject(name)
            for (f in byName[name] ?: continue) {
                if (r.optLong("opened") > f.lastOpened) {
                    App.db.savePosition(f.path, r.optInt("page"), r.optDouble("zoom", 1.0).toFloat())
                    App.db.setFavourite(f.path, r.optBoolean("fav"))
                    App.db.setTags(f.path, r.optString("tags").split(',').filter { it.isNotBlank() })
                    App.db.saveViewPrefs(f.path, r.optString("colour"), r.optInt("view", -1))
                    App.db.setOpened(f.path, r.optLong("opened"))
                }
                val have = App.db.bookmarks(f.path).map { it.page }.toSet()
                val m = r.optJSONArray("marks") ?: continue
                for (i in 0 until m.length()) { val b = m.getJSONObject(i); if (b.optInt("page") !in have) App.db.addBookmark(f.path, b.optInt("page"), b.optString("label"), b.optLong("created")) }
            }
        }
        val s = remote.optJSONObject("settings") ?: return
        val p = App.prefs
        if (s.optLong("changed") > p.long("settingsChanged")) {
            p.theme = s.optString("theme", p.theme); p.darkMode = s.optString("dark", p.darkMode); p.viewMode = s.optInt("viewMode", p.viewMode)
            p.colourMode = s.optString("colourMode", p.colourMode); p.author = s.optString("author", p.author); p.ttsRate = s.optDouble("ttsRate", 1.0).toFloat()
            p.set("settingsChanged", s.optLong("changed"))
        }
    }

    private fun syncState(t: String) {
        val existing = list(t, "name='$STATE_FILE'", "appDataFolder").firstOrNull()
        if (existing != null) runCatching { applyState(JSONObject(read(open("$API/files/${existing.id}?alt=media", t)))) }
        val body = localState().toString().toByteArray()
        upload(t, existing?.id, STATE_FILE, "appDataFolder", "application/json", { body.inputStream() }, body.size.toLong())
    }

    // ---- Files ----

    private fun syncFiles(t: String): Int {
        var folder = list(t, "name='${esc(SYNC_FOLDER)}' and mimeType='$FOLDER' and trashed=false").firstOrNull()?.id
        if (folder == null) {
            val c = open("$API/files", t, "POST"); c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(JSONObject().put("name", SYNC_FOLDER).put("mimeType", FOLDER).toString().toByteArray()) }
            folder = JSONObject(read(c)).getString("id")
        }
        val remote = list(t, "'$folder' in parents and mimeType='application/pdf' and trashed=false").associateBy { it.name }
        val local = (syncedDir().listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".pdf", true) }.associateBy { it.name }
        var moved = 0
        for ((name, f) in local) {
            val r = remote[name]
            when {
                r == null || f.lastModified() > r.modified + 3000 -> {
                    status.value = "Uploading $name"
                    val id = upload(t, r?.id, name, folder, "application/pdf", { f.inputStream() }, f.length())
                    // adopt Drive's timestamp so the two copies read as identical next time
                    runCatching { JSONObject(read(open("$API/files/$id?fields=modifiedTime", t))).optString("modifiedTime").let { f.setLastModified(Instant.parse(it).toEpochMilli()) } }
                    moved++
                }
                r.modified > f.lastModified() + 3000 -> { status.value = "Downloading $name"; download(t, r.id, f); f.setLastModified(r.modified); Library.register(f); moved++ }
            }
        }
        for ((name, r) in remote) if (name !in local) {
            status.value = "Downloading $name"
            val f = File(syncedDir(), name); download(t, r.id, f); f.setLastModified(r.modified); Library.register(f); moved++
        }
        return moved
    }

    /** Runs whichever kinds of sync are switched on. Quietly does nothing if Google needs the user first. */
    fun sync(ctx: Context, force: Boolean = false, report: (String) -> Unit = {}) {
        val p = App.prefs
        if (!connected || (!p.syncState && !p.syncFiles) || syncing) return
        if (!force && System.currentTimeMillis() - p.long("lastSync") < 10 * 60_000) return
        syncing = true
        App.instance.scope.launch {
            try {
                val t = silentToken(ctx)
                if (t == null) { report("Google needs you to sign in again. Open Settings, Google Drive."); return@launch }
                val moved = withContext(Dispatchers.IO) {
                    if (p.syncState) { status.value = "Syncing reading positions"; syncState(t) }
                    if (p.syncFiles) syncFiles(t) else 0
                }
                p.set("lastSync", System.currentTimeMillis())
                Library.changed()
                report(if (moved > 0) "Sync finished: $moved file(s) updated." else "Everything is in step.")
            } catch (e: Throwable) {
                report("Sync did not finish: ${e.message ?: "no connection"}")
            } finally { status.value = ""; syncing = false }
        }
    }
}
