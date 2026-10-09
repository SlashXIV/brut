package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EngineStatsTest {

    private val rate = 48_000
    private val block = rate / 50 // 20 ms
    private val ms = 1_000_000L

    @Test
    fun `la charge rapporte le traitement à la durée du bloc, moyenne et pire`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        stats.block(block, workNanos = 2 * ms, writeNanos = -1) // 10 %
        stats.block(block, workNanos = 6 * ms, writeNanos = -1) // 30 %
        val s = stats.snapshot()
        assertThat(s.dspAverage).isWithin(1e-4f).of(0.2f)
        assertThat(s.dspPeak).isWithin(1e-4f).of(0.3f)
        assertThat(s.writeWorstMs).isNull()
        assertThat(s.bufferMs).isWithin(1e-3f).of(200f)
    }

    @Test
    fun `la fenêtre repart de zéro après chaque synthèse`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        stats.block(block, workNanos = 10 * ms, writeNanos = 3 * ms)
        stats.block(block, workNanos = 4 * ms, writeNanos = 7 * ms)
        assertThat(stats.snapshot().writeWorstMs).isWithin(1e-4f).of(7f)
        val empty = stats.snapshot()
        assertThat(empty.dspAverage).isEqualTo(0f)
        assertThat(empty.dspPeak).isEqualTo(0f)
        assertThat(empty.writeWorstMs).isNull()
    }

    @Test
    fun `le remplissage du tampon suit l'avance du matériel sur l'appli`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        stats.timestamp(hardwareFrames = 48_000, appFrames = 48_000)
        assertThat(stats.snapshot().bufferFill).isWithin(1e-4f).of(0f)
        stats.timestamp(hardwareFrames = 100_800, appFrames = 96_000) // 4 800 en attente
        val s = stats.snapshot()
        assertThat(s.bufferFill).isWithin(1e-4f).of(0.5f)
        assertThat(s.lostFrames).isEqualTo(0)
    }

    @Test
    fun `sans horodatage, le remplissage est inconnu`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        stats.timestamp(hardwareFrames = null, appFrames = 1_000)
        assertThat(stats.snapshot().bufferFill).isNull()
    }

    @Test
    fun `un tampon débordé compte le surplus comme perdu, une seule fois`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        stats.timestamp(hardwareFrames = 0, appFrames = 0)
        // L'appli a pris 0,3 s de retard sur une réserve de 0,2 s : 0,1 s écrasée.
        stats.timestamp(hardwareFrames = 62_400, appFrames = 48_000)
        val s = stats.snapshot()
        assertThat(s.lostFrames).isEqualTo(4_800)
        assertThat(s.lostMs).isWithin(1e-3f).of(100f)
        assertThat(s.bufferFill).isWithin(1e-4f).of(1f)
        // L'appli a rattrapé : le trou reste compté, mais n'est pas recompté.
        stats.timestamp(hardwareFrames = 110_400, appFrames = 105_600)
        val after = stats.snapshot()
        assertThat(after.lostFrames).isEqualTo(4_800)
        assertThat(after.bufferFill).isWithin(1e-4f).of(0f)
    }

    @Test
    fun `l'avance du matériel au premier relevé n'est pas une perte`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        // Le matériel tournait déjà avant la première lecture.
        stats.timestamp(hardwareFrames = 30_000, appFrames = 0)
        assertThat(stats.snapshot().lostFrames).isEqualTo(0)
    }

    @Test
    fun `les pertes repartent de zéro au début d'une prise`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        stats.timestamp(hardwareFrames = 0, appFrames = 0)
        stats.timestamp(hardwareFrames = 62_400, appFrames = 48_000)
        assertThat(stats.snapshot().lostFrames).isEqualTo(4_800)
        stats.markTake()
        assertThat(stats.snapshot().lostFrames).isEqualTo(0)
    }

    @Test
    fun `le processeur est rapporté à tous les cœurs de l'appareil`() {
        val stats = EngineStats(rate, bufferFrames = 9_600)
        stats.cpu(cpuMillis = 1_000, wallNanos = 0, cores = 8)
        assertThat(stats.snapshot().cpuPercent).isNull()
        // 400 ms de processeur en 1 s, sur 8 cœurs : 5 % de l'appareil.
        stats.cpu(cpuMillis = 1_400, wallNanos = 1_000 * ms, cores = 8)
        assertThat(stats.snapshot().cpuPercent).isWithin(1e-3f).of(5f)
    }
}
