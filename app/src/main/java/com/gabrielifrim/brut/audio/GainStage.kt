package com.gabrielifrim.brut.audio

/**
 * Gain numérique par canal, réglé par l'utilisateur et lui seul.
 *
 * À 0 dB, [apply] ne touche à aucun échantillon : le signal reste bit-exact.
 * Un changement de gain est interpolé sur un bloc pour éviter le « zipper noise »
 * (le claquement d'un saut de gain brutal).
 */
class GainStage(private val channels: Int) {

    @Volatile private var targetDb = FloatArray(channels)
    private val current = FloatArray(channels) { 1f }

    fun setGainDb(channel: Int, db: Float) {
        val next = targetDb.copyOf()
        next[channel] = db.coerceIn(MIN_DB, MAX_DB)
        targetDb = next
    }

    fun gainDb(channel: Int): Float = targetDb[channel]

    /** Applique le gain en place. Retourne faux si le signal est resté intact. */
    fun apply(interleaved: FloatArray, frames: Int): Boolean {
        val targets = targetDb
        var touched = false
        for (c in 0 until channels) {
            val target = LevelMeter.dbToLinear(targets[c])
            val start = current[c]
            if (start == 1f && targets[c] == 0f) continue
            touched = true
            val step = (target - start) / frames
            var g = start
            var i = c
            for (f in 0 until frames) {
                g += step
                interleaved[i] *= g
                i += channels
            }
            // Recaler exactement sur la cible : à 0 dB, on retrouve le chemin bit-exact.
            current[c] = if (targets[c] == 0f) 1f else target
        }
        return touched
    }

    companion object {
        const val MIN_DB = -24f
        const val MAX_DB = 24f
    }
}
