package com.havoice.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/** Speed of a noise model on this phone, measured on the test recording. */
data class FilterTiming(val stepMillis: Int, val millisPerSecondOfAudio: Long)

/** Records a short clip and replays it raw or cleaned, to compare noise settings by ear. */
object MicTest {
    private const val BYTES_PER_SECOND = MicRecorder.SAMPLE_RATE * 2

    suspend fun record(
        context: Context,
        seconds: Int = 5,
        noise: NoiseReduction = NoiseReduction.OFF,
        capture: Captures.Recorder? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        withTimeoutOrNull(seconds * 1000L) {
            MicRecorder.frames(context, noise, capture).collect { out.write(it) }
        }
        return out.toByteArray()
    }

    /** Runs [pcm] through [mode]'s model in live-sized chunks, timing it like real use. */
    suspend fun clean(context: Context, mode: NoiseReduction, pcm: ByteArray): Pair<ByteArray, FilterTiming> =
        withContext(Dispatchers.Default) {
            val filter = requireNotNull(NoiseFilter.get(context, mode)) { "$mode has no model" }
            filter.reset()
            val out = ByteArrayOutputStream()
            val started = System.nanoTime()
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + MicRecorder.CHUNK_BYTES, pcm.size)
                out.write(filter.process(pcm.copyOfRange(offset, end)))
                offset = end
            }
            out.write(filter.flush())
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            filter.reset()
            val seconds = (pcm.size.toDouble() / BYTES_PER_SECOND).coerceAtLeast(0.001)
            out.toByteArray() to FilterTiming(filter.stepMillis, (elapsedMs / seconds).toLong())
        }

    suspend fun play(pcm: ByteArray) = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(MicRecorder.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / BYTES_PER_SECOND + 150)
        } finally {
            track.release()
        }
    }
}
