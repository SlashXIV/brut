package com.gabrielifrim.brut.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime

class TimestampTest {

    @get:Rule val tmp = TemporaryFolder()

    private val format = AudioFormatSpec(48_000, BitDepth.PCM_24, 2)
    private val clock = 36_000L * 48_000 // 10:00:00 à l'horloge du téléphone
    private val bext = Bext("Entrée : test", "Brut", "Prise", LocalDateTime.of(2026, 10, 9, 10, 0), clock, Bext.codingHistoryFor(format, "Brut"))
    private val speed = IxmlSpeed(TimecodeRate.FPS_25, 48_000, 24, clock)

    private fun chunks(b: Bext = bext) = listOf(
        Bext.CHUNK_ID to b.encode(),
        Ixml.CHUNK_ID to Ixml.encode("Note", "Brut", "Prise", listOf("Gauche", "Droite"), speed),
    )

    private fun record(rewrite: ((WavWriter) -> Unit)? = null): File {
        val file = tmp.newFile()
        WavWriter(RandomAccessFile(file, "rw").channel, format, chunks()).use { w ->
            val bytes = ByteArray(4_800 * 6)
            w.write(bytes, bytes.size)
            rewrite?.invoke(w)
        }
        return file
    }

    private fun info(f: File) = RandomAccessFile(f, "r").channel.use { WavReader.readInfo(it) }

    @Test
    fun `l'iXML porte la cadence et l'heure, et se réécrit à taille constante`() {
        val body = Ixml.encode("Note", "Brut", "Prise", listOf("Gauche"), speed)
        assertThat(Ixml.decodeRate(body)).isEqualTo(TimecodeRate.FPS_25)
        val later = Ixml.withTimestamp(body, speed.copy(rate = TimecodeRate.FPS_29_97_DF, timeReference = 5_000_000_000L))!!
        assertThat(later.size).isEqualTo(body.size)
        assertThat(Ixml.decodeRate(later)).isEqualTo(TimecodeRate.FPS_29_97_DF)
        val xml = String(later, Charsets.UTF_8)
        assertThat(xml).contains("<TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_HI>1</TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_HI>")
        assertThat(xml).contains("<TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>${5_000_000_000L - (1L shl 32)}</TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>")
        assertThat(Ixml.decodeNote(later)).isEqualTo("Note")
    }

    @Test
    fun `un iXML sans réserve n'est jamais agrandi`() {
        val tight = "<?xml version=\"1.0\"?><BWFXML><NOTE>x</NOTE></BWFXML>".toByteArray()
        assertThat(Ixml.withTimestamp(tight, speed)).isNull()
    }

    @Test
    fun `iXML d'un autre enregistreur, seules les valeurs présentes changent`() {
        val foreign = ("<?xml version=\"1.0\"?><BWFXML><SPEED><TIMECODE_RATE>25/1</TIMECODE_RATE><TIMECODE_FLAG>NDF</TIMECODE_FLAG>" +
            "<TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_HI>0</TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_HI><TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>1</TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>" +
            "</SPEED></BWFXML>" + " ".repeat(20)).toByteArray()
        val out = Ixml.withTimestamp(foreign, speed.copy(timeReference = 2_505_595_200L))!!
        assertThat(out.size).isEqualTo(foreign.size)
        assertThat(String(out)).contains("<TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>2505595200</TIMESTAMP_SAMPLES_SINCE_MIDNIGHT_LO>")
    }

    @Test
    fun `calage à la fin de la prise, fichier encore ouvert`() {
        val ltc = Timecode.samplesSinceMidnight(Timecode(14, 30, 0, 12), TimecodeRate.FPS_25, 48_000)
        val file = record { w ->
            assertThat(w.rewriteChunk(Bext.CHUNK_ID, bext.copy(timeReference = ltc).encode())).isTrue()
            val body = chunks()[1].second
            assertThat(w.rewriteChunk(Ixml.CHUNK_ID, Ixml.withTimestamp(body, speed.copy(timeReference = ltc))!!)).isTrue()
        }
        val i = info(file)
        assertThat(i.bext!!.timeReference).isEqualTo(ltc)
        assertThat(i.timecodeRate).isEqualTo(TimecodeRate.FPS_25)
        assertThat(i.frames).isEqualTo(4_800)
    }

    @Test
    fun `calage après coup, en place, sans toucher au son`() {
        val file = record()
        val before = file.readBytes()
        val ltc = Timecode.samplesSinceMidnight(Timecode(9, 59, 58, 0), TimecodeRate.FPS_24, 48_000)
        RandomAccessFile(file, "rw").channel.use { ch ->
            assertThat(WavMetadata.patchTimestamp(ch, ch, speed.copy(rate = TimecodeRate.FPS_24, timeReference = ltc))).isTrue()
        }
        val after = file.readBytes()
        val i = info(file)
        assertThat(after.size).isEqualTo(before.size)
        assertThat(i.bext!!.timeReference).isEqualTo(ltc)
        assertThat(i.timecodeRate).isEqualTo(TimecodeRate.FPS_24)
        // Les octets audio sont identiques.
        val data = i.dataOffset.toInt() until (i.dataOffset + i.dataBytes).toInt()
        assertThat(after.sliceArray(data)).isEqualTo(before.sliceArray(data))
    }
}
