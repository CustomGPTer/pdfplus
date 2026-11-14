package uk.jj.pdfplus.extras

import android.content.Context
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.artifex.mupdf.fitz.Font
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.jj.pdfplus.App
import uk.jj.pdfplus.BuildConfig
import uk.jj.pdfplus.MuBg
import uk.jj.pdfplus.pdf.Para
import uk.jj.pdfplus.pdf.PdfDoc
import uk.jj.pdfplus.pdf.PdfTools
import uk.jj.pdfplus.reader.Session
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max

// ---------------- Read aloud ----------------

class VoiceItem(val voice: Voice, val label: String)

class Speaker(ctx: Context, private val scope: CoroutineScope) {
    var ready by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var session: Session? = null
    private var page = 0
    private var index = 0
    private var paras: List<Para> = emptyList()
    private var stopped = true

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) applySettings()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { if (!stopped) { index++; speakCurrent() } } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { scope.launch { speaking = false } }
        })
    }

    fun voices(): List<VoiceItem> {
        val all = runCatching { tts.voices?.toList() }.getOrNull() ?: return emptyList()
        val usable = all.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val out = ArrayList<VoiceItem>()
        usable.groupBy { it.locale }.toSortedMap(compareBy { it.displayName }).forEach { (locale, list) ->
            list.sortedBy { it.name }.forEachIndexed { n, v ->
                out.add(VoiceItem(v, "${locale.getDisplayName(Locale.UK)} · Voice ${n + 1}" + if (v.isNetworkConnectionRequired) " (needs internet)" else ""))
            }
        }
        val mine = Locale.getDefault().language
        return out.sortedBy { if (it.voice.locale.language == mine) 0 else 1 }
    }

    fun applySettings() {
        val name = App.prefs.ttsVoice
        if (name.isNotEmpty()) runCatching { tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
        tts.setSpeechRate(App.prefs.ttsRate)
    }

    fun sample() { tts.speak("This is how this voice sounds when it reads your document.", TextToSpeech.QUEUE_FLUSH, null, "sample") }

    fun start(s: Session, fromPage: Int) {
        session = s; page = fromPage; index = 0; stopped = false; speaking = true
        scope.launch { loadPage(); speakCurrent() }
    }

    private suspend fun loadPage() { paras = session?.read { it.paragraphs(page) } ?: emptyList() }

    private suspend fun speakCurrent() {
        val s = session ?: return stop()
        var guard = 0
        while (index >= paras.size) {
            page++; index = 0
            if (page >= s.pageCount || guard++ > 400) return stop()
            loadPage()
        }
        while (index < 0) {
            page--; if (page < 0) { page = 0; index = 0; loadPage(); break }
            loadPage(); index = max(0, paras.size - 1)
        }
        if (paras.isEmpty()) return stop()
        val p = paras[index]
        s.view?.speakRect = page to RectF(p.rect)
        if (s.page != page) s.view?.scrollToPage(page, yPoints = p.rect.top)
        tts.speak(p.text.take(3900), TextToSpeech.QUEUE_FLUSH, null, "p$page-$index")
        speaking = true
    }

    fun pause() { stopped = true; speaking = false; tts.stop() }
    fun resume() { stopped = false; scope.launch { speakCurrent() } }
    fun skip(delta: Int) { tts.stop(); index += delta; stopped = false; scope.launch { speakCurrent() } }
    fun stop() { stopped = true; speaking = false; tts.stop(); session?.view?.speakRect = null; session = null }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}

// ---------------- OCR ----------------

object Ocr {
    private suspend fun recognise(bmp: android.graphics.Bitmap): List<Triple<String, android.graphics.Rect, Unit>> =
        suspendCancellableCoroutine { cont ->
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            client.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text ->
                    val out = ArrayList<Triple<String, android.graphics.Rect, Unit>>()
                    for (b in text.textBlocks) for (l in b.lines) l.boundingBox?.let { out.add(Triple(l.text, it, Unit)) }
                    client.close(); if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener { client.close(); if (cont.isActive) cont.resume(emptyList()) }
        }

    /** Adds a hidden text layer to pages that have no text. Returns the number of pages recognised. */
    suspend fun makeSearchable(input: String, out: String, progress: (Int, Int) -> Unit): Int = withContext(MuBg) {
        val reader = PdfDoc.open(input)
        val pdf = PdfTools.openPdf(input)
        val font = Font("Helvetica")
        var done = 0
        try {
            val fo = pdf.addSimpleFont(font, 0)
            for (i in 0 until reader.pageCount) {
                progress(i, reader.pageCount)
                if (reader.pageText(i).trim().length > 20) continue
                reader.measureMore(i, 1)
                val s = reader.sizes[i]
                val scale = (2200f / max(s[0], s[1])).coerceIn(1f, 4f)
                val bmp = reader.render(i, scale, 0, 0, (s[0] * scale).toInt(), (s[1] * scale).toInt(), false)
                val lines = recognise(bmp).map { (t, r, _) -> PdfTools.OcrLine(t, r.left / scale + s[2], r.top / scale + s[3], r.width() / scale, r.height() / scale) }
                bmp.recycle()
                if (lines.isNotEmpty()) { PdfTools.addTextLayer(pdf, i, lines, font, fo); done++ }
            }
            pdf.save(out, "garbage=2,compress")
        } finally { font.destroy(); pdf.destroy(); reader.close() }
        done
    }
}

// ---------------- AI with the user's own key ----------------

object Ai {
    val PROVIDERS = listOf("openai" to "OpenAI", "anthropic" to "Anthropic", "gemini" to "Google Gemini")

    private fun http(url: String, headers: Map<String, String>, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 120000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw RuntimeException(if (!msg.isNullOrBlank()) msg else "The AI service returned error $code")
        }
        return text
    }

    private fun auth(provider: String, key: String): Map<String, String> = when (provider) {
        "anthropic" -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        "gemini" -> mapOf("x-goog-api-key" to key)
        else -> mapOf("Authorization" to "Bearer $key")
    }

    suspend fun models(provider: String, key: String): List<String> = withContext(Dispatchers.IO) {
        when (provider) {
            "anthropic" -> JSONObject(http("https://api.anthropic.com/v1/models?limit=100", auth(provider, key), null)).getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
            "gemini" -> JSONObject(http("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", auth(provider, key), null)).getJSONArray("models").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }.map { it.getString("name").removePrefix("models/") }
            }
            else -> JSONObject(http("https://api.openai.com/v1/models", auth(provider, key), null)).getJSONArray("data").let { a ->
                (0 until a.length()).map { a.getJSONObject(it).getString("id") }.filter { it.startsWith("gpt") || it.startsWith("o") || it.startsWith("chatgpt") }.sorted()
            }
        }
    }

    suspend fun ask(system: String, user: String): String = withContext(Dispatchers.IO) {
        val p = App.prefs.aiProvider; val key = App.prefs.aiKey; val model = App.prefs.aiModel
        if (key.isBlank() || model.isBlank()) throw RuntimeException("Add your AI key and pick a model in Settings first.")
        when (p) {
            "anthropic" -> {
                val body = JSONObject().put("model", model).put("max_tokens", 2500).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                val r = JSONObject(http("https://api.anthropic.com/v1/messages", auth(p, key), body.toString())).getJSONArray("content")
                (0 until r.length()).joinToString("") { r.getJSONObject(it).optString("text") }
            }
            "gemini" -> {
                val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                val parts = JSONObject(http("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", auth(p, key), body.toString()))
                    .getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }
            else -> {
                val body = JSONObject().put("model", model).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
                JSONObject(http("https://api.openai.com/v1/chat/completions", auth(p, key), body.toString()))
                    .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }
    }

    const val SYSTEM = "You help someone understand a PDF document. Answer only from the document text provided. " +
        "If the answer is not in the text, say so. Be concise and use plain language. Mention page numbers where you can."

    /** Collects the document text with page markers, capped so it fits in one request. */
    suspend fun documentText(s: Session, onlyPage: Int = -1, cap: Int = 120_000): Pair<String, Boolean> {
        val sb = StringBuilder(); var cut = false
        val range = if (onlyPage >= 0) onlyPage..onlyPage else 0 until s.pageCount
        for (i in range) {
            val t = s.read { it.pageText(i) }.orEmpty().trim()
            if (t.isEmpty()) continue
            if (sb.length + t.length > cap) { cut = true; break }
            sb.append("\n[Page ").append(i + 1).append("]\n").append(t).append('\n')
        }
        return sb.toString() to cut
    }
}

// ---------------- New-version check ----------------

object Updates {
    /** The public repository whose latest release is treated as the newest version. */
    const val REPO = "CustomGPTer/pdfplus"

    class Release(val version: String, val pageUrl: String, val apkUrl: String, val notes: String)

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until max(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return@runCatching null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@runCatching null
            val assets = j.optJSONArray("assets")
            var apk = ""
            if (assets != null) for (i in 0 until assets.length()) { val a = assets.getJSONObject(i); if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url") }
            Release(tag.removePrefix("v"), j.optString("html_url"), apk, j.optString("body").take(1200))
        }.getOrNull()
    }
}
