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
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.ChannelPick
import com.gabrielifrim.brut.audio.LevelMeter
import com.gabrielifrim.brut.library.LibraryMessage
import com.gabrielifrim.brut.library.LibraryState
import com.gabrielifrim.brut.library.PlayerState
import com.gabrielifrim.brut.library.Take
import com.gabrielifrim.brut.library.TakeSort
import com.gabrielifrim.brut.library.Waveform
import com.gabrielifrim.brut.ui.console.ActionButton
import com.gabrielifrim.brut.ui.console.Lamp
import com.gabrielifrim.brut.ui.console.chassis
import com.gabrielifrim.brut.ui.console.recess
import com.gabrielifrim.brut.ui.console.RackPlate
import com.gabrielifrim.brut.ui.console.Readout
import com.gabrielifrim.brut.ui.console.engraved
import com.gabrielifrim.brut.ui.console.meterPosition
import com.gabrielifrim.brut.ui.formatBytes
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutShapes
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
    fun chooseFolder()
    fun resetFolder()
    fun consumeMessage()
    fun startTrim(take: Take)
    fun setTrim(start: Long, end: Long)
    fun endTrim()
    fun export(take: Take, bitDepth: BitDepth?, channels: ChannelPick, sampleRate: Int?, split: Boolean)
    fun cancelExport()
    fun stampFromLtc(take: Take, channel: Int)
}

private fun dateFormat(locale: Locale): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", locale)

@Composable
fun LibraryScreen(
    state: LibraryState,
    player: PlayerState,
    canReadAll: Boolean,
    recording: Boolean,
    folderLabel: String,
    customFolder: Boolean,
    actions: LibraryActions,
) {
    BackHandler(onBack = actions::back)
    var renaming by remember { mutableStateOf<Take?>(null) }
    var stamping by remember { mutableStateOf<Take?>(null) }
    var deleting by remember { mutableStateOf<Take?>(null) }
    val listState = rememberLazyListState()
    // Une prise ouverte se déplie en plaque : si elle déborde sous l'écran, on remonte la
    // liste juste assez pour voir ses boutons, sans perdre son en-tête.
    LaunchedEffect(state.selectedKey) {
        val key = state.selectedKey ?: return@LaunchedEffect
        delay(120)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@LaunchedEffect
        val overflow = item.offset + item.size - info.viewportEndOffset
        if (overflow > 0) listState.animateScrollBy(minOf(overflow, item.offset).toFloat())
    }

    Box(
        Modifier
            .fillMaxSize()
            .chassis()
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TopBar(state.takes.size, actions::back)
            Spacer(Modifier.height(10.dp))
            FolderPlate(folderLabel, customFolder, enabled = !recording, actions)
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
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visible, key = { it.key }) { take ->
                        if (take.key == state.selectedKey) {
                            MountedTake(
                                take = take,
                                waveform = state.waveforms[take.key],
                                trim = state.trim?.takeIf { it.takeKey == take.key },
                                exportProgress = state.exportProgress,
                                player = player,
                                recording = recording,
                                actions = actions,
                                onRename = { renaming = take },
                                onStamp = { stamping = take },
                                // Sans corbeille possible, la suppression est définitive : on confirme.
                                onDelete = { if (take.canUndoDelete) actions.trash(take) else deleting = take },
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

    deleting?.let { take ->
        ConfirmDeleteDialog(
            name = take.baseName,
            onConfirm = { actions.trash(take); deleting = null },
            onDismiss = { deleting = null },
        )
    }

    stamping?.let { take ->
        StampDialog(
            channels = take.info?.channels ?: 1,
            onChannel = { actions.stampFromLtc(take, it); stamping = null },
            onDismiss = { stamping = null },
        )
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
                Text(stringResource(R.string.library_search), style = BrutType.Readout, color = BrutColors.CreamDim)
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
            Text(stringResource(R.string.library_sort).uppercase() + " ", style = BrutType.Legend, color = BrutColors.CreamDim)
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
            .recess()
            .clip(RoundedCornerShape(BrutShapes.Plate))
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
        // La langue suit la configuration : un changement de langue redessine la date.
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        val date = Instant.ofEpochMilli(take.dateMillis).atZone(ZoneId.systemDefault()).format(dateFormat(locale))
        Text(date.uppercase(locale), style = engraved(BrutType.Legend), color = BrutColors.Amber)
        Text(take.baseName, style = engraved(BrutType.BodyStrong), color = BrutColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatLine(take), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim, maxLines = 1)
    }
}

@Composable
private fun formatLine(take: Take): String {
    val i = take.info ?: return stringResource(R.string.library_unreadable) + " · " + formatBytes(take.sizeBytes)
    val depth = if (i.isFloat) "32F" else "${i.bitsPerSample}"
    val ch = com.gabrielifrim.brut.ui.console.channelsLabel(i.channels)
    return "${formatRate(i.sampleRate)} · $depth BIT · $ch · ${formatBytes(take.sizeBytes)}"
}

/** La prise sélectionnée, « montée » sur une plaque : forme d'onde, lecture, actions. */
@Composable
private fun MountedTake(
    take: Take,
    waveform: Waveform?,
    trim: com.gabrielifrim.brut.library.TrimState?,
    exportProgress: Float?,
    player: PlayerState,
    recording: Boolean,
    actions: LibraryActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onStamp: () -> Unit,
) {
    RackPlate(Modifier.fillMaxWidth(), contentPadding = 10.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TakeHeader(take, Modifier.weight(1f).clickable { actions.select(take) })
            }
            val info = take.info
            if (info != null && trim != null) {
                TrimPanel(take, info, trim, waveform, player, exportProgress, recording, actions)
                return@Column
            }
            val markerFractions = info?.takeIf { it.frames > 0 }?.markers?.map { it.frame.toFloat() / info.frames }.orEmpty()
            WaveformView(waveform, player.fraction, markerFractions, enabled = info != null, onSeek = actions::seek)
            // Repères posés pendant la prise (coupure du micro, reprise…) : toucher = s'y placer.
            info?.markers?.forEach { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .clickable { actions.seek(m.frame.toFloat() / info.frames) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("▾ " + formatDuration(m.frame.toDouble() / info.sampleRate, withHundredths = false), style = BrutType.ReadoutSmall, color = BrutColors.Amber)
                    Spacer(Modifier.width(10.dp))
                    Text(m.label, style = BrutType.Body, color = BrutColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            PlaybackRow(take, player, enabled = !recording, actions)
            info?.bext?.let { b ->
                val rate = info.timecodeRate ?: com.gabrielifrim.brut.audio.TimecodeRate.DEFAULT
                val tc = com.gabrielifrim.brut.audio.Timecode.fromSamples(b.timeReference, rate, info.sampleRate).format(rate)
                Text(stringResource(R.string.library_tc, tc, rate.label), style = BrutType.ReadoutSmall, color = BrutColors.Amber)
            }
            if ((info?.channels ?: 0) > 2) {
                Text(stringResource(R.string.library_multi_playback, info!!.channels), style = BrutType.Body, color = BrutColors.CreamDim)
            }
            if (recording) {
                Text(stringResource(R.string.library_recording_busy), style = BrutType.Body, color = BrutColors.Amber)
            }
            take.info?.description?.let {
                Text(it, style = BrutType.Body, color = BrutColors.CreamDim)
            }
            // L'édition ne crée que de nouveaux fichiers : elle reste possible sur toute prise lisible.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.library_edit), BrutColors.Amber, Modifier.weight(1f), enabled = (info?.frames ?: 0L) > 0L) {
                    actions.startTrim(take)
                }
                // Le calage réécrit l'heure du bext : sans bext (fichier étranger), rien à caler.
                ActionButton(stringResource(R.string.library_ltc_stamp), BrutColors.Amber, Modifier.weight(1f), enabled = info?.bext != null && !recording, onClick = onStamp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.library_rename), BrutColors.Cream, Modifier.weight(1f), onClick = onRename)
                ActionButton(stringResource(R.string.library_share), BrutColors.Cream, Modifier.weight(1f)) { actions.share(take) }
                ActionButton(stringResource(R.string.library_delete), BrutColors.Red, Modifier.weight(1f), onClick = onDelete)
            }
        }
    }
}

/**
 * Forme d'onde sur l'échelle des crête-mètres (IEC) : une prise calme reste lisible,
 * et rien n'est normalisé. Les crêtes saturées sont en rouge. Toucher ou glisser = se placer.
 */
@Composable
private fun WaveformView(waveform: Waveform?, fraction: Float, markers: List<Float>, enabled: Boolean, onSeek: (Float) -> Unit) {
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
        markers.forEach { f ->
            val mx = f * size.width
            drawLine(BrutColors.Amber.copy(alpha = 0.7f), Offset(mx, 0f), Offset(mx, size.height), 1.dp.toPx())
            val t = 5.dp.toPx()
            drawPath(Path().apply { moveTo(mx - t, 0f); lineTo(mx + t, 0f); lineTo(mx, t * 1.4f); close() }, BrutColors.Amber)
        }
        val x = fraction * size.width
        drawLine(BrutColors.Cream, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    }
}

@Composable
internal fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
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

/** Dossier de destination des prises, et de quoi le changer. */
@Composable
private fun FolderPlate(label: String, custom: Boolean, enabled: Boolean, actions: LibraryActions) {
    RackPlate(Modifier.fillMaxWidth(), contentPadding = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.folder_title).uppercase(), style = engraved(BrutType.Legend), color = BrutColors.CreamDim)
                // Le nom du dossier en grand, son chemin en petit : un long chemin ne masque plus l'essentiel.
                Text(label.substringAfterLast('/'), style = engraved(BrutType.BodyStrong), color = BrutColors.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if ('/' in label) {
                    Text(label.substringBeforeLast('/'), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (custom) {
                SmallAction(stringResource(R.string.folder_default), enabled, actions::resetFolder)
                Spacer(Modifier.width(6.dp))
            }
            SmallAction(stringResource(R.string.folder_change), enabled, actions::chooseFolder)
        }
    }
}

@Composable
private fun SmallAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text.uppercase(),
        style = engraved(BrutType.Legend),
        color = if (enabled) BrutColors.Amber else BrutColors.CreamFaint,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BrutColors.Recess)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun ConfirmDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        RackPlate(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.library_delete_title), style = BrutType.Title, color = BrutColors.Cream)
                Text(stringResource(R.string.library_delete_body, name), style = BrutType.Body, color = BrutColors.CreamDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(R.string.library_cancel), BrutColors.CreamDim, Modifier.weight(1f), onClick = onDismiss)
                    ActionButton(stringResource(R.string.library_delete), BrutColors.Red, Modifier.weight(1f), onClick = onConfirm)
                }
            }
        }
    }
}

/** Choix de la voie qui porte le LTC, et ce que le calage fait (et ne fait pas). */
@Composable
private fun StampDialog(channels: Int, onChannel: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        RackPlate(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.library_ltc_stamp), style = BrutType.Title, color = BrutColors.Cream)
                Text(stringResource(R.string.library_ltc_body), style = BrutType.Body, color = BrutColors.CreamDim)
                if (channels > 2) {
                    // Une touche par voie, par rangées de quatre.
                    (0 until channels).chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { c ->
                                ActionButton(stringResource(R.string.track_name, c + 1), BrutColors.Amber, Modifier.weight(1f)) { onChannel(c) }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else if (channels == 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.library_ltc_left), BrutColors.Amber, Modifier.weight(1f)) { onChannel(0) }
                        ActionButton(stringResource(R.string.library_ltc_right), BrutColors.Amber, Modifier.weight(1f)) { onChannel(1) }
                    }
                } else {
                    ActionButton(stringResource(R.string.library_ltc_stamp), BrutColors.Amber, Modifier.fillMaxWidth()) { onChannel(0) }
                }
                ActionButton(stringResource(R.string.library_cancel), BrutColors.CreamDim, Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
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
                    ActionButton(stringResource(R.string.library_cancel), BrutColors.CreamDim, Modifier.weight(1f), onClick = onDismiss)
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
            kotlinx.coroutines.delay(if (message is LibraryMessage.Trashed || message is LibraryMessage.Exported) 6000 else 3000)
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
                .semantics { liveRegion = LiveRegionMode.Polite }
                .drawBehind { drawRect(if (m is LibraryMessage.Failed || m is LibraryMessage.LtcNotFound || (m is LibraryMessage.Exported && m.clipped > 0)) BrutColors.Red else BrutColors.Amber, Offset.Zero, size.copy(width = 4.dp.toPx())) }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = when (m) {
                is LibraryMessage.Trashed -> stringResource(R.string.library_trashed, m.take.baseName)
                is LibraryMessage.Renamed -> stringResource(R.string.library_renamed, m.name)
                LibraryMessage.Failed -> stringResource(R.string.library_failed)
                is LibraryMessage.LtcStamped -> stringResource(R.string.library_ltc_done, m.timecode)
                LibraryMessage.LtcNotFound -> stringResource(R.string.library_ltc_missing)
                is LibraryMessage.Exported -> {
                    val done = if (m.count == 1) {
                        stringResource(R.string.library_exported, m.name)
                    } else {
                        androidx.compose.ui.res.pluralStringResource(R.plurals.library_exported_parts, m.count, m.count)
                    }
                    if (m.clipped > 0) {
                        val n = m.clipped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        done + " — " + androidx.compose.ui.res.pluralStringResource(R.plurals.library_exported_clipped, n, n)
                    } else {
                        done
                    }
                }
            }
            Text(text, style = BrutType.Body, color = BrutColors.Cream, modifier = Modifier.weight(1f))
            if (m is LibraryMessage.Trashed && m.take.canUndoDelete) {
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
