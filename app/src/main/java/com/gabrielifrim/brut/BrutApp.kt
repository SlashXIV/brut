package com.gabrielifrim.brut

import android.app.Application
import com.gabrielifrim.brut.audio.RecorderController
import com.gabrielifrim.brut.service.RecordingService

class BrutApp : Application() {

    /** Unique pour tout le processus : la prise survit à la destruction de l'écran. */
    val controller: RecorderController by lazy { RecorderController(this) }

    override fun onCreate() {
        super.onCreate()
        RecordingService.createChannel(this)
    }
}
