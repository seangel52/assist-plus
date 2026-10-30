package com.havoice.assistant

import android.app.Application

class VoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)

        // Record crashes in the log so they reach Home Assistant on the next sync.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.e("App crashed on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }

        LogSync.schedule(this)
    }
}
