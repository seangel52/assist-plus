package com.havoice.assistant

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The owner's voiceprint: an average of the speaker embeddings from the setup phrases.
 * A phrase is "you" when its embedding points the same way (cosine similarity).
 */
object VoiceProfile {
    /**
     * Default minimum similarity to accept a phrase as the owner's voice; adjustable in settings.
     * Midway between the owner's lowest (0.70) and a video's highest (0.16) TitaNet scores.
     */
    const val DEFAULT_THRESHOLD = 0.45f

    /**
     * Short phrases ("yes", "no thanks", "stop") are too little speech for a firm voice check: the
     * owner's score anywhere from 0.3 to 0.6 while a TV or video scores about 0–0.16. So they are
     * only turned away when clearly someone else.
     */
    const val SHORT_SECONDS = 1.3f
    private const val SHORT_FLOOR = 0.25f

    /** Phrases scoring at least this, but below the threshold, may be you mixed with another voice. */
    const val MIXED_MIN = 0.2f

    // "v3": TitaNet voiceprints. Older ones came from CAM++ (or, before v2, only 0.3 s of speech).
    private fun file(context: Context) = File(context.filesDir, "voiceprint_v3.bin")

    /** The owner's speech from setup, as a WAV file — VoxLock's reference voice. */
    fun sampleFile(context: Context) = File(context.filesDir, "voice_sample_v2.wav")

    fun load(context: Context): FloatArray? {
        val f = file(context)
        if (!f.exists()) return null
        val buffer = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()) { buffer.get(it) }
    }

    fun isEnrolled(context: Context) = file(context).exists()

    fun clear(context: Context) {
        file(context).delete()
        sampleFile(context).delete()
    }

    /** Builds and saves the voiceprint and voice sample from recorded setup phrases. Returns how consistent they were. */
    fun enroll(context: Context, engines: SpeechEngines, clips: List<ByteArray>): Float {
        val speech = clips.map { speechOnly(engines, toFloats(it)) }
        val embeddings = speech.mapNotNull { embed(engines, it) }
        require(embeddings.size >= 2) { "Couldn't hear enough speech in the recordings" }
        val average = normalize(FloatArray(embeddings[0].size) { i -> embeddings.sumOf { it[i].toDouble() }.toFloat() })
        val buffer = ByteBuffer.allocate(average.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        average.forEach { buffer.putFloat(it) }
        file(context).writeBytes(buffer.array())
        writeWav(sampleFile(context), speech.reduce { a, b -> a + b })

        val pairs = embeddings.indices.flatMap { i -> (i + 1 until embeddings.size).map { j -> cosine(embeddings[i], embeddings[j]) } }
        AppLog.i(
            "Voice ID setup: ${embeddings.size} clips, speech %s s, clip-to-clip similarity %s".format(
                speech.joinToString("/") { "%.1f".format(it.size / MicRecorder.SAMPLE_RATE.toFloat()) },
                pairs.joinToString("/") { "%.2f".format(it) },
            ),
        )
        // How alike the setup phrases are to each other: low values mean a noisy setup.
        return pairs.min()
    }

    private fun writeWav(target: File, samples: FloatArray) {
        val rate = MicRecorder.SAMPLE_RATE
        val data = samples.size * 2
        val out = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data)
        samples.forEach { out.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        target.writeBytes(out.array())
    }

    /** Similarity (−1…1) between a phrase and the voiceprint. */
    fun score(engines: SpeechEngines, profile: FloatArray, samples: FloatArray): Float? =
        embed(engines, samples)?.let { cosine(it, profile) }

    fun thresholdFor(seconds: Float, base: Float) = if (seconds < SHORT_SECONDS) minOf(base, SHORT_FLOOR) else base

    private fun embed(engines: SpeechEngines, samples: FloatArray): FloatArray? {
        if (samples.size < MicRecorder.SAMPLE_RATE / 4) return null
        val stream = engines.speaker.createStream()
        return try {
            stream.acceptWaveform(samples, MicRecorder.SAMPLE_RATE)
            stream.inputFinished()
            if (engines.speaker.isReady(stream)) normalize(engines.speaker.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    /** Keeps only the spoken parts of a clip, so silence doesn't dilute the voiceprint. */
    fun speechOnly(engines: SpeechEngines, samples: FloatArray): FloatArray {
        val vad = engines.vad
        vad.reset()
        VadFeeder(vad).accept(samples)
        vad.flush()
        val parts = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            parts += vad.front().samples
            vad.pop()
        }
        vad.reset()
        return if (parts.isEmpty()) samples else parts.reduce { a, b -> a + b }
    }

    fun toFloats(pcm: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
    }

    private fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // both are unit length
    }
}
