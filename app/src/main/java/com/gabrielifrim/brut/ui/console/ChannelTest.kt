package com.gabrielifrim.brut.ui.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.CaptureEncoding
import com.gabrielifrim.brut.audio.CaptureRecipe
import com.gabrielifrim.brut.audio.CaptureSource
import com.gabrielifrim.brut.audio.ChannelTestState
import com.gabrielifrim.brut.audio.PairReading
import com.gabrielifrim.brut.audio.ProbeOutcome
import com.gabrielifrim.brut.audio.ProbeResult
import com.gabrielifrim.brut.ui.formatDb
import com.gabrielifrim.brut.ui.formatRate
import com.gabrielifrim.brut.ui.theme.BrutColors
import com.gabrielifrim.brut.ui.theme.BrutType
import java.util.Locale

/** Une combinaison en mots : « Brut · flottant · position ». */
fun recipeText(context: Context, r: CaptureRecipe): String = listOf(
    context.getString(
        when (r.source) {
            CaptureSource.UNPROCESSED -> R.string.tag_raw
            CaptureSource.VOICE_RECOGNITION -> R.string.tag_no_agc
            CaptureSource.MIC -> R.string.tag_standard
            CaptureSource.CAMCORDER -> R.string.tag_camcorder
        },
    ),
    context.getString(if (r.encoding == CaptureEncoding.FLOAT) R.string.recipe_float else R.string.recipe_pcm16),
    context.getString(if (r.indexMask) R.string.recipe_index else R.string.recipe_position),
).joinToString(" · ")

@Composable
fun recipeLabel(r: CaptureRecipe): String = listOf(
    stringResource(
        when (r.source) {
            CaptureSource.UNPROCESSED -> R.string.tag_raw
            CaptureSource.VOICE_RECOGNITION -> R.string.tag_no_agc
            CaptureSource.MIC -> R.string.tag_standard
            CaptureSource.CAMCORDER -> R.string.tag_camcorder
        },
    ),
    stringResource(if (r.encoding == CaptureEncoding.FLOAT) R.string.recipe_float else R.string.recipe_pcm16),
    stringResource(if (r.indexMask) R.string.recipe_index else R.string.recipe_position),
).joinToString(" · ")

private fun outcomeText(context: Context, o: ProbeOutcome): String = context.getString(
    when (o) {
        ProbeOutcome.SEPARATED -> R.string.outcome_separated
        ProbeOutcome.MIXED -> R.string.outcome_mixed
        ProbeOutcome.SILENT -> R.string.outcome_silent
        ProbeOutcome.REROUTED -> R.string.outcome_rerouted
        ProbeOutcome.REFUSED -> R.string.outcome_refused
    },
)

private fun outcomeColor(o: ProbeOutcome): Color = when (o) {
    ProbeOutcome.SEPARATED -> BrutColors.Green
    ProbeOutcome.MIXED -> BrutColors.Red
    ProbeOutcome.SILENT, ProbeOutcome.REROUTED -> BrutColors.Amber
    ProbeOutcome.REFUSED -> BrutColors.CreamFaint
}

private fun levelsText(context: Context, r: PairReading): String = context.getString(
    R.string.channel_test_levels,
    formatDb(r.leftDb) ?: "−∞",
    formatDb(r.rightDb) ?: "−∞",
    String.format(Locale.getDefault(), "%.2f", r.correlation),
)

/** Rapport en texte brut, à coller dans un message : de quoi diagnostiquer à distance. */
private fun report(context: Context, test: ChannelTestState): String = buildString {
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    appendLine(context.getString(R.string.channel_test_report_title, version))
    appendLine(context.getString(R.string.channel_test_report_phone, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE))
    appendLine(context.getString(R.string.channel_test_report_input, context.getString(R.string.channel_test_input, test.deviceName, formatRate(test.sampleRate))))
    test.results.forEach { r ->
        append(recipeText(context, r.recipe)).append(" : ").append(outcomeText(context, r.outcome).uppercase())
        r.reading?.let { append(" (").append(levelsText(context, it)).append(')') }
        r.routedName?.takeIf { r.outcome == ProbeOutcome.REROUTED }?.let { append(" → ").append(it) }
        appendLine()
    }
}

/**
 * Test des voies d'une entrée : consignes, avancement essai par essai, puis verdict et
 * de quoi retenir la bonne combinaison ou partager le rapport.
 */
@Composable
fun ChannelTestSheet(
    deviceName: String,
    sampleRate: Int,
    test: ChannelTestState?,
    activeRecipe: CaptureRecipe?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUse: (CaptureRecipe) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BrutSheet(onDismiss) {
        Text(stringResource(R.string.channel_test_title), style = BrutType.Title)
        Text(stringResource(R.string.channel_test_intro), style = BrutType.Body, color = BrutColors.CreamDim)
        Spacer(Modifier.height(12.dp))
        Readout(stringResource(R.string.channel_test_input, deviceName, formatRate(test?.sampleRate ?: sampleRate)))
        Spacer(Modifier.height(12.dp))

        val running = test?.running == true
        val done = test != null && !running && test.results.isNotEmpty()
        when {
            running -> {
                val current = test.current
                Text(
                    stringResource(R.string.channel_test_progress, test.trial + 1, test.total, current?.let { recipeLabel(it) } ?: ""),
                    style = BrutType.Body, color = BrutColors.Amber,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(10.dp))
                ActionButton(stringResource(R.string.channel_test_stop), BrutColors.Red, Modifier.fillMaxWidth(), onClick = onStop)
            }
            done -> Verdict(test, activeRecipe, onUse)
            else -> {
                val seconds = (CaptureRecipe.CANDIDATES.size * 2.4).toInt() / 5 * 5 + 5
                Text(stringResource(R.string.channel_test_steps, seconds), style = BrutType.Body, color = BrutColors.Cream)
                Spacer(Modifier.height(12.dp))
                ActionButton(stringResource(R.string.channel_test_start), BrutColors.Amber, Modifier.fillMaxWidth(), onClick = onStart)
            }
        }

        if (test != null && test.results.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            RackPlate(Modifier.fillMaxWidth(), contentPadding = 8.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    test.results.forEach { ResultRow(it) }
                }
            }
        }
        if (done) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.channel_test_again), BrutColors.Cream, Modifier.weight(1f), onClick = onStart)
                val clipLabel = stringResource(R.string.channel_test_title)
                ActionButton(stringResource(R.string.channel_test_copy), BrutColors.Cream, Modifier.weight(1f)) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(clipLabel, report(context, test)))
                }
            }
        }
    }
}

@Composable
private fun Verdict(test: ChannelTestState, activeRecipe: CaptureRecipe?, onUse: (CaptureRecipe) -> Unit) {
    val best = test.best
    val anyMixed = test.results.any { it.outcome == ProbeOutcome.MIXED }
    val (text, color) = when {
        best != null -> stringResource(R.string.channel_test_found, recipeLabel(best)) to BrutColors.Green
        anyMixed -> stringResource(R.string.channel_test_all_mixed) to BrutColors.Red
        else -> stringResource(R.string.channel_test_inconclusive) to BrutColors.Amber
    }
    Text(text, style = BrutType.Body, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (best != null) {
        Spacer(Modifier.height(10.dp))
        if (best == activeRecipe) {
            Text(stringResource(R.string.channel_test_in_use), style = engraved(BrutType.Legend), color = BrutColors.Green)
        } else {
            ActionButton(stringResource(R.string.channel_test_use), BrutColors.Amber, Modifier.fillMaxWidth()) { onUse(best) }
        }
    }
}

@Composable
private fun ResultRow(result: ProbeResult) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(recipeLabel(result.recipe), style = engraved(BrutType.Legend), color = BrutColors.Cream)
            result.reading?.let { Text(levelsText(context, it), style = BrutType.ReadoutSmall, color = BrutColors.CreamDim) }
        }
        Spacer(Modifier.width(8.dp))
        Text(outcomeText(context, result.outcome).uppercase(), style = engraved(BrutType.Legend), color = outcomeColor(result.outcome))
    }
}
