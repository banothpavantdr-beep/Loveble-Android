package com.jarvis.assistant

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * "Ask JARVIS": type or speak a free-form request; an AI model (via the
 * Lovable AI Gateway, behind a small web endpoint) matches it to one of the
 * commands this app supports.
 */
internal object JarvisIntentClient {
    private const val ENDPOINT =
        "https://project--4c5db2de-342e-4efa-99e3-6b818f1bbcff.lovable.app/api/public/jarvis-intent"
    private const val CLIENT_TOKEN = "jarvis-android-v1"

    data class Result(val command: String, val reply: String)

    fun match(text: String, onDone: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-jarvis-client", CLIENT_TOKEN)
                }
                conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
                val ok = conn.responseCode in 200..299
                val body = (if (ok) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (ok && json != null) {
                    Result(json.optString("command", "none"), json.optString("reply", ""))
                } else {
                    Result("none", json?.optString("error")?.ifBlank { null } ?: "JARVIS AI is unavailable.")
                }
            } catch (e: Exception) {
                Result("none", "Couldn't reach JARVIS AI. Check your internet connection.")
            }
            main.post { onDone(result) }
        }.start()
    }
}

@Composable
internal fun AskJarvisCard(
    onLockScreen: () -> Unit,
    onStopListening: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("Type or speak a request.") }
    var busy by remember { mutableStateOf(false) }

    fun submit(request: String) {
        val trimmed = request.trim()
        if (trimmed.isEmpty() || busy) return
        busy = true
        reply = "Thinking…"
        JarvisIntentClient.match(trimmed) { result ->
            busy = false
            reply = result.reply
            when (result.command) {
                "lock_screen" -> onLockScreen()
                "stop_listening" -> onStopListening()
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                text = spoken
                submit(spoken)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, JarvisComponents.PanelBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text("ASK JARVIS (AI)", color = JarvisComponents.SoftCyan, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(500) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("e.g. turn my screen off", fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = JarvisComponents.Cyan,
                    unfocusedBorderColor = JarvisComponents.PanelBorder,
                ),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                enabled = !busy,
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    }
                    runCatching { speechLauncher.launch(intent) }
                        .onFailure { reply = "Voice input isn't available on this phone." }
                },
            ) { Icon(Icons.Default.Mic, contentDescription = "Speak", tint = JarvisComponents.Cyan) }
            IconButton(enabled = !busy, onClick = { submit(text) }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = JarvisComponents.Cyan)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(reply, color = JarvisComponents.Muted, fontSize = 12.sp)
    }
}
