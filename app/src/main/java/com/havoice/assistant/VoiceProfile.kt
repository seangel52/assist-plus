package com.havoice.assistant

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The owner's voice as heard from different places: one average speaker embedding per
 * recording condition (held close, and across the room). A voice far from the phone sounds
 * different (quieter, more echo), so it is compared with the far version too: the score is
 * the best match. [noise] is the noise reduction the setup recordings went through; anything
 * scored against this voiceprint must go through the same ([VoiceProfile.asEnrolled]).
 */
class Voiceprint(val centroids: List<FloatArray>, val noise: NoiseReduction)

object VoiceProfile {
    /**
     * Default minimum similarity to accept a phrase as the owner's voice; adjustable in settings.
     * Midway between the owner's lowest (0.70) and a video's highest (0.16) TitaNet scores.
     */
    const val DEFAULT_THRESHOLD = 0.45f

    /**
     * No phrase counts as the owner below this, however short or noisy: a TV or video scores
     * 0–0.16 against the voiceprint, the owner's shortest, noisiest phrases about 0.3.
     */
    const val FLOOR = 0.25f

    // Short phrases ("yes", "no thanks") are too little speech for a firm voice check. The bar
    // drops from the strictness (at 1.5 s of speech or more) to the floor (at 0.6 s or less).
    private const val FULL_SECONDS = 1.5f
    private const val SHORT_SECONDS = 0.6f

    // A voice far from the phone, or over loud background sound, matches less well. The bar
    // drops by up to NOISE_RELIEF as the speech gets closer to the background level.
    private const val CLEAN_SNR_DB = 20f
    private const val NOISY_SNR_DB = 6f
    private const val NOISE_RELIEF = 0.06f

    // "v4": several embeddings (close and far). v3 held one, from close recordings only.
    private fun file(context: Context) = File(context.filesDir, "voiceprint_v4.bin")
    private fun oldFile(context: Context) = File(context.filesDir, "voiceprint_v3.bin")

    // Setup speech saved for VoxLock voice isolation, which was tried and dropped (it never
    // rescued a phrase and often lowered the owner's score); deleted where found.
    private fun oldSample(context: Context) = File(context.filesDir, "voice_sample_v2.wav")

    /**
     * The bar a phrase must reach to count as the owner, for [base] (the strictness setting),
     * [speechSeconds] of speech, and the speech's level above the background ([snrDb], null
     * when unknown).
     */
    fun needed(base: Float, speechSeconds: Float, snrDb: Float?): Float {
        val shortness = ((FULL_SECONDS - speechSeconds) / (FULL_SECONDS - SHORT_SECONDS)).coerceIn(0f, 1f)
        val noisiness = snrDb?.let { ((CLEAN_SNR_DB - it) / (CLEAN_SNR_DB - NOISY_SNR_DB)).coerceIn(0f, 1f) } ?: 0f
        val bar = base - shortness * (base - FLOOR).coerceAtLeast(0f) - noisiness * NOISE_RELIEF
        return maxOf(FLOOR, bar)
    }

    fun load(context: Context): Voiceprint? {
        val settings = HaSettings(context)
        file(context).takeIf { it.exists() }?.let { f ->
            val buffer = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            val size = buffer.int
            val centroids = List(count) { FloatArray(size) { buffer.float } }
            return Voiceprint(centroids, settings.voiceprintNoise)
        }
        // A close-only voiceprint from before far recordings; it was set up with the noise
        // reduction chosen at the time, most likely today's.
        val old = oldFile(context).takeIf { it.exists() } ?: return null
        val buffer = ByteBuffer.wrap(old.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return Voiceprint(listOf(FloatArray(buffer.remaining()) { buffer.get(it) }), settings.voiceprintNoise)
    }

    fun isEnrolled(context: Context) = file(context).exists() || oldFile(context).exists()

    /** Whether the voiceprint includes the owner speaking from across the room. */
    fun hasFarVoice(context: Context) = (load(context)?.centroids?.size ?: 0) > 1

    fun clear(context: Context) {
        file(context).delete()
        oldFile(context).delete()
        oldSample(context).delete()
    }

    /**
     * Builds and saves the voiceprint and voice sample from setup recordings: [near] held as
     * usual, [far] from across the room (empty to skip). [noise] is what the recordings went
     * through. Returns how consistent the close recordings were.
     */
    fun enroll(
        context: Context,
        engines: SpeechEngines,
        near: List<ByteArray>,
        far: List<ByteArray>,
        noise: NoiseReduction,
    ): Float {
        val nearSpeech = near.map { speechOnly(engines, toFloats(it)) }
        val nearEmbeddings = nearSpeech.mapNotNull { embed(engines, it) }
        require(nearEmbeddings.size >= 2) { "Couldn't hear enough speech in the recordings" }
        val farEmbeddings = far.mapNotNull { embed(engines, speechOnly(engines, toFloats(it))) }
        val centroids = listOfNotNull(average(nearEmbeddings), farEmbeddings.takeIf { it.size >= 2 }?.let(::average))

        val buffer = ByteBuffer.allocate(8 + centroids.sumOf { it.size } * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(centroids.size).putInt(centroids[0].size)
        centroids.forEach { c -> c.forEach { buffer.putFloat(it) } }
        file(context).writeBytes(buffer.array())
        oldFile(context).delete()
        HaSettings(context).voiceprintNoise = noise
        oldSample(context).delete()

        val pairs = nearEmbeddings.indices.flatMap { i ->
            (i + 1 until nearEmbeddings.size).map { j -> cosine(nearEmbeddings[i], nearEmbeddings[j]) }
        }
        val farToNear = farEmbeddings.map { cosine(it, centroids[0]) }
        AppLog.i(
            "Voice ID setup: ${nearEmbeddings.size} close clips (similarity %s), ${farEmbeddings.size} far clips (vs close %s), noise %s".format(
                pairs.joinToString("/") { "%.2f".format(it) },
                farToNear.joinToString("/") { "%.2f".format(it) }.ifEmpty { "-" },
                noise,
            ),
        )
        // How alike the close setup phrases are to each other: low values mean a noisy setup.
        return pairs.min()
    }

    private fun average(embeddings: List<FloatArray>) =
        normalize(FloatArray(embeddings[0].size) { i -> embeddings.sumOf { it[i].toDouble() }.toFloat() })

    /** Similarity (−1…1) between a phrase and the closest version of the owner's voice. */
    fun score(engines: SpeechEngines, profile: Voiceprint, samples: FloatArray): Float? =
        embed(engines, samples)?.let { e -> profile.centroids.maxOf { cosine(e, it) } }

    /**
     * Raw microphone audio put through the same noise reduction as the setup recordings, so it
     * can be compared fairly (the wake word listener, for one, hears the raw mic).
     */
    fun asEnrolled(context: Context, profile: Voiceprint, raw: FloatArray): FloatArray {
        val filter = NoiseFilter.offline(context, profile.noise) ?: return raw
        return synchronized(filter) {
            filter.reset()
            val out = filter.process(raw) + filter.flushSamples()
            filter.reset()
            out
        }
    }

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
