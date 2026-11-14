package uk.jj.pdfplus.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import uk.jj.pdfplus.App

val THEMES = listOf("wallpaper" to "Match my wallpaper", "blue" to "Harbour blue", "green" to "Forest", "plum" to "Plum", "slate" to "Slate", "amber" to "Amber")

private fun preset(seed: Long, dark: Boolean): ColorScheme {
    val p = Color(seed)
    return if (dark) darkColorScheme(
        primary = lerp(p, Color.White, 0.45f), onPrimary = Color(0xFF10131A), primaryContainer = lerp(p, Color.Black, 0.35f), onPrimaryContainer = Color.White,
        secondary = lerp(p, Color.White, 0.6f), secondaryContainer = lerp(p, Color.Black, 0.55f), onSecondaryContainer = Color.White,
        background = Color(0xFF111316), surface = Color(0xFF111316), surfaceVariant = Color(0xFF2A2D33), surfaceContainer = Color(0xFF1B1E22),
        surfaceContainerHigh = Color(0xFF24272C), surfaceContainerLow = Color(0xFF17191D),
    ) else lightColorScheme(
        primary = p, onPrimary = Color.White, primaryContainer = lerp(p, Color.White, 0.82f), onPrimaryContainer = lerp(p, Color.Black, 0.6f),
        secondary = lerp(p, Color.Black, 0.25f), secondaryContainer = lerp(p, Color.White, 0.86f), onSecondaryContainer = lerp(p, Color.Black, 0.6f),
        background = Color(0xFFFBFBFD), surface = Color(0xFFFBFBFD), surfaceVariant = Color(0xFFE6E8EE), surfaceContainer = Color(0xFFF1F2F6),
        surfaceContainerHigh = Color(0xFFEAECF1), surfaceContainerLow = Color(0xFFF6F7FA),
    )
}

private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

@Composable
fun PdfPlusTheme(content: @Composable () -> Unit) {
    val prefs = App.prefs
    val dark = when (prefs.darkMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when (prefs.theme) {
        "blue" -> preset(0xFF1F5FA8, dark)
        "green" -> preset(0xFF2E6B4A, dark)
        "plum" -> preset(0xFF7A3E7E, dark)
        "slate" -> preset(0xFF475569, dark)
        "amber" -> preset(0xFF9A5B00, dark)
        else -> if (android.os.Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) } else preset(0xFF1F5FA8, dark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---- Small shared dialogs ----

class Field(val label: String, initial: String = "", val number: Boolean = false, val password: Boolean = false, val lines: Int = 1) {
    var value by mutableStateOf(initial)
}

@Composable
fun FormDialog(title: String, fields: List<Field>, confirm: String = "OK", note: String = "", onDismiss: () -> Unit, extra: @Composable () -> Unit = {}, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium)
                for (f in fields) OutlinedTextField(
                    value = f.value, onValueChange = { f.value = it }, label = { Text(f.label) }, singleLine = f.lines == 1, minLines = f.lines,
                    keyboardOptions = KeyboardOptions(keyboardType = if (f.number) KeyboardType.Decimal else if (f.password) KeyboardType.Password else KeyboardType.Text),
                    visualTransformation = if (f.password) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth(),
                )
                extra()
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String = "OK", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BusyDialog(message: String, progress: Float?) {
    AlertDialog(
        onDismissRequest = {}, confirmButton = {},
        title = { Text(message) },
        text = {
            Column {
                if (progress != null && progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
        },
    )
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int = -1, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, o ->
                    TextButton(onClick = { onDismiss(); onPick(i) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (i == selected) "●" else "○", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(o, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

val SWATCHES = listOf(0xFFFFD400, 0xFF7CFC6B, 0xFF5BC8FF, 0xFFFF8AD8, 0xFFFF9F43, 0xFFE11D48, 0xFF2563EB, 0xFF16A34A, 0xFF7C3AED, 0xFF111827, 0xFFFFFFFF).map { it.toInt() }

fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.UK, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(java.util.Locale.UK, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

fun humanDate(ms: Long): String = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(ms))
