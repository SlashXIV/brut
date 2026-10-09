package com.gabrielifrim.brut.quick

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.gabrielifrim.brut.BrutApp
import com.gabrielifrim.brut.MainActivity
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.service.RecordingService

/**
 * Lance une prise sans ouvrir la console : tuile des Paramètres rapides, widget,
 * raccourci. Une activité, même transparente, est nécessaire : Android n'autorise
 * l'accès au micro et le service « micro » que depuis une application au premier plan.
 *
 * Elle s'affiche aussi sur l'écran verrouillé, pour lancer une prise depuis le volet
 * sans déverrouiller ; elle ne montre rien et se ferme aussitôt.
 */
class QuickRecordActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handled = savedInstanceState?.getBoolean(KEY_HANDLED) ?: false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
    }

    // Au premier plan seulement à partir d'ici : c'est la condition pour ouvrir le micro.
    override fun onResume() {
        super.onResume()
        if (!handled) {
            handled = true
            startTake()
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HANDLED, handled)
    }

    private fun startTake() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val controller = (application as BrutApp).controller
        // Sans permission, ou déjà en prise : la console explique ou montre la prise en cours.
        if (!granted || controller.state.value.isBusy) {
            openConsole()
            return
        }
        androidx.core.content.pm.ShortcutManagerCompat.reportShortcutUsed(this, SHORTCUT_ID)
        if (controller.startRecording()) {
            RecordingService.start(this)
            val armed = controller.state.value.isArmed
            toast(if (armed) R.string.quick_armed else R.string.quick_started)
        } else {
            toast(R.string.quick_failed)
            openConsole()
        }
    }

    private fun openConsole() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(text: Int) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_HANDLED = "fait"

        fun intent(context: Context): Intent =
            Intent(context, QuickRecordActivity::class.java)
                .setAction(ACTION_RECORD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        const val ACTION_RECORD = "com.gabrielifrim.brut.ENREGISTRER"
        const val SHORTCUT_ID = "enregistrer"
    }
}
