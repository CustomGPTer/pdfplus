package uk.jj.pdfplus

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import uk.jj.pdfplus.data.Db
import java.io.File
import java.util.concurrent.Executors

/** Thread that owns every open document shown in the reader. MuPDF documents are not re-entrant. */
val Mu = Executors.newSingleThreadExecutor { Thread(it, "mu-reader").apply { priority = Thread.NORM_PRIORITY + 1 } }.asCoroutineDispatcher()

/** Thread for library work (thumbnails, text index, file tools). Opens its own document handles. */
val MuBg = Executors.newSingleThreadExecutor { Thread(it, "mu-background").apply { priority = Thread.MIN_PRIORITY + 1 } }.asCoroutineDispatcher()

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(getSharedPreferences("settings", Context.MODE_PRIVATE))
        db = Db(this)
        File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
    }

    companion object {
        lateinit var instance: App
        lateinit var prefs: Prefs
        lateinit var db: Db

        fun workFile(name: String): File = File(File(instance.cacheDir, "work").apply { mkdirs() }, name)
        fun thumbDir(): File = File(instance.cacheDir, "thumbs").apply { mkdirs() }
        fun privateDir(): File = File(instance.filesDir, "private").apply { mkdirs() }
        fun outputDir(): File {
            val d = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "PDF+")
            if (!d.exists() && !d.mkdirs()) return File(instance.getExternalFilesDir(null), "PDF+").apply { mkdirs() }
            return d
        }
    }
}

/** Settings. Every value is observable so Compose screens update when one changes. */
class Prefs(private val sp: SharedPreferences) {
    val version = mutableStateOf(0)

    private fun touch() { version.value++ }
    fun str(k: String, d: String = ""): String { version.value; return sp.getString(k, d) ?: d }
    fun int(k: String, d: Int = 0): Int { version.value; return sp.getInt(k, d) }
    fun bool(k: String, d: Boolean = false): Boolean { version.value; return sp.getBoolean(k, d) }
    fun float(k: String, d: Float = 0f): Float { version.value; return sp.getFloat(k, d) }
    fun long(k: String, d: Long = 0L): Long { version.value; return sp.getLong(k, d) }
    private val synced = setOf("theme", "dark", "viewMode", "colourMode", "author", "ttsRate")
    private fun stamp(k: String) { if (k in synced) sp.edit().putLong("settingsChanged", System.currentTimeMillis()).apply() }
    fun set(k: String, v: String) { sp.edit().putString(k, v).apply(); stamp(k); touch() }
    fun set(k: String, v: Int) { sp.edit().putInt(k, v).apply(); stamp(k); touch() }
    fun set(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply(); touch() }
    fun set(k: String, v: Float) { sp.edit().putFloat(k, v).apply(); stamp(k); touch() }
    fun set(k: String, v: Long) { sp.edit().putLong(k, v).apply(); touch() }
    fun all(): Map<String, *> = sp.all
    fun remove(k: String) { sp.edit().remove(k).apply(); touch() }

    // Named settings
    var theme: String get() = str("theme", "wallpaper"); set(v) = set("theme", v)
    var darkMode: String get() = str("dark", "system"); set(v) = set("dark", v)
    var viewMode: Int get() = int("viewMode", 0); set(v) = set("viewMode", v)
    var colourMode: String get() = str("colourMode", "day"); set(v) = set("colourMode", v)
    var customPaper: Int get() = int("customPaper", 0xFFF4ECD8.toInt()); set(v) = set("customPaper", v)
    var customInk: Int get() = int("customInk", 0xFF3B2F1E.toInt()); set(v) = set("customInk", v)
    var keepAwake: Boolean get() = bool("keepAwake", true); set(v) = set("keepAwake", v)
    var autoSave: Boolean get() = bool("autoSave", true); set(v) = set("autoSave", v)
    var author: String get() = str("author", ""); set(v) = set("author", v)
    var excludedFolders: Set<String>
        get() = str("excluded", "").split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = set("excluded", v.joinToString("\n"))
    var appLock: Boolean get() = bool("appLock", false); set(v) = set("appLock", v)
    var indexText: Boolean get() = bool("indexText", true); set(v) = set("indexText", v)
    var ttsVoice: String get() = str("ttsVoice", ""); set(v) = set("ttsVoice", v)
    var ttsRate: Float get() = float("ttsRate", 1f); set(v) = set("ttsRate", v)
    var aiEnabled: Boolean get() = bool("aiEnabled", false); set(v) = set("aiEnabled", v)
    var aiProvider: String get() = str("aiProvider", "openai"); set(v) = set("aiProvider", v)
    var aiKey: String get() = str("aiKey", ""); set(v) = set("aiKey", v)
    var aiModel: String get() = str("aiModel", ""); set(v) = set("aiModel", v)
    var driveAccount: String get() = str("driveAccount", ""); set(v) = set("driveAccount", v)
    var driveFolders: String get() = str("driveFolders", ""); set(v) = set("driveFolders", v)
    var syncState: Boolean get() = bool("syncState", false); set(v) = set("syncState", v)
    var syncFiles: Boolean get() = bool("syncFiles", false); set(v) = set("syncFiles", v)
    var updateCheck: Boolean get() = bool("updateCheck", true); set(v) = set("updateCheck", v)
    var stylusOnlyInk: Boolean get() = bool("stylusOnlyInk", true); set(v) = set("stylusOnlyInk", v)
}
