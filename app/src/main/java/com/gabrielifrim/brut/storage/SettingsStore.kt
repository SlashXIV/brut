package com.gabrielifrim.brut.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gabrielifrim.brut.audio.AudioFormatSpec
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.MeterMode
import kotlinx.coroutines.flow.first

/** Réglages retrouvés d'une session à l'autre. */
data class SavedSettings(
    val format: AudioFormatSpec = AudioFormatSpec(),
    val gainDb: List<Float> = listOf(0f, 0f),
    val gainLinked: Boolean = true,
    /** Entrée choisie, identifiée par « type|nom » : l'identifiant Android change à chaque branchement. */
    val deviceKey: String? = null,
    val meterMode: MeterMode = MeterMode.PEAK,
)

private val Context.dataStore by preferencesDataStore(name = "reglages")

class SettingsStore(private val context: Context) {

    suspend fun load(): SavedSettings {
        val p = context.dataStore.data.first()
        val rate = p[RATE]?.takeIf { it in AudioFormatSpec.SUPPORTED_SAMPLE_RATES } ?: 48_000
        val depth = p[DEPTH]?.let { name -> BitDepth.entries.firstOrNull { it.name == name } } ?: BitDepth.PCM_24
        val channels = p[CHANNELS]?.takeIf { it == 1 || it == 2 } ?: 2
        return SavedSettings(
            format = AudioFormatSpec(rate, depth, channels),
            gainDb = listOf(p[GAIN_L] ?: 0f, p[GAIN_R] ?: 0f),
            gainLinked = p[LINKED] ?: true,
            deviceKey = p[DEVICE],
            meterMode = p[METER]?.let { name -> MeterMode.entries.firstOrNull { it.name == name } } ?: MeterMode.PEAK,
        )
    }

    suspend fun save(settings: SavedSettings) {
        context.dataStore.edit { p ->
            p[RATE] = settings.format.sampleRate
            p[DEPTH] = settings.format.bitDepth.name
            p[CHANNELS] = settings.format.channels
            p[GAIN_L] = settings.gainDb[0]
            p[GAIN_R] = settings.gainDb[1]
            p[LINKED] = settings.gainLinked
            p[METER] = settings.meterMode.name
            settings.deviceKey?.let { p[DEVICE] = it } ?: p.remove(DEVICE)
        }
    }

    private companion object {
        val RATE = intPreferencesKey("frequence")
        val DEPTH = stringPreferencesKey("resolution")
        val CHANNELS = intPreferencesKey("canaux")
        val GAIN_L: Preferences.Key<Float> = floatPreferencesKey("gain_g")
        val GAIN_R: Preferences.Key<Float> = floatPreferencesKey("gain_d")
        val LINKED = booleanPreferencesKey("gains_lies")
        val DEVICE = stringPreferencesKey("entree")
        val METER = stringPreferencesKey("affichage_mesure")
    }
}
