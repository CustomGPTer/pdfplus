package uk.jj.pdfplus.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.jj.pdfplus.App
import uk.jj.pdfplus.MuBg
import uk.jj.pdfplus.pdf.PdfDoc
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Finds PDFs on the phone, keeps thumbnails, and builds the text index in the background. */
object Library {
    /** Bumped whenever the file list changes so screens reload. */
    val revision = mutableIntStateOf(0)
    val scanning = mutableStateOf(false)
    val status = mutableStateOf("")

    private var scanJob: Job? = null
    private var indexJob: Job? = null

    fun changed() { revision.intValue++ }

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = App.instance.scope.launch {
            scanning.value = true
            try {
                withContext(Dispatchers.IO) {
                    val found = ArrayList<PdfFile>()
                    if (hasAllFilesAccess()) {
                        val excluded = App.prefs.excludedFolders
                        walk(Environment.getExternalStorageDirectory(), excluded, found, 0)
                    } else {
                        // Without the all-files permission only the app's own output folder is visible.
                        App.outputDir().listFiles()?.filter { it.isFile && it.name.endsWith(".pdf", true) }
                            ?.forEach { found.add(PdfFile(it.absolutePath, it.name, it.length(), it.lastModified())) }
                    }
                    App.db.upsertScanned(found, "phone")
                    val priv = App.privateDir().listFiles()?.filter { it.isFile }?.map { PdfFile(it.absolutePath, it.name, it.length(), it.lastModified()) } ?: emptyList()
                    App.db.upsertScanned(priv, "private")
                }
                changed()
            } finally {
                scanning.value = false
            }
            startIndexing()
        }
    }

    private fun walk(dir: File, excluded: Set<String>, out: ArrayList<PdfFile>, depth: Int) {
        if (depth > 12) return
        val path = dir.absolutePath
        if (excluded.any { path == it || path.startsWith("$it/") }) return
        val name = dir.name
        if (depth > 0 && (name.startsWith(".") || (depth == 1 && name == "Android"))) return
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isDirectory) walk(f, excluded, out, depth + 1)
            else if (f.name.endsWith(".pdf", ignoreCase = true) && f.length() > 0) out.add(PdfFile(f.absolutePath, f.name, f.length(), f.lastModified()))
        }
    }

    /** Page counts first (quick), then full text for search. Pauses politely between files. */
    fun startIndexing() {
        if (indexJob?.isActive == true) return
        indexJob = App.instance.scope.launch {
            while (isActive) {
                val batch = withContext(Dispatchers.IO) { App.db.withoutPageCount(25) }
                if (batch.isEmpty()) break
                for (f in batch) {
                    withContext(MuBg) {
                        val n = runCatching { val d = PdfDoc.open(f.path); try { d.pageCount } finally { d.close() } }.getOrDefault(-1)
                        App.db.setPages(f.path, if (n > 0) n else -1)
                    }
                }
                changed()
            }
            while (isActive && App.prefs.indexText) {
                val batch = withContext(Dispatchers.IO) { App.db.unindexed(5) }
                if (batch.isEmpty()) break
                for (f in batch) {
                    val (done, total) = withContext(Dispatchers.IO) { App.db.indexedCount() }
                    status.value = "Indexing text for search: $done of $total"
                    withContext(MuBg) { indexOne(f) }
                    delay(40)
                }
            }
            status.value = ""
        }
    }

    private fun indexOne(f: PdfFile) {
        val pages = ArrayList<Pair<Int, String>>()
        try {
            if (f.size > 300L * 1024 * 1024) { App.db.setIndexed(f.path); return }
            val d = PdfDoc.open(f.path)
            try {
                val n = minOf(d.pageCount, 1500)
                var chars = 0
                for (i in 0 until n) {
                    val t = d.pageText(i).trim()
                    if (t.isNotEmpty()) { pages.add(i to t); chars += t.length }
                    if (chars > 6_000_000) break
                }
            } finally { d.close() }
        } catch (_: Throwable) {
        }
        App.db.indexPages(f.path, pages)
    }

    // ---- Thumbnails ----

    private fun key(path: String, mtime: Long): String {
        val md = MessageDigest.getInstance("MD5").digest("$path|$mtime".toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun cachedThumb(f: PdfFile): Bitmap? {
        val file = File(App.thumbDir(), key(f.path, f.mtime) + ".jpg")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    suspend fun thumb(f: PdfFile): Bitmap? {
        cachedThumb(f)?.let { return it }
        return withContext(MuBg) {
            runCatching {
                val d = PdfDoc.open(f.path)
                try {
                    val b = d.thumbnail(0, 220)
                    FileOutputStream(File(App.thumbDir(), key(f.path, f.mtime) + ".jpg")).use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    b
                } finally { d.close() }
            }.getOrNull()
        }
    }

    // ---- File operations ----

    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var n = 2
        while (f.exists()) { f = File(dir, "$base ($n)" + if (ext.isNotEmpty()) ".$ext" else ""); n++ }
        return f
    }

    fun register(file: File, source: String = "phone") {
        App.db.ensure(file.absolutePath, file.name, file.length(), file.lastModified(), source)
        changed()
        startIndexing()
    }

    fun rename(f: PdfFile, newName: String): Boolean {
        val clean = newName.trim().replace('/', '-').let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val target = File(File(f.path).parentFile, clean)
        if (target.exists() || !File(f.path).renameTo(target)) return false
        App.db.rename(f.path, target.absolutePath, target.name); changed(); return true
    }

    fun delete(f: PdfFile): Boolean {
        val ok = File(f.path).delete() || !File(f.path).exists()
        if (ok) { App.db.remove(f.path); changed() }
        return ok
    }

    fun moveTo(f: PdfFile, dir: File): Boolean {
        val src = File(f.path)
        val dest = uniqueFile(dir, src.name)
        return try {
            if (!src.renameTo(dest)) { src.copyTo(dest); src.delete() }
            App.db.rename(f.path, dest.absolutePath, dest.name); changed(); true
        } catch (_: Exception) { dest.delete(); false }
    }

    /** Moves a file into the app's private folder (hidden from other apps) or back out to Documents/PDF+. */
    fun setPrivate(f: PdfFile, private: Boolean): Boolean {
        val src = File(f.path)
        val dest = uniqueFile(if (private) App.privateDir() else App.outputDir(), src.name)
        return try {
            src.copyTo(dest); src.delete()
            App.db.rename(f.path, dest.absolutePath, dest.name)
            App.db.setSource(dest.absolutePath, if (private) "private" else "phone")
            changed(); true
        } catch (_: Exception) { dest.delete(); false }
    }
}
