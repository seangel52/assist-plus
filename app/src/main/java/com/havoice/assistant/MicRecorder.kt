package com.havoice.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Reads the microphone as 16 kHz mono 16-bit PCM — the format Home Assistant's
 * speech-to-text expects — in 40 ms chunks, cleaned by the chosen [NoiseReduction].
 * Recording stops when collection stops.
 */
object MicRecorder {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_BYTES = SAMPLE_RATE * 2 * 40 / 1000

    /**
     * Caller must hold RECORD_AUDIO. With a [capture], raw and cleaned audio are also
     * kept for tuning.
     */
    @SuppressLint("MissingPermission")
    fun frames(
        context: Context,
        noise: NoiseReduction,
        capture: Captures.Recorder? = null,
        /** Gets the raw audio's loudness (before noise reduction). */
        meter: SignalMeter? = null,
    ): Flow<ByteArray> = flow {
        val filter = NoiseFilter.get(context, noise)?.also { it.reset() }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // VOICE_RECOGNITION is the least-processed input, best for our own filter.
        // VOICE_COMMUNICATION turns on the phone's call-grade noise suppression.
        val source = if (noise == NoiseReduction.PHONE) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val record = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw HaException("Couldn't open the microphone")
        }
        val suppressor = if (noise == NoiseReduction.PHONE && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        } else {
            null
        }
        record.startRecording()
        try {
            val buffer = ByteArray(CHUNK_BYTES)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val chunk = buffer.copyOf(read)
                meter?.add(chunk)
                val out = filter?.process(chunk) ?: chunk
                capture?.let {
                    it.raw.write(chunk)
                    it.clean.write(out)
                }
                if (out.isNotEmpty()) emit(out)
            }
        } finally {
            record.stop()
            suppressor?.release()
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
