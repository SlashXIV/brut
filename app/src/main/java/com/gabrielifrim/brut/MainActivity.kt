package com.gabrielifrim.brut

import com.gabrielifrim.brut.ui.component.chassis
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
import com.gabrielifrim.brut.ui.theme.BrutShapes
import com.gabrielifrim.brut.ui.theme.Palettes
import com.gabrielifrim.brut.ui.theme.legibleOn
import com.gabrielifrim.brut.ui.console.calm
import com.gabrielifrim.brut.ui.motion.motionLevelOf
import com.gabrielifrim.brut.ui.motion.rememberAnimatorScale
import com.gabrielifrim.brut.storage.SettingsStore
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.gabrielifrim.brut.ui.theme.BrutType

class MainActivity : ComponentActivity() {

    private val controller: RecorderController get() = (application as BrutApp).controller
    private val library: LibraryController get() = (application as BrutApp).library
    private val settings by lazy { SettingsStore(applicationContext) }

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
        if (savedInstanceState == null) handleShortcut(intent)
        // Bord à bord dès la première image ; les icônes suivent ensuite la palette (ci-dessous).
        enableEdgeToEdge()
        setContent {
            // Rien n'est dessiné avant la lecture des réglages (quelques millisecondes, le fond de
            // fenêtre reste visible) : sinon chaque lancement passerait en fondu de la palette par
            // défaut à celle choisie.
            val appearance = settings.appearance.collectAsStateWithLifecycle(null).value ?: return@setContent
            val palette = Palettes.resolve(appearance.palette, isSystemInDarkTheme())
            val animatorScale = rememberAnimatorScale()
            // Icônes des barres système accordées à la palette, pas au thème du téléphone.
            LaunchedEffect(palette.isDark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (palette.isDark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BrutTheme(palette = palette, motion = motionLevelOf(animatorScale, appearance.sober)) {
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
                    // Deux lectures du même état : la console suit l'état calme, les instruments l'état vivant.
                    val live = controller.state.collectAsStateWithLifecycle()
                    val state by remember { derivedStateOf { live.value.calm() } }
                    when (screen) {
                        Screen.CONSOLE -> ConsoleScreen(state, live, appearance, animatorScale == 0f, actions)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    /** Raccourci « Prises » : on arrive directement sur la bibliothèque. */
    private fun handleShortcut(intent: Intent?) {
        if (intent?.action != ACTION_LIBRARY || screen == Screen.LIBRARY) return
        androidx.core.content.pm.ShortcutManagerCompat.reportShortcutUsed(this, SHORTCUT_LIBRARY)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            actions.openLibrary()
        } else {
            library.refresh()
            screen = Screen.LIBRARY
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
        override fun export(take: Take, bitDepth: BitDepth?, channels: ChannelPick, sampleRate: Int?, split: Boolean) =
            library.export(take, bitDepth, channels, sampleRate, split)
        override fun cancelExport() = library.cancelExport()
        override fun stampFromLtc(take: Take, channel: Int) = library.stampFromLtc(take, channel)
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
        override fun applyPreset(preset: com.gabrielifrim.brut.audio.Preset) = controller.applyPreset(preset)
        override fun resetLoudness() = controller.resetLoudness()
        override fun openLibrary() {
            // Pendant une prise, le micro reste ouvert ; sinon on le rend en quittant la console.
            controller.stopMonitoring()
            library.refresh()
            screen = Screen.LIBRARY
        }
        override fun consumeMessage() = controller.consumeMessage()
        override fun startChannelTest() = controller.startChannelTest()
        override fun stopChannelTest() = controller.stopChannelTest()
        override fun closeChannelTest() = controller.closeChannelTest()
        override fun applyRecipe(recipe: com.gabrielifrim.brut.audio.CaptureRecipe) = controller.applyRecipe(recipe)
        override fun forgetRecipe() = controller.forgetRecipe()
        override fun setPalette(id: String) {
            lifecycleScope.launch { settings.setPalette(id) }
        }
        override fun setSober(sober: Boolean) {
            lifecycleScope.launch { settings.setSober(sober) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    companion object {
        const val ACTION_LIBRARY = "com.gabrielifrim.brut.PRISES"
        const val SHORTCUT_LIBRARY = "prises"
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
            .chassis()
            .safeDrawingPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Le monogramme de l'icône : la lettre, puis le trait ambre.
            Text(stringResource(R.string.app_name).take(1), style = BrutType.Clock.copy(fontSize = BrutType.Clock.fontSize * 1.6f), color = BrutColors.Ink, modifier = Modifier.clearAndSetSemantics {})
            Box(Modifier.width(48.dp).height(3.dp).background(BrutColors.Lamp))
            Text(stringResource(R.string.permission_title), style = BrutType.Title, color = BrutColors.Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.permission_body), style = BrutType.Body, color = BrutColors.InkDim, textAlign = TextAlign.Center)
            val label = stringResource(if (asked) R.string.permission_settings else R.string.permission_grant)
            val lamp = BrutColors.Lamp
            Text(
                label.uppercase(),
                style = BrutType.Action.copy(fontSize = 15.sp),
                color = legibleOn(lamp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(BrutShapes.InsetShape)
                    .background(lamp)
                    .clickable(role = Role.Button) {
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
