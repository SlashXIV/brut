package com.gabrielifrim.brut.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gabrielifrim.brut.BrutApp
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tuile « Enregistrer » des Paramètres rapides : un toucher lance la prise avec les
 * réglages en cours, un autre l'arrête. Allumée pendant la prise, avec le chrono.
 */
class RecordTileService : TileService() {

    private var scope: CoroutineScope? = null
    private val controller get() = (application as BrutApp).controller

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = MainScope().also { s ->
            s.launch {
                controller.state
                    .map { Triple(it.isBusy, it.isArmed, it.elapsedSeconds.toLong()) }
                    .distinctUntilChanged()
                    .collect { (busy, armed, seconds) -> render(busy, armed, seconds) }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (controller.state.value.isBusy) {
            controller.stopRecording()
            return
        }
        launch()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch() {
        val intent = QuickRecordActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(busy: Boolean, armed: Boolean, seconds: Long) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_record)
        tile.state = if (busy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(
            when {
                armed -> R.string.tile_armed
                busy -> R.string.tile_stop
                else -> R.string.tile_record
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                armed -> getString(R.string.tile_armed_subtitle)
                busy -> formatDuration(seconds.toDouble(), withHundredths = false)
                else -> getString(R.string.app_name)
            }
        }
        tile.updateTile()
    }
}
