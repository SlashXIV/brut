package com.gabrielifrim.brut.audio

/** Profondeur de bits du fichier WAV écrit. */
enum class BitDepth(val bits: Int, val bytesPerSample: Int, val isFloat: Boolean) {
    PCM_16(16, 2, false),
    PCM_24(24, 3, false),
    FLOAT_32(32, 4, true),
}

/** Format demandé par l'utilisateur pour le fichier. */
data class AudioFormatSpec(
    val sampleRate: Int = 48_000,
    val bitDepth: BitDepth = BitDepth.PCM_24,
    val channels: Int = 2,
) {
    init {
        require(sampleRate in SUPPORTED_SAMPLE_RATES) { "Fréquence non prise en charge : $sampleRate" }
        require(channels == 1 || channels == 2) { "Seuls le mono et la stéréo sont pris en charge" }
    }

    val bytesPerFrame: Int get() = channels * bitDepth.bytesPerSample
    val bytesPerSecond: Long get() = sampleRate.toLong() * bytesPerFrame

    companion object {
        val SUPPORTED_SAMPLE_RATES = listOf(44_100, 48_000, 96_000)
    }
}
