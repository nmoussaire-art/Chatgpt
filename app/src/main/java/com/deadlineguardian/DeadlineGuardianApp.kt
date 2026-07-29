package com.deadlineguardian

import android.app.Application
import com.deadlineguardian.notify.DeadlineWorker
import com.deadlineguardian.notify.Notifier

class DeadlineGuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        DeadlineWorker.schedule(this)
    }
}
