package com.gabrielifrim.brut.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gabrielifrim.brut.BrutApp
import com.gabrielifrim.brut.MainActivity
import com.gabrielifrim.brut.R
import com.gabrielifrim.brut.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Service de premier plan « micro » : garde la prise vivante écran éteint ou
 * application en arrière-plan. Il n'enregistre rien lui-même ; il tient seulement
 * le processus éveillé et affiche le chrono dans la notification.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as BrutApp).controller
        if (intent?.action == ACTION_STOP) {
            controller.stopRecording()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MARKER) {
            controller.addMarker()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(0, armed = controller.state.value.isArmed),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Brut:enregistrement")
                .apply { acquire(MAX_WAKE_MS) }
        }
        if (watchJob != null) return START_NOT_STICKY
        watchJob = scope.launch {
            controller.state
                .map { Triple(it.isBusy, it.isArmed, it.elapsedSeconds.toLong()) }
                .distinctUntilChanged()
                .collect { (busy, armed, seconds) ->
                    if (!busy) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(seconds, armed))
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun buildNotification(seconds: Long, armed: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = stopIntent(this)
        val marker = markerIntent(this)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(if (armed) R.string.notification_armed_title else R.string.notification_recording_title))
            .setContentText(if (armed) getString(R.string.notification_armed_text) else formatDuration(seconds.toDouble(), withHundredths = false))
            .setContentIntent(open)
        // Un repère se pose depuis la notification, sans rallumer l'écran de l'appli.
        if (!armed) builder.addAction(0, getString(R.string.action_marker), marker)
        return builder
            .addAction(0, getString(if (armed) R.string.disarm else R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(ContextCompat.getColor(this, R.color.brut_amber))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.gabrielifrim.brut.STOP"
        private const val ACTION_MARKER = "com.gabrielifrim.brut.REPERE"
        private const val MAX_WAKE_MS = 12 * 60 * 60 * 1000L

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Arrêter la prise : notification et widget. */
        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 1, Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** Poser un repère sans ouvrir l'appli : notification et widget. */
        fun markerIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 2, Intent(context, RecordingService::class.java).setAction(ACTION_MARKER),
            PendingIntent.FLAG_IMMUTABLE,
        )

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }
    }
}
