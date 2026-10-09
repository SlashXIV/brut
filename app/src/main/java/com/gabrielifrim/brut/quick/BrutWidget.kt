package com.gabrielifrim.brut.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.gabrielifrim.brut.BrutApp
import com.gabrielifrim.brut.MainActivity
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.audio.BitDepth
import com.gabrielifrim.brut.audio.LevelMeter
import com.gabrielifrim.brut.audio.RecorderState
import com.gabrielifrim.brut.service.RecordingService
import com.gabrielifrim.brut.ui.console.meterPosition
import com.gabrielifrim.brut.ui.formatDuration
import com.gabrielifrim.brut.ui.formatRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Widget d'écran d'accueil : format, chrono et niveau, et un gros bouton REC.
 * Pendant la prise, un bouton Repère s'ajoute, comme dans la notification.
 */
class BrutWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = (context.applicationContext as BrutApp).controller.state.value
        manager.updateAppWidget(ids, views(context, state))
    }

    companion object {

        /** Ce que le widget affiche ; le niveau est arrondi pour ne pas le redessiner sans cesse. */
        private data class Shown(val busy: Boolean, val armed: Boolean, val seconds: Long, val format: String, val level: Int, val clip: Boolean)

        /**
         * Tient les widgets à jour tant que le processus vit. Un widget se redessine
         * par un échange avec le lanceur : au plus quatre fois par seconde, et seulement
         * quand ce qu'il affiche change.
         */
        fun observe(context: Context, scope: CoroutineScope, state: StateFlow<RecorderState>) {
            val app = context.applicationContext
            var last: Shown? = null
            var lastAt = 0L
            scope.launch {
                state.collect { s ->
                    val shown = shown(app, s)
                    if (shown == last) return@collect
                    val now = SystemClock.elapsedRealtime()
                    // Le niveau seul ne justifie pas plus de quatre images par seconde.
                    val onlyLevel = last?.copy(level = shown.level, clip = shown.clip) == shown
                    if (onlyLevel && now - lastAt < 250) return@collect
                    last = shown
                    lastAt = now
                    push(app, s)
                }
            }
        }

        private fun push(context: Context, state: RecorderState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BrutWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context, state))
        }

        private fun shown(context: Context, s: RecorderState): Shown {
            val peak = s.levels.maxOfOrNull { it.holdDb } ?: LevelMeter.FLOOR_DB
            return Shown(
                busy = s.isBusy,
                armed = s.isArmed,
                seconds = s.elapsedSeconds.toLong(),
                format = formatLine(context, s),
                level = if (s.isBusy) (meterPosition(peak) * 50).roundToInt() else 0,
                clip = s.isBusy && s.levels.any { it.clipped },
            )
        }

        private fun formatLine(context: Context, s: RecorderState): String {
            val depth = when (s.format.bitDepth) {
                BitDepth.PCM_16 -> "16 BIT"
                BitDepth.PCM_24 -> "24 BIT"
                BitDepth.FLOAT_32 -> "32F"
            }
            val ch = context.getString(if (s.format.channels == 1) R.string.format_short_mono else R.string.format_short_stereo)
            return "${formatRate(s.format.sampleRate)} · $depth · $ch"
        }

        fun views(context: Context, s: RecorderState): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_brut)
            val shown = shown(context, s)
            v.setTextViewText(R.id.widget_format, shown.format)
            v.setTextViewText(
                R.id.widget_status,
                context.getString(
                    when {
                        s.isArmed -> R.string.widget_armed
                        s.isRecording -> R.string.widget_recording
                        else -> R.string.widget_ready
                    },
                ),
            )
            v.setTextColor(R.id.widget_status, context.getColor(if (s.isBusy) R.color.brut_red else R.color.brut_cream_dim))
            if (s.isArmed) v.setTextColor(R.id.widget_status, context.getColor(R.color.brut_amber))
            v.setTextViewText(R.id.widget_chrono, formatDuration(if (s.isRecording) s.elapsedSeconds else 0.0, withHundredths = false))
            v.setTextColor(R.id.widget_chrono, context.getColor(if (s.isRecording) R.color.brut_cream else R.color.brut_cream_faint))
            v.setProgressBar(R.id.widget_level, 100, shown.level * 2, false)
            v.setViewVisibility(R.id.widget_level, if (s.isRecording) View.VISIBLE else View.INVISIBLE)
            v.setViewVisibility(R.id.widget_clip, if (shown.clip) View.VISIBLE else View.GONE)
            v.setImageViewResource(
                R.id.widget_rec,
                when {
                    s.isArmed -> R.drawable.widget_rec_armed
                    s.isRecording -> R.drawable.widget_rec_stop
                    else -> R.drawable.widget_rec_idle
                },
            )
            v.setContentDescription(R.id.widget_rec, context.getString(if (s.isBusy) R.string.action_stop else R.string.tile_record))
            v.setViewVisibility(R.id.widget_marker, if (s.isRecording) View.VISIBLE else View.GONE)

            val open = PendingIntent.getActivity(
                context, 10, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val rec = if (s.isBusy) {
                RecordingService.stopIntent(context)
            } else {
                PendingIntent.getActivity(context, 11, QuickRecordActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            v.setOnClickPendingIntent(R.id.widget_root, open)
            v.setOnClickPendingIntent(R.id.widget_rec, rec)
            v.setOnClickPendingIntent(R.id.widget_marker, RecordingService.markerIntent(context))
            return v
        }
    }
}
