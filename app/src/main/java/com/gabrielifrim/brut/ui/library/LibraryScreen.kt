package com.gabrielifrim.brut.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.LevelMeter
import com.gabrielifrim.brut.library.LibraryMessage
import com.gabrielifrim.brut.library.LibraryState
import com.gabrielifrim.brut.library.PlayerState
import com.gabrielifrim.brut.library.Take
import com.gabrielifrim.brut.library.TakeSort
import com.gabrielifrim.brut.library.Waveform
import com.gabrielifrim.brut.ui.console.Lamp
import com.gabrielifrim.brut.ui.console.RackPlate
import com.gabrielifrim.brut.ui.console.Readout
import com.gabrielifrim.brut.ui.console.engraved
import com.gabrielifrim.brut.ui.console.meterPosition
import com.gabrielifrim.brut.ui.formatBytes
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

interface LibraryActions {
    fun back()
    fun select(take: Take)
    fun togglePlay()
    fun seek(fraction: Float)
    fun setLoop(loop: Boolean)
    fun rename(take: Take, name: String)
    fun share(take: Take)
    fun trash(take: Take)
    fun undoTrash(take: Take)
    fun setQuery(query: String)
    fun setSort(sort: TakeSort)
    fun requestReadAll()
    fun consumeMessage()
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.FRANCE)

@Composable
fun LibraryScreen(
    state: LibraryState,
    player: PlayerState,
    canReadAll: Boolean,
    recording: Boolean,
    actions: LibraryActions,
) {
    BackHandler(onBack = actions::back)
    var renaming by remember { mutableStateOf<Take?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(BrutColors.Graphite)
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TopBar(state.takes.size, actions::back)
            Spacer(Modifier.height(10.dp))
            SearchAndSort(state, actions)
            if (!canReadAll) {
                Text(
                    stringResource(R.string.library_read_all),
                    style = BrutType.Body,
                    color = BrutColors.Amber,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = actions::requestReadAll)
                        .padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            val visible = state.visible
            when {
                state.loading && state.takes.isEmpty() -> Centered(stringResource(R.string.library_loading))
                state.takes.isEmpty() -> Centered(stringResource(R.string.library_empty))
                visible.isEmpty() -> Centered(stringResource(R.string.library_no_match))
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.key }) { take ->
                        if (take.key == state.selectedKey) {
                            MountedTake(
                                take = take,
                                waveform = state.waveforms[take.key],
                                player = player,
                                recording = recording,
                                actions = actions,
                                onRename = { renaming = take },
                            )
                        } else {
                            TakeRow(take) { actions.select(take) }
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
        LibraryMessageBar(state.message, actions, Modifier.align(Alignment.BottomCenter))
    }

    renaming?.let { take ->
        RenameDialog(
            initial = take.baseName,
            onConfirm = { actions.rename(take, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun TopBar(count: Int, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹",
            style = BrutType.Title,
            color = BrutColors.Cream,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.library_back), onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Column {
            Text(stringResource(R.string.library_title).uppercase(), style = BrutType.Title, color = BrutColors.Cream)
            Box(Modifier.width(34.dp).height(2.dp).background(BrutColors.Amber))
        }
        Spacer(Modifier.weight(1f))
        Readout(pluralTakes(count))
    }
}

@Composable
private fun pluralTakes(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.library_count, count, count)

@Composable
private fun SearchAndSort(state: LibraryState, actions: LibraryActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .drawBehind {
                    drawRoundRect(Color(0xFF0B0A08), cornerRadius = CornerRadius(3.dp.toPx()))
                    drawRect(Color.Black.copy(alpha = 0.7f), size = Size(size.width, 2.dp.toPx()))
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (state.query.isEmpty()) {
                Text(stringResource(R.string.library_search), style = BrutType.Readout, color = BrutColors.CreamFaint)
            }
            BasicTextField(
                value = state.query,
                onValueChange = actions::setQuery,
                singleLine = true,
                textStyle = BrutType.Readout.copy(color = BrutColors.Amber),
                cursorBrush = SolidColor(BrutColors.Amber),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(10.dp))
        val labels = mapOf(
            TakeSort.DATE to stringResource(R.string.library_sort_date),
            TakeSort.NAME to stringResource(R.string.library_sort_name),
            TakeSort.DURATION to stringResource(R.string.library_sort_duration),
        )
        // Commutateur à trois positions : un toucher passe à la suivante.
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BrutColors.Panel)
                .clickable(role = Role.Button) {
                    val all = TakeSort.entries
                    actions.setSort(all[(state.sort.ordinal + 1) % all.size])
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.library_sort).uppercase() + " ", style = BrutType.Legend, color = BrutColors.CreamFaint)
            Text(labels.getValue(state.sort).uppercase(), style = BrutType.Legend, color = BrutColors.Amber)
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = BrutType.Body, color = BrutColors.CreamDim)
    }
}

/** Ligne d'une prise au repos : date, nom, format, durée. */
@Composable
private fun TakeRow(take: Take, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(BrutColors.Recess)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TakeHeader(take, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            take.info?.let { formatDuration(it.durationSeconds, withHundredths = false) } ?: "--:--:--",
            style = BrutType.Readout,
            color = BrutColors.Cream,
        )
    }
}

@Composable
private fun TakeHeader(take: Take, modifier: Modifier = Modifier) {
    Column(modifier) {
        val date = Instant.ofEpochMilli(take.dateMillis).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)
        Text(date.uppercase(Locale.FRANCE), style = engraved(BrutType.Legend), color = BrutColors.Amber)
        Text(take.baseName, style = engraved(BrutType.BodyStrong), color = BrutColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatLine(take), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim, maxLines = 1)
    }
}

@Composable
private fun formatLine(take: Take): String {
    val i = take.info ?: return stringResource(R.string.library_unreadable) + " · " + formatBytes(take.sizeBytes)
    val depth = if (i.isFloat) "32F" else "${i.bitsPerSample}"
    val ch = stringResource(if (i.channels == 1) R.string.format_short_mono else R.string.format_short_stereo)
    return "${formatRate(i.sampleRate)} · $depth BIT · $ch · ${formatBytes(take.sizeBytes)}"
}

/** La prise sélectionnée, « montée » sur une plaque : forme d'onde, lecture, actions. */
@Composable
private fun MountedTake(
    take: Take,
    waveform: Waveform?,
    player: PlayerState,
    recording: Boolean,
    actions: LibraryActions,
    onRename: () -> Unit,
) {
    RackPlate(Modifier.fillMaxWidth(), contentPadding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TakeHeader(take, Modifier.weight(1f).clickable { actions.select(take) })
            }
            WaveformView(waveform, player.fraction, enabled = take.info != null, onSeek = actions::seek)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayButton(player.playing, enabled = take.info != null && !recording, onClick = actions::togglePlay)
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Switch) { actions.setLoop(!player.loop) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Lamp(BrutColors.Amber, lit = player.loop)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.library_loop).uppercase(), style = engraved(BrutType.Legend), color = if (player.loop) BrutColors.Cream else BrutColors.CreamDim)
                }
                Spacer(Modifier.weight(1f))
                Readout(
                    formatDuration(player.positionSeconds, withHundredths = false) + " / " +
                        formatDuration(take.info?.durationSeconds ?: 0.0, withHundredths = false),
                    color = BrutColors.Cream,
                )
            }
            if (recording) {
                Text(stringResource(R.string.library_recording_busy), style = BrutType.Body, color = BrutColors.Amber)
            }
            take.info?.description?.let {
                Text(it, style = BrutType.Body, color = BrutColors.CreamDim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.library_rename), BrutColors.Cream, Modifier.weight(1f), onRename)
                ActionButton(stringResource(R.string.library_share), BrutColors.Cream, Modifier.weight(1f)) { actions.share(take) }
                ActionButton(stringResource(R.string.library_delete), BrutColors.Red, Modifier.weight(1f)) { actions.trash(take) }
            }
        }
    }
}

/**
 * Forme d'onde sur l'échelle des crête-mètres (IEC) : une prise calme reste lisible,
 * et rien n'est normalisé. Les crêtes saturées sont en rouge. Toucher ou glisser = se placer.
 */
@Composable
private fun WaveformView(waveform: Waveform?, fraction: Float, enabled: Boolean, onSeek: (Float) -> Unit) {
    val description = stringResource(R.string.library_waveform)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF0B0A08))
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { onSeek(it.x / size.width) }
            }
            .pointerInput(enabled) {
                if (enabled) detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek(change.position.x / size.width)
                }
            },
    ) {
        val mid = size.height / 2
        drawLine(BrutColors.CreamFaint.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 1f)
        val peaks = waveform?.peaks
        if (peaks != null) {
            val step = size.width / peaks.size
            val playX = fraction * size.width
            peaks.forEachIndexed { i, p ->
                val h = meterPosition(LevelMeter.toDb(p)) * (mid - 2.dp.toPx())
                val x = i * step
                val color = when {
                    p >= LevelMeter.CLIP_LINEAR -> BrutColors.Red
                    x <= playX -> BrutColors.Amber
                    else -> BrutColors.CreamDim
                }
                drawRect(color, Offset(x, mid - h), Size(maxOf(step - 1f, 1f), h * 2))
            }
        }
        val x = fraction * size.width
        drawLine(BrutColors.Cream, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (playing) R.string.library_pause else R.string.library_play)
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(52.dp)) {
            val r = size.minDimension / 2
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF3E372F), Color(0xFF171410))), r)
            drawCircle(BrutColors.Edge, r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            val ink = if (enabled) BrutColors.Amber else BrutColors.CreamFaint
            if (playing) {
                val w = r * 0.22f
                val h = r * 0.8f
                drawRect(ink, Offset(center.x - w * 1.6f, center.y - h / 2), Size(w, h))
                drawRect(ink, Offset(center.x + w * 0.6f, center.y - h / 2), Size(w, h))
            } else {
                val s = r * 0.5f
                val path = Path().apply {
                    moveTo(center.x - s * 0.6f, center.y - s)
                    lineTo(center.x + s, center.y)
                    lineTo(center.x - s * 0.6f, center.y + s)
                    close()
                }
                drawPath(path, ink)
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .drawBehind {
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3B342C), Color(0xFF221E19))), cornerRadius = CornerRadius(4.dp.toPx()))
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, 0.5f), Offset(size.width, 0.5f))
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = engraved(BrutType.Legend), color = color, maxLines = 1)
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        RackPlate(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.library_rename_title), style = BrutType.Title, color = BrutColors.Cream)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawRoundRect(Color(0xFF0B0A08), cornerRadius = CornerRadius(3.dp.toPx())) }
                        .padding(12.dp),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        textStyle = BrutType.Readout.copy(color = BrutColors.Amber),
                        cursorBrush = SolidColor(BrutColors.Amber),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Text(stringResource(R.string.library_rename_hint), style = BrutType.Body, color = BrutColors.CreamDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(R.string.library_cancel), BrutColors.CreamDim, Modifier.weight(1f), onDismiss)
                    ActionButton(stringResource(R.string.library_rename), BrutColors.Amber, Modifier.weight(1f)) {
                        if (value.isNotBlank()) onConfirm(value)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun LibraryMessageBar(message: LibraryMessage?, actions: LibraryActions, modifier: Modifier) {
    var shown by remember { mutableStateOf<LibraryMessage?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            kotlinx.coroutines.delay(if (message is LibraryMessage.Trashed) 6000 else 3000)
            actions.consumeMessage()
        }
    }
    AnimatedVisibility(message != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val m = shown ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(BrutColors.PanelRaised)
                .drawBehind { drawRect(if (m is LibraryMessage.Failed) BrutColors.Red else BrutColors.Amber, Offset.Zero, size.copy(width = 4.dp.toPx())) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = when (m) {
                is LibraryMessage.Trashed -> stringResource(R.string.library_trashed, m.take.baseName)
                is LibraryMessage.Renamed -> stringResource(R.string.library_renamed, m.name)
                LibraryMessage.Failed -> stringResource(R.string.library_failed)
            }
            Text(text, style = BrutType.Body, color = BrutColors.Cream, modifier = Modifier.weight(1f))
            if (m is LibraryMessage.Trashed && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && m.take.file == null) {
                Text(
                    stringResource(R.string.library_undo).uppercase(),
                    style = BrutType.Legend,
                    color = BrutColors.Amber,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { actions.undoTrash(m.take) }
                        .padding(8.dp),
                )
            }
        }
    }
}
