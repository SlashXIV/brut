package com.gabrielifrim.brut

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.gabrielifrim.brut.audio.RecorderController
import com.gabrielifrim.brut.library.LibraryController
import com.gabrielifrim.brut.quick.BrutWidget
import com.gabrielifrim.brut.quick.QuickRecordActivity
import com.gabrielifrim.brut.service.RecordingService
import kotlinx.coroutines.MainScope

class BrutApp : Application() {

    /** Unique pour tout le processus : la prise survit à la destruction de l'écran. */
    val controller: RecorderController by lazy {
        RecorderController(this).also { BrutWidget.observe(this, MainScope(), it.state) }
    }
    val library: LibraryController by lazy { LibraryController(this) { controller.customFolder } }

    override fun onCreate() {
        super.onCreate()
        RecordingService.createChannel(this)
        publishShortcuts()
    }

    /**
     * Raccourcis d'application (appui long sur l'icône). Déclarés ici plutôt qu'en XML :
     * le XML exige le nom du paquet en dur, qui diffère entre les versions debug et release.
     */
    private fun publishShortcuts() {
        val record = ShortcutInfoCompat.Builder(this, QuickRecordActivity.SHORTCUT_ID)
            .setShortLabel(getString(R.string.shortcut_record_short))
            .setLongLabel(getString(R.string.shortcut_record_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_record))
            .setIntent(QuickRecordActivity.intent(this))
            .build()
        val library = ShortcutInfoCompat.Builder(this, MainActivity.SHORTCUT_LIBRARY)
            .setShortLabel(getString(R.string.shortcut_library_short))
            .setLongLabel(getString(R.string.shortcut_library_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_library))
            .setIntent(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LIBRARY))
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(record, library)) }
    }
}
