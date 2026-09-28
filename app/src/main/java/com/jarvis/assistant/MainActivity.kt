package com.jarvis.assistant

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin
import java.util.Locale

private val Midnight = JarvisComponents.Midnight
private val Panel = JarvisComponents.Panel
private val PanelBorder = JarvisComponents.PanelBorder
private val Cyan = JarvisComponents.Cyan
private val SoftCyan = JarvisComponents.SoftCyan
private val Muted = JarvisComponents.Muted
private val Green = JarvisComponents.Green

class MainActivity : ComponentActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var pendingActivationAfterOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        adminComponent = ComponentName(this, JarvisDeviceAdminReceiver::class.java)
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        if (pendingActivationAfterOverlay) {
            // Returning from the "display over other apps" settings screen.
            pendingActivationAfterOverlay = false
            startBackgroundListening()
        }
        renderScreen()
    }

    private fun renderScreen() {
        setContent {
            JarvisTheme {
                JarvisScreen(
                    isAdminActive = devicePolicyManager.isAdminActive(adminComponent),
                    isMicrophoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                    isSpeechAvailable = SpeechRecognizer.isRecognitionAvailable(this),
                    isJarvisActive = JarvisOverlayService.isActive.value,
                    onEnableScreenLock = ::requestDeviceAdmin,
                    onLockNow = ::lockScreenNow,
                    onActivateJarvis = ::requestVoiceAccess,
                    onDeactivateJarvis = ::deactivateJarvis,
                )
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_description),
                    )
                },
            )
        }
    }

    private fun lockScreenNow() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.lockNow()
        } else {
            requestDeviceAdmin()
        }
    }

    private fun requestVoiceAccess() {
        val missingPermissions = buildList {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            ensureOverlayPermissionThenStart()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                ensureOverlayPermissionThenStart()
            } else {
                renderScreen()
            }
        }

    /** The popup over other apps needs "Display over other apps". */
    private fun ensureOverlayPermissionThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            pendingActivationAfterOverlay = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } else {
            startBackgroundListening()
        }
    }

    private fun startBackgroundListening() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        // Started while JARVIS is visible, as Android requires for microphone services.
        JarvisOverlayService.start(this)
        JarvisOverlayService.isActive.value = true
        renderScreen()
    }

    private fun deactivateJarvis() {
        JarvisOverlayService.stop(this)
        renderScreen()
    }
}

@Composable
private fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            background = Midnight,
            surface = Panel,
            primary = Cyan,
            onPrimary = Midnight,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

@Composable
private fun JarvisScreen(
    isAdminActive: Boolean,
    isMicrophoneGranted: Boolean,
    isSpeechAvailable: Boolean,
    isJarvisActive: Boolean,
    onEnableScreenLock: () -> Unit,
    onLockNow: () -> Unit,
    onActivateJarvis: () -> Unit,
    onDeactivateJarvis: () -> Unit,
) {
    val activated = isJarvisActive
    val showActivationPopup = false
    val status = when {
        activated && isMicrophoneGranted -> "LISTENING FOR COMMANDS"
        !isSpeechAvailable -> "VOICE SERVICE UNAVAILABLE"
        !isAdminActive -> "SCREEN LOCK PERMISSION REQUIRED"
        !isMicrophoneGranted -> "MICROPHONE PERMISSION REQUIRED"
        else -> "SYSTEM READY"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Midnight,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A111D), Midnight, Color(0xFF0B1621)),
                    ),
                )
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "JARVIS",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                )
                Text(
                    text = "PERSONAL DEVICE ASSISTANT",
                    color = Muted,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(34.dp))
                StatusOrb(activated = activated)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = status,
                    color = if (activated) Green else SoftCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(modifier = Modifier.height(34.dp))

                PermissionRow(
                    icon = Icons.Default.Security,
                    title = "SCREEN LOCK",
                    detail = if (isAdminActive) "Device admin enabled" else "Device admin not enabled",
                    ready = isAdminActive,
                    onClick = onEnableScreenLock,
                )
                Spacer(modifier = Modifier.height(12.dp))
                PermissionRow(
                    icon = Icons.Default.Mic,
                    title = "VOICE CONTROL",
                    detail = if (isMicrophoneGranted) "Microphone access enabled" else "Microphone access not enabled",
                    ready = isMicrophoneGranted && isSpeechAvailable,
                    onClick = onActivateJarvis,
                )
                Spacer(modifier = Modifier.height(12.dp))
                AskJarvisCard(
                    onLockScreen = onLockNow,
                    onStopListening = onDeactivateJarvis,
                )

                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = onEnableScreenLock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAdminActive) Panel else Cyan,
                        contentColor = if (isAdminActive) SoftCyan else Midnight,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isAdminActive) "SCREEN LOCK ENABLED" else "ENABLE SCREEN LOCK",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onLockNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    enabled = isAdminActive,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SoftCyan),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("LOCK SCREEN NOW", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (activated) onDeactivateJarvis() else onActivateJarvis()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cyan,
                        contentColor = Midnight,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(if (activated) "DEACTIVATE JARVIS" else "ACTIVATE JARVIS", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Say “Screen off” or “Lock my phone”",
                    color = Muted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            AnimatedVisibility(
                visible = showActivationPopup,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(240)) +
                    scaleIn(
                        animationSpec = tween(360),
                        initialScale = 0.82f,
                    ),
                exit = fadeOut(animationSpec = tween(220)) +
                    scaleOut(
                        animationSpec = tween(220),
                        targetScale = 0.94f,
                    ),
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
}

@Composable
internal fun ActivationPopup() {
    val infiniteTransition = rememberInfiniteTransition(label = "activation_popup")
    val orbPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb_pulse",
    )
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave_phase",
    )
    val popupShape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .width(312.dp)
            .shadow(
                elevation = 28.dp,
                shape = popupShape,
                ambientColor = Cyan.copy(alpha = 0.24f),
                spotColor = Cyan.copy(alpha = 0.34f),
            )
            .clip(popupShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xE91A2B3D),
                        Color(0xE90A121E),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Cyan.copy(alpha = 0.82f),
                        Color(0xFF1C657D).copy(alpha = 0.4f),
                    ),
                ),
                shape = popupShape,
            )
            .padding(horizontal = 28.dp, vertical = 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(148.dp),
                contentAlignment = Alignment.Center,
            ) {
                AudioWaveRing(phase = wavePhase)
                Box(
                    modifier = Modifier
                        .size((96f * orbPulse).dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFB8F5FF),
                                    Cyan,
                                    Color(0xFF0D536B),
                                    Color.Transparent,
                                ),
                            ),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.72f), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color.White, Cyan, Color(0xFF1A89A8)),
                            ),
                        ),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "JARVIS",
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 5.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Yes, I'm listening...",
                color = SoftCyan,
                fontSize = 14.sp,
                letterSpacing = 0.4.sp,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Listening",
                    tint = Cyan,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = "LISTENING",
                    color = Cyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}

@Composable
private fun AudioWaveRing(phase: Float) {
    Canvas(modifier = Modifier.size(148.dp)) {
        val center = androidx.compose.ui.geometry.Offset(
            x = size.width / 2f,
            y = size.height / 2f,
        )
        val baseRadius = size.minDimension * 0.36f
        drawCircle(
            color = Cyan.copy(alpha = 0.1f),
            radius = baseRadius + 12.dp.toPx(),
        )
        drawCircle(
            color = Cyan.copy(alpha = 0.52f),
            radius = baseRadius + 9.dp.toPx(),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        repeat(16) { index ->
            val angle = (index * (Math.PI * 2 / 16)).toFloat()
            val waveHeight = (5f + 9f * (
                0.5f + 0.5f * sin(phase + index * 0.7f)
            )) * density
            val innerRadius = baseRadius + 18.dp.toPx()
            val outerRadius = innerRadius + waveHeight
            val start = androidx.compose.ui.geometry.Offset(
                x = center.x + cos(angle) * innerRadius,
                y = center.y + sin(angle) * innerRadius,
            )
            val end = androidx.compose.ui.geometry.Offset(
                x = center.x + cos(angle) * outerRadius,
                y = center.y + sin(angle) * outerRadius,
            )
            drawLine(
                color = Cyan.copy(alpha = 0.78f),
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun StatusOrb(activated: Boolean) {
    Box(
        modifier = Modifier
            .size(118.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = if (activated) {
                        listOf(Color(0xFF5AF0BB), Color(0xFF13352D), Color.Transparent)
                    } else {
                        listOf(Color(0xFF6CDFFF), Color(0xFF12303D), Color.Transparent)
                    },
                ),
            )
            .border(1.dp, if (activated) Green else Cyan, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (activated) Green else Cyan),
        )
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    ready: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (ready) Color(0xFF245646) else PanelBorder,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Panel.copy(alpha = 0.72f),
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (ready) Green else Cyan,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(detail, fontSize = 11.sp, color = Muted)
        }
        Text(
            text = if (ready) "READY" else "SET UP",
            color = if (ready) Green else SoftCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}