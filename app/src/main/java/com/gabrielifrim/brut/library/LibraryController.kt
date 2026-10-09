package com.gabrielifrim.brut.library

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.AudioFormatSpec
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.Bext
import com.gabrielifrim.brut.audio.ChannelPick
import com.gabrielifrim.brut.audio.ExportSpec
import com.gabrielifrim.brut.audio.Ixml
import com.gabrielifrim.brut.audio.IxmlSpeed
import com.gabrielifrim.brut.audio.LtcDecoder
import com.gabrielifrim.brut.audio.LtcFrame
import com.gabrielifrim.brut.audio.Timecode
import com.gabrielifrim.brut.audio.TimecodeRate
import com.gabrielifrim.brut.audio.WavReader
import com.gabrielifrim.brut.audio.WavExport
import com.gabrielifrim.brut.audio.WavInfo
import com.gabrielifrim.brut.storage.RecordingStorage
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.formatRate
import java.io.IOException
import java.text.Normalizer

enum class TakeSort { DATE, NAME, DURATION }

/** Message de la bibliothèque ; le texte est résolu par l'interface. */
sealed interface LibraryMessage {
    data class Trashed(val take: Take) : LibraryMessage
    data class Renamed(val name: String) : LibraryMessage
    data object Failed : LibraryMessage
    /** [count] fichiers créés ; [name] est celui du fichier quand il n'y en a qu'un. */
    data class Exported(val name: String, val count: Int, val clipped: Long) : LibraryMessage
    data class LtcStamped(val timecode: String) : LibraryMessage
    data object LtcNotFound : LibraryMessage
}

/** Sélection [start, end[ (en trames) en cours d'édition sur la prise ouverte. */
data class TrimState(val takeKey: String, val start: Long, val end: Long) {
    val frames: Long get() = end - start
}

data class LibraryState(
    val takes: List<Take> = emptyList(),
    val loading: Boolean = true,
    val query: String = "",
    val sort: TakeSort = TakeSort.DATE,
    val selectedKey: String? = null,
    val waveforms: Map<String, Waveform> = emptyMap(),
    val message: LibraryMessage? = null,
    /** Action système à faire confirmer (prise d'une installation précédente). */
    val consent: IntentSender? = null,
    val trim: TrimState? = null,
    /** Avancement de l'export en cours (0 à 1) ; null hors export. */
    val exportProgress: Float? = null,
) {
    /** Prises affichées : filtrées par la recherche puis triées. */
    val visible: List<Take>
        get() {
            val q = fold(query)
            val filtered = if (q.isBlank()) takes else takes.filter { t ->
                fold(t.name).contains(q) || fold(t.info?.description.orEmpty()).contains(q)
            }
            return when (sort) {
                TakeSort.DATE -> filtered.sortedByDescending { it.dateMillis }
                TakeSort.NAME -> filtered.sortedBy { fold(it.name) }
                TakeSort.DURATION -> filtered.sortedByDescending { it.info?.frames?.toDouble()?.div(it.info.sampleRate) ?: 0.0 }
            }
        }

    companion object {
        /** Recherche insensible à la casse et aux accents : « entree » trouve « Entrée ». */
        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }
}

class LibraryController(private val context: Context, customFolder: () -> android.net.Uri?) {

    private val repository = TakeRepository(context, customFolder)
    private val waveforms = WaveformCache(context)
    private val storage = RecordingStorage(context)
    private val appVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    val player = TakePlayer(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var waveJob: Job? = null
    private var exportJob: Job? = null
    /** Action à rejouer une fois l'accord du système obtenu. */
    private var pendingRetry: (suspend () -> Unit)? = null

    fun refresh() {
        scope.launch {
            _state.update { it.copy(loading = true) }
            val takes = repository.list()
            _state.update { s ->
                s.copy(
                    takes = takes,
                    loading = false,
                    selectedKey = s.selectedKey?.takeIf { key -> takes.any { it.key == key } },
                )
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setSort(sort: TakeSort) = _state.update { it.copy(sort = sort) }

    fun select(take: Take) {
        if (_state.value.exportProgress != null) return
        if (_state.value.selectedKey == take.key) {
            player.stop()
            _state.update { it.copy(selectedKey = null, trim = null) }
            return
        }
        _state.update { it.copy(selectedKey = take.key, trim = null) }
        player.load(take)
        if (take.key !in _state.value.waveforms) {
            waveJob?.cancel()
            waveJob = scope.launch {
                waveforms.get(take)?.let { w -> _state.update { it.copy(waveforms = it.waveforms + (take.key to w)) } }
            }
        }
    }

    fun rename(take: Take, newName: String) {
        val retry: suspend () -> Unit = { rename(take, newName) }
        scope.launch {
            handle(repository.rename(take, newName), retry) {
                _state.update { it.copy(message = LibraryMessage.Renamed(TakeRepository.sanitize(newName))) }
                refresh()
            }
        }
    }

    fun trash(take: Take) {
        if (_state.value.selectedKey == take.key) {
            player.stop()
            _state.update { it.copy(selectedKey = null) }
        }
        // Android 11+ : la demande système met elle-même à la corbeille, il suffit de recharger.
        // Android 10 : l'accord ne fait que débloquer l'écriture, il faut rejouer.
        val retry: suspend () -> Unit = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            { refresh() }
        } else {
            { trash(take) }
        }
        scope.launch {
            handle(repository.trash(take), retry) {
                _state.update { s -> s.copy(takes = s.takes - take, message = LibraryMessage.Trashed(take)) }
            }
        }
    }

    fun undoTrash(take: Take) {
        scope.launch {
            if (repository.restore(take)) refresh()
            _state.update { it.copy(message = null) }
        }
    }

    fun shareIntent(take: Take): Intent = repository.shareIntent(take)

    /** « Gauche » / « Droite » en stéréo, « Voie 3 » au-delà. */
    private fun channelName(channels: Int, index: Int): String = when {
        channels == 2 && index == 0 -> context.getString(R.string.channel_left_name)
        channels == 2 -> context.getString(R.string.channel_right_name)
        else -> context.getString(R.string.track_name, index + 1)
    }

    // --- Timecode -----------------------------------------------------------------

    /**
     * Lit le LTC enregistré sur la voie [channel] et cale l'heure de départ de la prise
     * dessus (bext + iXML, réécrits en place ; le son n'est pas touché).
     */
    fun stampFromLtc(take: Take, channel: Int) {
        val info = take.info ?: return
        if (info.bext == null) return
        val retry: suspend () -> Unit = { stampFromLtc(take, channel) }
        scope.launch {
            val found = withContext(Dispatchers.IO) { readLtc(take, info, channel) }
            if (found == null) {
                _state.update { it.copy(message = LibraryMessage.LtcNotFound) }
                return@launch
            }
            val (anchor, rate) = found
            val sr = info.sampleRate
            val tr = Math.floorMod(Timecode.samplesSinceMidnight(anchor.timecode, rate, sr) - anchor.startSample, 86_400L * sr)
            val speed = IxmlSpeed(rate, sr, info.bitsPerSample, tr)
            handle(repository.patchTimestamp(take, speed), retry) {
                _state.update { it.copy(message = LibraryMessage.LtcStamped(Timecode.fromSamples(tr, rate, sr).format(rate))) }
                refresh()
            }
        }
    }

    /** Première image validée (l'ancrage) et cadence mesurée sur la plus longue série. */
    private fun readLtc(take: Take, info: com.gabrielifrim.brut.audio.WavInfo, channel: Int): Pair<LtcFrame, TimecodeRate>? {
        val input = openChannel(context, take) ?: return null
        return input.use { ch ->
            val decoder = LtcDecoder(info.sampleRate)
            val block = 8192
            val samples = FloatArray(block * info.channels)
            val scratch = java.nio.ByteBuffer.allocate(block * info.blockAlign)
            var anchor: LtcFrame? = null
            var latest: LtcFrame? = null
            var validated = 0
            var frame = 0L
            // Deux secondes d'images validées suffisent à séparer 23,976 de 24.
            while (frame < info.frames && validated < 50) {
                val n = WavReader.readFrames(ch, info, frame, block, samples, scratch)
                if (n == 0) break
                decoder.process(samples, n, info.channels, channel.coerceIn(0, info.channels - 1), frame) { f, ok ->
                    if (ok) {
                        if (anchor == null) anchor = f
                        latest = f
                        validated++
                    }
                }
                frame += n
            }
            val a = anchor ?: return@use null
            a to (latest ?: a).rateAt(info.sampleRate)
        }
    }

    // --- Édition ------------------------------------------------------------------

    fun startTrim(take: Take) {
        val info = take.info ?: return
        if (info.frames == 0L) return
        _state.update { it.copy(trim = TrimState(take.key, 0, info.frames)) }
        player.setRegion(0, info.frames)
    }

    /** Déplace les poignées ; la sélection garde au moins 10 ms. */
    fun setTrim(start: Long, end: Long) {
        val trim = _state.value.trim ?: return
        val info = _state.value.takes.firstOrNull { it.key == trim.takeKey }?.info ?: return
        val min = (info.sampleRate / 100L).coerceAtMost(info.frames)
        val s = start.coerceIn(0, info.frames - min)
        val e = end.coerceIn(s + min, info.frames)
        _state.update { it.copy(trim = trim.copy(start = s, end = e)) }
        player.setRegion(s, e)
    }

    fun endTrim() {
        if (_state.value.exportProgress != null) return
        _state.update { it.copy(trim = null) }
        player.clearRegion()
    }

    /**
     * Exporte la sélection (ou, avec [split], chaque morceau délimité par les repères)
     * dans de nouveaux fichiers, rangés dans le dossier des prises. L'original n'est pas touché.
     */
    fun export(take: Take, bitDepth: BitDepth?, channels: ChannelPick, sampleRate: Int?, split: Boolean) {
        val info = take.info ?: return
        if (_state.value.exportProgress != null) return
        val trim = _state.value.trim?.takeIf { it.takeKey == take.key } ?: TrimState(take.key, 0, info.frames)
        player.pause()
        _state.update { it.copy(exportProgress = 0f) }
        val job = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val self = coroutineContext[Job]
                runCatching { writeExports(take, info, trim, bitDepth, channels, sampleRate, split) { self?.isActive != false } }
            }
            val message = outcome.fold(
                onSuccess = { (names, clipped) -> LibraryMessage.Exported(if (names.size == 1) names[0] else "", names.size, clipped) },
                onFailure = { LibraryMessage.Failed },
            )
            _state.update { it.copy(exportProgress = null, message = message) }
            if (outcome.isSuccess) refresh()
        }
        exportJob = job
    }

    /** Le fichier en cours est jeté ; les morceaux déjà terminés restent. */
    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(exportProgress = null) }
        refresh()
    }

    private fun writeExports(
        take: Take,
        info: WavInfo,
        trim: TrimState,
        bitDepth: BitDepth?,
        channels: ChannelPick,
        sampleRate: Int?,
        split: Boolean,
        isActive: () -> Boolean,
    ): Pair<List<String>, Long> {
        val ranges = if (split) WavExport.segments(info, trim.start, trim.end) else listOf(trim.start until trim.end)
        // « Séparées » : chaque voie dans son fichier mono ; sinon une seule passe.
        val picks: List<Int?> = when (channels) {
            ChannelPick.All -> listOf(null)
            is ChannelPick.One -> listOf(channels.index)
            ChannelPick.Separate -> (0 until info.channels).toList()
        }
        val jobs = ranges.withIndex().flatMap { (k, r) -> picks.map { c -> Triple(k, r, c) } }
        val total = jobs.sumOf { it.second.last - it.second.first + 1 }.coerceAtLeast(1)
        val names = mutableListOf<String>()
        var done = 0L
        var clipped = 0L
        var shown = -1
        for ((k, range, pick) in jobs) {
            val spec = ExportSpec(range.first, range.last + 1, bitDepth, pick, sampleRate)
            val target = WavExport.targetFormat(info, spec)
            val part = if (split) context.getString(R.string.export_suffix_part) + (k + 1) else context.getString(R.string.export_suffix_extract)
            val voice = if (channels == ChannelPick.Separate && pick != null) "_" + context.getString(R.string.export_suffix_channel, pick + 1) else ""
            val file = storage.createNamed(TakeRepository.sanitize("${take.baseName}_$part$voice"))
            try {
                val input = openChannel(context, take) ?: throw IOException("Prise illisible")
                val result = input.use {
                    WavExport.export(
                        it, info, spec, file.channel,
                        extraChunks = metadataFor(take, info, spec, target, file.displayName.removeSuffix(".wav")),
                        markers = WavExport.markersIn(info, spec.startFrame, spec.endFrame),
                        onProgress = { p ->
                            val percent = ((done + p * spec.frames) * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                _state.update { s -> s.copy(exportProgress = percent / 100f) }
                            }
                        },
                        isActive = isActive,
                    )
                }
                file.publish()
                clipped += result.clippedSamples
                names += file.displayName
            } catch (e: Throwable) {
                file.discard()
                throw e
            }
            done += spec.frames
        }
        return names to clipped
    }

    /** bext (si l'original en a un) et iXML : l'extrait dit d'où il vient et ce qu'on lui a fait. */
    private fun metadataFor(take: Take, info: WavInfo, spec: ExportSpec, target: AudioFormatSpec, name: String): List<Pair<String, ByteArray>> {
        val rate = info.sampleRate.toDouble()
        val channelName = spec.channel?.let { channelName(info.channels, it) }
        val origin = buildList {
            add(context.getString(R.string.export_note, take.baseName, formatDuration(spec.startFrame / rate), formatDuration(spec.endFrame / rate)))
            if (WavExport.resamples(info, spec)) add(context.getString(R.string.export_note_resampled, formatRate(target.sampleRate)))
            if (!WavExport.isBitExact(info, spec) && target.bitDepth != WavExport.sourceDepth(info)) {
                add(context.getString(R.string.export_note_convert, depthName(context, target.bitDepth)))
            }
            spec.channel?.let { c ->
                add(
                    if (info.channels == 2) context.getString(R.string.export_note_channel, channelName(2, c).lowercase())
                    else context.getString(R.string.export_note_channel_n, c + 1),
                )
            }
        }.joinToString(" ")
        val note = listOfNotNull(origin, info.description).joinToString("\n")
        val tracks = when {
            target.channels > 2 -> List(target.channels) { context.getString(R.string.track_name, it + 1) }
            target.channels == 2 -> listOf(context.getString(R.string.channel_left_name), context.getString(R.string.channel_right_name))
            channelName != null -> listOf(channelName)
            else -> listOf(context.getString(R.string.format_mono))
        }
        val bext = info.bext?.let { source ->
            val description = listOfNotNull(origin, source.description.takeIf { it.isNotBlank() }).joinToString(" ; ")
            WavExport.derivedBext(source, info, spec, target, name, "Brut $appVersion", description)
        }
        val speed = bext?.let { IxmlSpeed(info.timecodeRate ?: TimecodeRate.DEFAULT, target.sampleRate, target.bitDepth.bits, it.timeReference) }
        return listOfNotNull(
            bext?.let { Bext.CHUNK_ID to it.encode() },
            Ixml.CHUNK_ID to Ixml.encode(note, "Brut", name, tracks, speed),
        )
    }

    /** Résultat de la confirmation système : on rejoue l'action si l'utilisateur a accepté. */
    fun onConsentResult(granted: Boolean) {
        val retry = pendingRetry
        pendingRetry = null
        _state.update { it.copy(consent = null) }
        if (granted && retry != null) scope.launch { retry() } else refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private suspend fun handle(result: EditResult, retry: suspend () -> Unit, onDone: () -> Unit) {
        when (result) {
            EditResult.Done -> onDone()
            is EditResult.NeedsConsent -> {
                pendingRetry = retry
                _state.update { it.copy(consent = result.intent) }
            }
            EditResult.Failed -> _state.update { it.copy(message = LibraryMessage.Failed) }
        }
    }
}

/** « 16 bit », « 24 bit », « 32 bit flottant ». */
fun depthName(context: Context, depth: BitDepth): String =
    if (depth.isFloat) context.getString(R.string.export_depth_float) else context.getString(R.string.export_depth_int, depth.bits)
