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
        removeOldFiles()
    }

    /**
     * Files earlier versions left behind: voiceprints from older speaker models (v3 is still
     * read until voice setup is run again), and VoxLock's models and voice samples (about 80 MB;
     * voice isolation was tried and dropped).
     */
    private fun removeOldFiles() {
        listOf("voiceprint.bin", "voiceprint_v2.bin", "voice_sample.wav", "voice_sample_v2.wav")
            .map { java.io.File(filesDir, it) }
            .filter { it.exists() }
            .forEach { it.delete() }
        java.io.File(filesDir, "voxlock").takeIf { it.exists() }?.deleteRecursively()
    }
}
