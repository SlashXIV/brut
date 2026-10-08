package com.gabrielifrim.brut

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gabrielifrim.brut.audio.AudioFormatSpec
import com.gabrielifrim.brut.audio.MeterMode
import com.gabrielifrim.brut.audio.RecorderController
import com.gabrielifrim.brut.service.RecordingService
import com.gabrielifrim.brut.ui.console.ConsoleActions
import com.gabrielifrim.brut.ui.console.ConsoleScreen
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutTheme
import com.gabrielifrim.brut.ui.theme.BrutType

class MainActivity : ComponentActivity() {

    private val controller: RecorderController get() = (application as BrutApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Console toujours sombre : icônes claires quel que soit le thème du système.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            BrutTheme {
                var granted by remember { mutableStateOf(hasMicPermission()) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    granted = hasMicPermission()
                    if (granted) controller.startMonitoring()
                }
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    granted = hasMicPermission()
                    if (granted) controller.startMonitoring()
                }
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                    controller.stopMonitoring()
                }

                if (granted) {
                    val state by controller.state.collectAsStateWithLifecycle()
                    ConsoleScreen(state, actions)
                } else {
                    PermissionScreen(onGrant = { launcher.launch(permissionsToAsk()) })
                }
            }
        }
    }

    private val actions = object : ConsoleActions {
        override fun toggleRecording() {
            if (controller.state.value.isRecording) {
                controller.stopRecording()
            } else if (controller.startRecording()) {
                RecordingService.start(this@MainActivity)
            }
        }

        override fun selectDevice(id: Int) = controller.selectDevice(id)
        override fun setFormat(format: AudioFormatSpec) = controller.setFormat(format)
        override fun setGain(channel: Int, db: Float) = controller.setGain(channel, db)
        override fun setGainLinked(linked: Boolean) = controller.setGainLinked(linked)
        override fun setMeterMode(mode: MeterMode) = controller.setMeterMode(mode)
        override fun resetClip() = controller.resetClip()
        override fun consumeMessage() = controller.consumeMessage()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun permissionsToAsk(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(BrutColors.Graphite)
            .safeDrawingPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("B", style = BrutType.Clock.copy(fontSize = BrutType.Clock.fontSize * 1.6f), color = BrutColors.Cream)
            Box(Modifier.width(48.dp).height(3.dp).background(BrutColors.Amber))
            Text(stringResource(R.string.permission_title), style = BrutType.Title, color = BrutColors.Cream, textAlign = TextAlign.Center)
            Text(stringResource(R.string.permission_body), style = BrutType.Body, color = BrutColors.CreamDim, textAlign = TextAlign.Center)
            val label = stringResource(if (asked) R.string.permission_settings else R.string.permission_grant)
            Text(
                label,
                style = BrutType.BodyStrong,
                color = BrutColors.Graphite,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrutColors.Amber)
                    .border(1.dp, BrutColors.Amber, RoundedCornerShape(12.dp))
                    .clickable {
                        if (asked) {
                            // Après un refus définitif, Android ne redemande plus : on ouvre les réglages.
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } else {
                            asked = true
                            onGrant()
                        }
                    }
                    .padding(vertical = 16.dp),
            )
        }
    }
}
