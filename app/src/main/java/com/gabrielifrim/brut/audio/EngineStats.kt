package com.gabrielifrim.brut.audio

import kotlin.math.max

/**
 * Santé du moteur de capture : combien du temps d'un bloc le traitement consomme, où en
 * est le tampon d'Android, et si des échantillons ont été perdus. Tout s'accumule sur le
 * thread audio sans allocation ; une synthèse est tirée une fois par seconde.
 *
 * Rien de tout cela ne touche au signal : on chronomètre et on compte, c'est tout.
 */
class EngineStats(
    private val sampleRate: Int,
    /** Capacité du tampon d'`AudioRecord`, en trames. */
    private val bufferFrames: Int,
) {
    data class Snapshot(
        /** Charge du processus sur toute la machine (tous cœurs), en %. Null au premier relevé. */
        val cpuPercent: Float?,
        /** Temps de traitement d'un bloc rapporté à sa durée : moyenne et pire de la seconde. */
        val dspAverage: Float,
        val dspPeak: Float,
        /** Pire écriture disque de la seconde, en ms (null si aucune prise en cours). */
        val writeWorstMs: Float?,
        /** Taille du tampon d'Android, en ms. */
        val bufferMs: Float,
        /** Remplissage du tampon (0..1) ; null si l'appareil ne donne pas d'horodatage. */
        val bufferFill: Float?,
        /** Trames perdues (tampon débordé) depuis le début de la prise ou de la capture. */
        val lostFrames: Long,
        /** Les mêmes pertes en ms, pour l'affichage. */
        val lostMs: Float,
    )

    private var blocks = 0
    private var workSum = 0.0
    private var workPeak = 0.0
    private var writeWorstNanos = -1L

    private var lastCpuMillis = -1L
    private var lastWallNanos = 0L
    private var cpuPercent: Float? = null

    private var fill: Float? = null
    /** Avance du matériel sur l'appli au premier relevé : ce n'est pas une perte. */
    private var baseline = Long.MIN_VALUE
    private var lostTotal = 0L
    private var lostAtMark = 0L

    /** Un bloc de [frames] trames a demandé [workNanos] de traitement, dont [writeNanos] d'écriture (-1 : pas de fichier). */
    fun block(frames: Int, workNanos: Long, writeNanos: Long) {
        val budget = frames * 1_000_000_000.0 / sampleRate
        val load = workNanos / budget
        blocks++
        workSum += load
        workPeak = max(workPeak, load)
        if (writeNanos >= 0) writeWorstNanos = max(writeWorstNanos, writeNanos)
    }

    /**
     * Horodatage matériel : [hardwareFrames] trames captées par le matériel, [appFrames] lues
     * par l'appli. L'écart est ce qui attend dans le tampon ; s'il dépasse la capacité, le
     * surplus a été écrasé, donc perdu. Null : l'appareil ne fournit pas d'horodatage.
     */
    fun timestamp(hardwareFrames: Long?, appFrames: Long) {
        if (hardwareFrames == null) {
            fill = null
            return
        }
        val raw = hardwareFrames - appFrames
        if (baseline == Long.MIN_VALUE) baseline = max(0L, raw - bufferFrames)
        val lag = raw - baseline - lostTotal
        if (lag > bufferFrames) lostTotal += lag - bufferFrames
        fill = (lag.coerceIn(0L, bufferFrames.toLong()).toFloat() / bufferFrames)
    }

    /** Temps processeur cumulé du processus ([cpuMillis]) relevé à l'instant [wallNanos]. */
    fun cpu(cpuMillis: Long, wallNanos: Long, cores: Int) {
        if (lastCpuMillis >= 0 && wallNanos > lastWallNanos) {
            val wallMillis = (wallNanos - lastWallNanos) / 1_000_000.0
            cpuPercent = ((cpuMillis - lastCpuMillis) / wallMillis / max(cores, 1) * 100).toFloat().coerceIn(0f, 100f)
        }
        lastCpuMillis = cpuMillis
        lastWallNanos = wallNanos
    }

    /** Remet le compteur de pertes à zéro (début de prise). */
    fun markTake() {
        lostAtMark = lostTotal
    }

    /** Synthèse de la fenêtre écoulée, puis remise à zéro de la fenêtre. */
    fun snapshot(): Snapshot {
        val s = Snapshot(
            cpuPercent = cpuPercent,
            dspAverage = if (blocks > 0) (workSum / blocks).toFloat() else 0f,
            dspPeak = workPeak.toFloat(),
            writeWorstMs = if (writeWorstNanos >= 0) writeWorstNanos / 1_000_000f else null,
            bufferMs = bufferFrames * 1000f / sampleRate,
            bufferFill = fill,
            lostFrames = lostTotal - lostAtMark,
            lostMs = (lostTotal - lostAtMark) * 1000f / sampleRate,
        )
        blocks = 0
        workSum = 0.0
        workPeak = 0.0
        writeWorstNanos = -1L
        return s
    }
}
