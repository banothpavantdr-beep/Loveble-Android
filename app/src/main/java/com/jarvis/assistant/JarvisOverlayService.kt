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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

/**
 * Foreground service that keeps JARVIS listening for the wake word
 * ("Jarvis" / "Hey Jarvis") while the user is in other apps.
 *
 * - Started only when the user taps ACTIVATE JARVIS (app in foreground).
 * - Shows a persistent notification with a Deactivate action.
 * - Uses the on-device SpeechRecognizer; nothing is recorded or uploaded by JARVIS.
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
    private var speechRecognizer: SpeechRecognizer? = null
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
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ---------- Speech recognition ----------

    private fun startWakeWordRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopListeningAndSelf()
            return
        }
        mainHandler.removeCallbacksAndMessages(null)
        voiceMode = VoiceMode.WAKE_WORD
        hideOverlay()
        startRecognition()
    }

    private fun startCommandRecognition() {
        if (voiceMode != VoiceMode.ACTIVATING) return
        voiceMode = VoiceMode.COMMAND
        startRecognition()
    }

    private fun startRecognition() {
        val activeMode = voiceMode
        speechRecognizer?.destroy()
        speechRecognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer unavailable", e)
            null
        }
        val recognizer = speechRecognizer ?: run {
            scheduleWakeWordRecognition(1000L)
            return
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                if (voiceMode != activeMode) return
                speechRecognizer?.destroy()
                speechRecognizer = null
                // Busy/throttled recognizers need a slightly longer pause.
                val delay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT,
                    -> 1000L
                    else -> 300L
                }
                // A failed command attempt returns to waiting for the wake word.
                voiceMode = VoiceMode.WAKE_WORD
                hideOverlay()
                scheduleWakeWordRecognition(delay)
            }

            override fun onResults(results: Bundle?) {
                if (voiceMode != activeMode) return
                val phrases = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                ).orEmpty()
                if (activeMode == VoiceMode.WAKE_WORD) {
                    if (phrases.any(::isWakeWord)) {
                        voiceMode = VoiceMode.ACTIVATING
                        speechRecognizer?.cancel()
                        showOverlay()
                        // The wake word itself is never treated as a command:
                        // a fresh recognition session starts after the reply.
                        speakActivationMessage(::startCommandRecognition)
                    } else {
                        scheduleWakeWordRecognition(300L)
                    }
                } else {
                    val lockRequested = phrases.any(::isLockCommand)
                    voiceMode = VoiceMode.WAKE_WORD
                    hideOverlay()
                    if (lockRequested) lockScreenNow()
                    scheduleWakeWordRecognition(600L)
                }
            }
        })
        try {
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            voiceMode = VoiceMode.WAKE_WORD
            scheduleWakeWordRecognition(1000L)
        }
    }

    private fun scheduleWakeWordRecognition(delayMs: Long) {
        if (voiceMode != VoiceMode.WAKE_WORD) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(
            { if (voiceMode == VoiceMode.WAKE_WORD) startRecognition() },
            delayMs,
        )
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
