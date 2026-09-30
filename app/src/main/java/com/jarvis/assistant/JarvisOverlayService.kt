package com.jarvis.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.Locale
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Foreground service that keeps JARVIS listening for the wake word
 * ("Jarvis" / "Hey Jarvis") while the user is in other apps.
 *
 * - Started only when the user taps ACTIVATE JARVIS (app in foreground).
 * - Shows a persistent notification with a Deactivate action.
 * - Uses the offline Vosk engine (silent AudioRecord); nothing is recorded or uploaded.
 * - On wake word: shows the activation popup over the current app, speaks
 *   "Yes, I'm listening.", then listens for the actual command.
 */
class JarvisOverlayService : Service() {

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL_ID = "jarvis_voice_control"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.jarvis.assistant.action.START_LISTENING"
        private const val ACTION_STOP = "com.jarvis.assistant.action.STOP_LISTENING"
        private const val SAMPLE_RATE = 16000f
        private const val COMMAND_TIMEOUT_MS = 8000L
        private const val VOSK_GRAMMAR =
            "[\"hey jarvis\", \"jarvis\", \"screen off\", \"screen lock\", " +
                "\"lock my phone\", \"lock my screen\", \"phone lock\", \"[unk]\"]"

        /** Observable by the UI so the button reflects the real state. */
        val isActive = mutableStateOf(false)

        fun start(context: Context) {
            val intent = Intent(context, JarvisOverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisOverlayService::class.java))
            isActive.value = false
        }
    }

    private enum class VoiceMode { IDLE, WAKE_WORD, ACTIVATING, COMMAND }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var isModelLoading = false
    private var voiceMode = VoiceMode.IDLE
    private var textToSpeech: TextToSpeech? = null
    private var isTextToSpeechReady = false
    private var pendingSpeechCompletion: (() -> Unit)? = null

    private var overlayView: ComposeView? = null
    private var overlayOwner: OverlayLifecycleOwner? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            // null intent = system restart; microphone services may not be
            // restarted from the background, so wait for the user to activate again.
            stopListeningAndSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopListeningAndSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground listening", e)
            stopListeningAndSelf()
            return START_NOT_STICKY
        }

        isActive.value = true
        if (textToSpeech == null) initializeTextToSpeech()
        if (voiceMode == VoiceMode.IDLE) startWakeWordRecognition()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseVoice()
        pendingSpeechCompletion = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        voskModel?.close()
        voskModel = null
        isActive.value = false
        super.onDestroy()
    }

    private fun stopListeningAndSelf() {
        releaseVoice()
        isActive.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releaseVoice() {
        mainHandler.removeCallbacksAndMessages(null)
        voiceMode = VoiceMode.IDLE
        hideOverlay()
        releaseRecognizer()
    }

    // ---------- Speech recognition (offline Vosk, AudioRecord) ----------

    /**
     * BEEP FIX: android.speech.SpeechRecognizer.startListening() hands the
     * microphone to Google's recognition service, which plays its own
     * start/stop earcon every session. The wake-word loop restarted it every
     * few hundred milliseconds, so the chime repeated constantly.
     *
     * JARVIS now listens with the offline Vosk engine, which reads the mic
     * directly through AudioRecord. AudioRecord never plays any sound, and a
     * single continuous session is used (no restart loop), so there is no
     * beep. Exactly one engine (this SpeechService) owns the microphone.
     */
    private fun startWakeWordRecognition() {
        mainHandler.removeCallbacksAndMessages(null)
        voiceMode = VoiceMode.WAKE_WORD
        hideOverlay()
        val model = voskModel
        if (model == null) {
            loadModelThenListen()
            return
        }
        startVoskService(model)
        speechService?.setPause(false)
    }

    private fun loadModelThenListen() {
        if (isModelLoading) return
        isModelLoading = true
        StorageService.unpack(
            this,
            "model-en-us",
            "model",
            { model ->
                isModelLoading = false
                voskModel = model
                if (voiceMode == VoiceMode.WAKE_WORD) startWakeWordRecognition()
            },
            { e ->
                isModelLoading = false
                Log.w(TAG, "Unable to load offline speech model", e)
                stopListeningAndSelf()
            },
        )
    }

    private fun startVoskService(model: Model) {
        if (speechService != null) return
        try {
            val rec = Recognizer(model, SAMPLE_RATE, VOSK_GRAMMAR)
            recognizer = rec
            speechService = SpeechService(rec, SAMPLE_RATE).also {
                it.startListening(voskListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Offline recognizer failed to start", e)
            releaseRecognizer()
            mainHandler.postDelayed(
                { if (voiceMode == VoiceMode.WAKE_WORD) startWakeWordRecognition() },
                1000L,
            )
        }
    }

    private fun releaseRecognizer() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        recognizer?.close()
        recognizer = null
    }

    private fun startCommandRecognition() {
        if (voiceMode != VoiceMode.ACTIVATING) return
        voiceMode = VoiceMode.COMMAND
        recognizer?.reset()
        speechService?.setPause(false)
        // Same timeout behaviour as before: no command -> back to wake word.
        mainHandler.postDelayed(
            {
                if (voiceMode == VoiceMode.COMMAND) {
                    voiceMode = VoiceMode.WAKE_WORD
                    hideOverlay()
                }
            },
            COMMAND_TIMEOUT_MS,
        )
    }

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = Unit
        override fun onFinalResult(hypothesis: String?) = Unit
        override fun onTimeout() = Unit

        override fun onError(exception: Exception?) {
            Log.w(TAG, "Offline recognizer error", exception)
            mainHandler.post {
                releaseRecognizer()
                if (voiceMode != VoiceMode.IDLE) {
                    voiceMode = VoiceMode.WAKE_WORD
                    hideOverlay()
                    mainHandler.postDelayed(
                        { if (voiceMode == VoiceMode.WAKE_WORD) startWakeWordRecognition() },
                        1000L,
                    )
                }
            }
        }

        override fun onResult(hypothesis: String?) {
            val phrase = try {
                org.json.JSONObject(hypothesis ?: return).optString("text")
            } catch (e: Exception) {
                return
            }
            if (phrase.isBlank()) return
            mainHandler.post { handlePhrase(phrase) }
        }
    }

    private fun handlePhrase(phrase: String) {
        when (voiceMode) {
            VoiceMode.WAKE_WORD -> if (isWakeWord(phrase)) {
                voiceMode = VoiceMode.ACTIVATING
                // Stop hearing JARVIS's own reply; the wake word itself is
                // never treated as a command.
                speechService?.setPause(true)
                showOverlay()
                speakActivationMessage(::startCommandRecognition)
            }
            VoiceMode.COMMAND -> {
                mainHandler.removeCallbacksAndMessages(null)
                val lockRequested = isLockCommand(phrase)
                voiceMode = VoiceMode.WAKE_WORD
                hideOverlay()
                if (lockRequested) lockScreenNow()
            }
            else -> Unit
        }
    }

    private fun normalize(phrase: String): String =
        phrase.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isWakeWord(phrase: String): Boolean {
        val normalized = normalize(phrase)
        return normalized == "jarvis" ||
            normalized == "hey jarvis" ||
            normalized.startsWith("jarvis ") ||
            normalized.startsWith("hey jarvis ")
    }

    private fun isLockCommand(phrase: String): Boolean {
        val commands = setOf(
            "screen off",
            "screen lock",
            "lock my phone",
            "lock my screen",
            "phone lock",
            "screen off chey",
            "phone lock chey",
            "screen ni off chey",
            "lock chey",
        )
        return normalize(phrase) in commands
    }

    private fun lockScreenNow() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, JarvisDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) dpm.lockNow()
    }

    // ---------- Text to speech ----------

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.let(::configureTextToSpeech)
                isTextToSpeechReady = true
                pendingSpeechCompletion?.let { completion ->
                    pendingSpeechCompletion = null
                    speakActivationMessage(completion)
                }
            } else {
                isTextToSpeechReady = false
                pendingSpeechCompletion?.invoke()
                pendingSpeechCompletion = null
            }
        }
    }

    private fun configureTextToSpeech(speech: TextToSpeech) {
        val languageResult = speech.setLanguage(Locale.US)
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            speech.language = Locale.getDefault()
        }
        val femaleVoice = speech.voices?.firstOrNull { voice ->
            val isEnglish = voice.locale.language == Locale.ENGLISH.language
            val describesFemaleVoice =
                voice.name.contains("female", ignoreCase = true) ||
                    voice.features.orEmpty().any { it.contains("female", ignoreCase = true) }
            isEnglish && describesFemaleVoice
        }
        if (femaleVoice != null) speech.voice = femaleVoice
    }

    private fun speakActivationMessage(onComplete: () -> Unit) {
        val speech = textToSpeech
        if (!isTextToSpeechReady || speech == null) {
            pendingSpeechCompletion = onComplete
            return
        }
        var completed = false
        fun completeSpeech() {
            if (completed) return
            completed = true
            mainHandler.post(onComplete)
        }
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = completeSpeech()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = completeSpeech()
        })
        val result = speech.speak(
            "Yes, I'm listening.",
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "jarvis_activation",
        )
        if (result == TextToSpeech.ERROR) completeSpeech()
    }

    // ---------- Activation popup over other apps ----------

    private fun showOverlay() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        val owner = OverlayLifecycleOwner().also { it.create() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val visibleState = remember {
                    MutableTransitionState(false).apply { targetState = true }
                }
                AnimatedVisibility(
                    visibleState = visibleState,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(animationSpec = tween(240)) +
                        scaleIn(animationSpec = tween(360), initialScale = 0.82f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.58f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ActivationPopup()
                    }
                }
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            owner.resume()
            overlayView = view
            overlayOwner = owner
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show activation popup", e)
            owner.destroy()
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        try {
            getSystemService(WindowManager::class.java).removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove activation popup", e)
        }
        overlayOwner?.destroy()
        overlayView = null
        overlayOwner = null
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.microphone_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.microphone_channel_description)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deactivate = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_service_notification))
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.deactivate_jarvis),
                    deactivate,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }
}

/** Minimal lifecycle owner so Compose can render inside a WindowManager overlay. */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun create() {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
