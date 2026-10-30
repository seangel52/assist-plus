package com.havoice.assistant

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Plays the assistant's spoken reply. Use from the main thread. */
class ReplyPlayer {
    private var player: MediaPlayer? = null
    private var onDone: (() -> Unit)? = null

    /** Returns when the reply has finished playing, failed, or [stop] was called. */
    suspend fun play(url: String) {
        stop()
        suspendCancellableCoroutine { continuation ->
            val mp = MediaPlayer()
            player = mp
            onDone = { if (continuation.isActive) continuation.resume(Unit) }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            continuation.invokeOnCancellation { stop() }
            try {
                mp.setDataSource(url)
                mp.prepareAsync()
            } catch (e: Exception) {
                stop()
            }
        }
    }

    fun stop() {
        val mp = player ?: return
        player = null
        mp.release()
        onDone?.invoke()
        onDone = null
    }
}
