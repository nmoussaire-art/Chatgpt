package com.loopguard.app

import android.app.Application
import com.loopguard.app.notify.Notifications
import com.loopguard.app.notify.ReminderScheduler

class LoopGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Neither of these is worth crashing the launch over: the app is fully
        // usable without notifications if WorkManager cannot start.
        runCatching { Notifications.createChannels(this) }
        runCatching { ReminderScheduler.schedule(this) }
    }
}
