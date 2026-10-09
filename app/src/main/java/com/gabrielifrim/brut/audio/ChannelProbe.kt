package com.gabrielifrim.brut.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.max

/** Issue d'un essai du test des voies. */
enum class ProbeOutcome {
    /** Les voies 1 et 2 arrivent différentes : cette combinaison garde la stéréo. */
    SEPARATED,
    /** Même signal sur les deux voies : Android les a mélangées. */
    MIXED,
    /** Trop peu de signal pour juger. */
    SILENT,
    /** Android a capté une autre entrée que celle demandée. */
    REROUTED,
    /** L'appareil a refusé d'ouvrir l'entrée ainsi. */
    REFUSED,
}

data class ProbeResult(
    val recipe: CaptureRecipe,
    val outcome: ProbeOutcome,
    val reading: PairReading? = null,
    val routedName: String? = null,
)

/**
 * Test des voies : ouvre l'entrée choisie de chaque façon qu'Android permet (source,
 * encodage, masque de voies) et mesure si la voie 2 reçoit la même chose que la voie 1.
 * Sert à trouver, sur un téléphone donné, la combinaison qui ne mélange pas une entrée
 * USB stéréo — certains processeurs audio la traitent comme un micro de casque mono.
 *
 * Bloquant : à lancer hors du thread principal, capture normale arrêtée.
 */
class ChannelProbe(
    private val device: AudioDeviceInfo,
    private val sampleRate: Int,
) {
    /** Essaie chaque combinaison ; [onTrial] avant chacune, [cancelled] interroge l'arrêt. */
    fun run(
        candidates: List<CaptureRecipe> = CaptureRecipe.CANDIDATES,
        onTrial: (index: Int, recipe: CaptureRecipe) -> Unit = { _, _ -> },
        onResult: (ProbeResult) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): List<ProbeResult> = buildList {
        candidates.forEachIndexed { i, recipe ->
            if (cancelled()) return@buildList
            onTrial(i, recipe)
            val result = trial(recipe, cancelled)
            add(result)
            onResult(result)
        }
    }

    @SuppressLint("MissingPermission")
    private fun trial(recipe: CaptureRecipe, cancelled: () -> Boolean): ProbeResult {
        val e = recipe.encoding
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, e.androidEncoding)
        if (min <= 0) return ProbeResult(recipe, ProbeOutcome.REFUSED)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(recipe.source.androidSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(e.androidEncoding)
                        .apply { if (recipe.indexMask) setChannelIndexMask(0b11) else setChannelMask(AudioFormat.CHANNEL_IN_STEREO) }
                        .build(),
                )
                .setBufferSizeInBytes(max(min * 4, sampleRate / 5 * 2 * e.bytesPerSample))
                .build()
        } catch (_: Exception) {
            return ProbeResult(recipe, ProbeOutcome.REFUSED)
        }
        val effects = mutableListOf<AudioEffect>()
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return ProbeResult(recipe, ProbeOutcome.REFUSED)
            record.setPreferredDevice(device)
            // Mêmes conditions que la vraie capture : les effets exposés par Android sont coupés.
            val session = record.audioSessionId
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = false; effects += it }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = false; effects += it }
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = false; effects += it }
            try {
                record.startRecording()
            } catch (_: IllegalStateException) {
                return ProbeResult(recipe, ProbeOutcome.REFUSED)
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return ProbeResult(recipe, ProbeOutcome.REFUSED)

            val pair = ChannelPair()
            val frames = sampleRate / 50
            val floats = FloatArray(frames * 2)
            val shorts = if (e == CaptureEncoding.PCM_16) ShortArray(frames * 2) else null
            // Le début d'un flux traîne souvent un peu de silence ou un fondu : on l'écarte.
            val skip = sampleRate * 2 / 5
            val measure = sampleRate * 8 / 5
            var read = 0
            while (read < skip + measure && !cancelled()) {
                val n = if (shorts != null) {
                    record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING).also { k ->
                        for (j in 0 until max(k, 0)) floats[j] = SampleConverter.pcm16ToFloat(shorts[j])
                    }
                } else {
                    record.read(floats, 0, floats.size, AudioRecord.READ_BLOCKING)
                }
                if (n < 0) return ProbeResult(recipe, ProbeOutcome.REFUSED)
                val got = n / 2
                if (read >= skip) pair.add(floats, got, 2)
                read += got
            }
            val routed = record.routedDevice
            val reading = pair.reading()
            val outcome = when {
                routed != null && routed.id != device.id -> ProbeOutcome.REROUTED
                reading.verdict == Pairing.SILENT -> ProbeOutcome.SILENT
                reading.verdict == Pairing.IDENTICAL -> ProbeOutcome.MIXED
                else -> ProbeOutcome.SEPARATED
            }
            return ProbeResult(recipe, outcome, reading, routed?.productName?.toString())
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { record.stop() }
            record.release()
        }
    }
}
