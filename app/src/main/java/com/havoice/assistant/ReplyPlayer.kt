package com.havoice.assistant

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Plays the assistant's spoken reply. Use from the main thread. */
class ReplyPlayer {
    private var player: MediaPlayer? = null
    private var onDone: ((Boolean) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Returns when the reply has finished playing, failed, or [stop] was called: true if it
     * played, false if it couldn't be loaded (so the caller can speak it another way).
     */
    suspend fun play(url: String): Boolean {
        stop()
        return suspendCancellableCoroutine { continuation ->
            val mp = MediaPlayer()
            player = mp
            var started = false
            onDone = { ok -> if (continuation.isActive) continuation.resume(ok) }
            fun fail() {
                if (player === mp) finish(false)
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setOnPreparedListener {
                started = true
                it.start()
            }
            mp.setOnCompletionListener { finish(true) }
            mp.setOnErrorListener { _, what, extra ->
                AppLog.w("Reply audio failed ($what/$extra)")
                if (started) finish(true) else fail()
                true
            }
            // An address the phone can't reach can leave loading hanging; give up and let the caller speak it.
            handler.postDelayed({
                if (!started && player === mp) {
                    AppLog.w("Reply audio didn't load in ${LOAD_TIMEOUT_MS / 1000} s")
                    fail()
                }
            }, LOAD_TIMEOUT_MS)
            continuation.invokeOnCancellation { stop() }
            try {
                mp.setDataSource(url)
                mp.prepareAsync()
            } catch (e: Exception) {
                AppLog.w("Reply audio couldn't start: ${e.message}")
                fail()
            }
        }
    }

    fun stop() = finish(true)

    private fun finish(ok: Boolean) {
        val mp = player ?: return
        player = null
        mp.release()
        onDone?.invoke(ok)
        onDone = null
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}
