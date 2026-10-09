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
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.ChannelPick
import com.gabrielifrim.brut.audio.CaptureSource
import com.gabrielifrim.brut.audio.TakeOptions
import com.gabrielifrim.brut.audio.MeterMode
import com.gabrielifrim.brut.audio.RecorderController
import com.gabrielifrim.brut.library.LibraryController
import com.gabrielifrim.brut.library.Take
import com.gabrielifrim.brut.library.TakeSort
import com.gabrielifrim.brut.ui.library.LibraryActions
import com.gabrielifrim.brut.ui.library.LibraryScreen
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.LaunchedEffect
import com.gabrielifrim.brut.service.RecordingService
import com.gabrielifrim.brut.ui.console.ConsoleActions
import com.gabrielifrim.brut.ui.console.ConsoleScreen
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutTheme
import com.gabrielifrim.brut.ui.theme.BrutType

class MainActivity : ComponentActivity() {

    private val controller: RecorderController get() = (application as BrutApp).controller
    private val library: LibraryController get() = (application as BrutApp).library

    /** Écran affiché : la console ou la bibliothèque des prises. */
    private var screen by mutableStateOf(Screen.CONSOLE)
    private var canReadAll by mutableStateOf(false)
    private lateinit var readAllLauncher: ActivityResultLauncher<String>
    private lateinit var consentLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var folderLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerLaunchers()
        savedInstanceState?.getString(KEY_SCREEN)?.let { screen = Screen.valueOf(it) }
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
                    canReadAll = hasReadPermission()
                    // La bibliothèque libère le micro : inutile de capter pendant qu'on réécoute.
                    if (granted && screen == Screen.CONSOLE) controller.startMonitoring()
                }
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                    library.player.pause()
                    controller.stopMonitoring()
                }

                if (granted) {
                    val state by controller.state.collectAsStateWithLifecycle()
                    when (screen) {
                        Screen.CONSOLE -> ConsoleScreen(state, actions)
                        Screen.LIBRARY -> {
                            val lib by library.state.collectAsStateWithLifecycle()
                            val player by library.player.state.collectAsStateWithLifecycle()
                            LaunchedEffect(lib.consent) {
                                lib.consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                            }
                            LibraryScreen(
                                lib, player, canReadAll, state.isRecording,
                                folderLabel = state.folderLabel,
                                customFolder = controller.customFolder != null,
                                actions = libraryActions,
                            )
                        }
                    }
                } else {
                    PermissionScreen(onGrant = { launcher.launch(permissionsToAsk()) })
                }
            }
        }
    }

    private fun registerLaunchers() {
        readAllLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            canReadAll = ok
            if (ok) library.refresh()
        }
        consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            library.onConsentResult(result.resultCode == RESULT_OK)
        }
        folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) {
                controller.setCustomFolder(tree)
                library.refresh()
            }
        }
    }

    private fun showConsole() {
        library.player.stop()
        screen = Screen.CONSOLE
        controller.startMonitoring()
    }

    private val libraryActions = object : LibraryActions {
        override fun back() = showConsole()
        override fun select(take: Take) = library.select(take)
        override fun togglePlay() = library.player.toggle()
        override fun seek(fraction: Float) = library.player.seekTo(fraction)
        override fun setLoop(loop: Boolean) = library.player.setLoop(loop)
        override fun rename(take: Take, name: String) = library.rename(take, name)
        override fun share(take: Take) = startActivity(library.shareIntent(take))
        override fun trash(take: Take) = library.trash(take)
        override fun undoTrash(take: Take) = library.undoTrash(take)
        override fun setQuery(query: String) = library.setQuery(query)
        override fun setSort(sort: TakeSort) = library.setSort(sort)
        override fun requestReadAll() = readAllLauncher.launch(readPermission())
        override fun chooseFolder() = folderLauncher.launch(null)
        override fun resetFolder() {
            controller.setCustomFolder(null)
            library.refresh()
        }
        override fun consumeMessage() = library.consumeMessage()
        override fun startTrim(take: Take) = library.startTrim(take)
        override fun setTrim(start: Long, end: Long) = library.setTrim(start, end)
        override fun endTrim() = library.endTrim()
        override fun export(take: Take, bitDepth: BitDepth?, channels: ChannelPick, split: Boolean) =
            library.export(take, bitDepth, channels, split)
        override fun cancelExport() = library.cancelExport()
    }

    private fun readPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasReadPermission() =
        ContextCompat.checkSelfPermission(this, readPermission()) == PackageManager.PERMISSION_GRANTED

    private val actions = object : ConsoleActions {
        override fun toggleRecording() {
            if (controller.state.value.isBusy) {
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
        override fun setCaptureMode(mode: CaptureSource?) = controller.setCaptureMode(mode)
        override fun resetClip() = controller.resetClip()
        override fun addMarker() = controller.addMarker()
        override fun setOptions(options: TakeOptions) = controller.setOptions(options)
        override fun resetLoudness() = controller.resetLoudness()
        override fun openLibrary() {
            // Pendant une prise, le micro reste ouvert ; sinon on le rend en quittant la console.
            controller.stopMonitoring()
            library.refresh()
            screen = Screen.LIBRARY
        }
        override fun consumeMessage() = controller.consumeMessage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
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

private enum class Screen { CONSOLE, LIBRARY }

private const val KEY_SCREEN = "ecran"
