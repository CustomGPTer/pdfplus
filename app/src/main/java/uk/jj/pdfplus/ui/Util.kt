package uk.jj.pdfplus.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import uk.jj.pdfplus.App
import uk.jj.pdfplus.data.Library
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

fun Context.copyText(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF+", text))
}

fun Context.fileUri(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

fun Context.shareFiles(files: List<File>, mime: String = "application/pdf") {
    if (files.isEmpty()) return
    val uris = ArrayList(files.map { fileUri(it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]) else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.type = mime
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, "Share"))
}

fun Context.shareText(text: String, subject: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject), "Share"))
}

fun Context.openWith(f: File, mime: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        .onFailure { toast("Saved to ${f.parentFile?.name}/${f.name}. No app on this phone opens that type.") }
}

fun Context.openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

fun Context.printPdf(f: File) {
    val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(f.name, object : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (signal?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(f.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, signal: CancellationSignal?, cb: WriteResultCallback) {
            try {
                FileInputStream(f).use { i -> FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) } }
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) { cb.onWriteFailed(e.message) }
        }
    }, null)
}

fun Context.displayName(uri: Uri): String {
    var name = ""
    runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: "" } }
    if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    return name
}

/** Copies a document handed over by another app into [dir] so it can be opened and edited. */
fun Context.importUri(uri: Uri, dir: File, fallbackExt: String = ""): File? = runCatching {
    if (uri.scheme == "file") return@runCatching File(uri.path!!)
    var name = displayName(uri).replace('/', '-')
    if (fallbackExt.isNotEmpty() && !name.contains('.')) name += ".$fallbackExt"
    dir.mkdirs()
    val out = Library.uniqueFile(dir, name)
    contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
    out
}.getOrNull()

fun Context.readBytes(uri: Uri): ByteArray? = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()

fun signatureDir(): File = File(App.instance.filesDir, "signatures").apply { mkdirs() }
