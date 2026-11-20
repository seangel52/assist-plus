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
 *
 * [everyday] is the owner's voice as it sounds in real requests ([VoiceMemory]), or null until
 * enough has been learned. Setup recordings are read aloud, a few at a time; everyday speech is
 * quicker and more varied, and matches them less well.
 */
class Voiceprint(val centroids: List<FloatArray>, val noise: NoiseReduction, val everyday: FloatArray? = null)

/**
 * How one piece of audio compares with the owner's voice: against the setup recordings
 * ([setup]) and against the everyday voice learned since ([everyday], null until learned).
 */
class VoiceMatch(val embedding: FloatArray, val setup: Float, val everyday: Float?) {
    /**
     * The better of the two: the voice only has to match one way the owner sounds. The everyday
     * voice counts only for audio the setup recordings don't rule out ([EVERYDAY_NEEDS_SETUP]:
     * the owner's own speech never scored under 0.26 against them, a video nearly always under
     * 0.2), so a voice that merely resembles what was learned can't get in on that alone.
     */
    val score: Float get() = if (everyday != null && setup >= EVERYDAY_NEEDS_SETUP) maxOf(setup, everyday) else setup

    companion object {
        const val EVERYDAY_NEEDS_SETUP = 0.20f
    }
}

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

    /** How much less a match with the far (across the room) voiceprint counts; see [score]. */
    const val FAR_MARGIN = 0.15f

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
    fun needed(base: Float, speechSeconds: Float, snrDb: Float?, isolated: Boolean = true): Float {
        // Only a short phrase on its own gets the short-phrase allowance: a sliver of a longer
        // phrase that another voice fills (a TV talking on) did pass as the owner with it.
        val shortness = if (!isolated) 0f else ((FULL_SECONDS - speechSeconds) / (FULL_SECONDS - SHORT_SECONDS)).coerceIn(0f, 1f)
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
        VoiceMemory.clear(context)
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
        // What was learned belonged to the old setup recordings (they decide what may be learned).
        VoiceMemory.clear(context)

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

    /**
     * Similarity (−1…1) between a phrase and the owner's voice: the best of the close and far
     * versions, the far one less [FAR_MARGIN]. Any voice heard from across the room (a TV
     * included) sounds more like the far version: measured on the owner's phone, TV audio
     * scored up to 0.32 against it in 1.5 s pieces, against at most 0.10 for the close one, while
     * the owner across the room scored 0.42 against it (0.19 against the close one).
     */
    fun score(engines: SpeechEngines, profile: Voiceprint, samples: FloatArray): Float? = match(engines, profile, samples)?.score

    /** [score] with its parts, and the audio's embedding for [VoiceMemory] to learn from. */
    fun match(engines: SpeechEngines, profile: Voiceprint, samples: FloatArray): VoiceMatch? =
        embed(engines, samples)?.let { e ->
            VoiceMatch(
                embedding = e,
                setup = profile.centroids.mapIndexed { i, c -> cosine(e, c) - if (i == 0) 0f else FAR_MARGIN }.max(),
                everyday = profile.everyday?.let { cosine(e, it) },
            )
        }

    /** [profile] with the everyday voice learned so far, for listening to requests. */
    fun withEveryday(context: Context, profile: Voiceprint): Voiceprint =
        Voiceprint(profile.centroids, profile.noise, VoiceMemory.everyday(context, profile.noise))

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

    fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // both are unit length
    }
}

/** One phrase's voice check, kept so the app can learn from it afterward ([VoiceMemory]). */
class HeardVoice(
    val match: VoiceMatch,
    /** The bar the phrase had to reach. */
    val needed: Float,
    /** Seconds of audio the match was made on. */
    val seconds: Float,
    /** The match covers the whole phrase: no other voice was found in it. */
    val whole: Boolean,
)

/**
 * The owner's everyday voice, learned from real requests and kept between sessions.
 *
 * Measured on a week of the owner's own requests: against the setup recordings their speech
 * scored 0.26–0.72 (half under 0.51) while a TV reached 0.28, so no bar could keep every request
 * and turn away every video. Against the average of the owner's own accepted requests the same
 * speech scored 0.37–0.90 (half over 0.72) and the TV stayed under 0.20.
 *
 * What may be learned is decided by evidence that doesn't depend on what was learned before, so
 * it can't drift toward another voice:
 * - a phrase the setup recordings alone accept with room to spare ([CONFIDENT_MARGIN]), long
 *   enough to judge ([CONFIDENT_SECONDS]) and with no other voice in it; or
 * - a phrase that was turned away, after which the owner said the same words again and was
 *   accepted: that it was them is shown by what they did, not by a score.
 *
 * The newest [MAX] phrases are kept (192 numbers each, about 50 KB in all), in the app's files
 * rather than its cache, so clearing the cache doesn't forget them. The oldest drop out as new
 * ones come in, so the voice keeps up with the owner (a cold, a new phone case, a new room).
 */
object VoiceMemory {
    const val MAX = 60

    /** Fewer phrases than this is too little to stand for the owner's voice. */
    const val MIN = 6

    const val CONFIDENT_MARGIN = 0.10f
    const val CONFIDENT_SECONDS = 2.0f

    /** A turned-away phrase must at least not sound like a video (those score under 0.2) to be learned from a repeat. */
    const val REPEAT_FLOOR = 0.20f
    const val REPEAT_SECONDS = 1.0f

    /** The owner tries again within this long of being turned away. */
    const val REPEAT_WITHIN_MILLIS = 45_000L

    /** Share of the words the two tries must have in common ("Vlog eight ounces of juice" / "Log eight ounces of juice"). */
    const val REPEAT_WORDS = 0.7f

    private const val MAGIC = 0x564D3031 // "VM01"

    private fun file(context: Context) = File(context.filesDir, "voice_everyday.bin")

    private class Stored(val noise: NoiseReduction, val embeddings: List<FloatArray>)

    @Synchronized
    private fun read(context: Context): Stored? = try {
        file(context).takeIf { it.exists() }?.let { f ->
            val buffer = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val noise = if (buffer.int == MAGIC) NoiseReduction.entries.getOrNull(buffer.int) else null
            noise?.let {
                val count = buffer.int
                val size = buffer.int
                Stored(it, List(count) { FloatArray(size) { buffer.float } })
            }
        }
    } catch (e: RuntimeException) {
        // A damaged file only means starting to learn again.
        null
    }

    /** How many phrases have been learned, for the log and settings. */
    fun count(context: Context): Int = read(context)?.embeddings?.size ?: 0

    /**
     * The everyday voice for audio that went through [noise], or null until [MIN] phrases are
     * learned. Phrases learned through a different noise reduction don't compare fairly.
     */
    fun everyday(context: Context, noise: NoiseReduction): FloatArray? {
        val stored = read(context)?.takeIf { it.noise == noise } ?: return null
        return average(stored.embeddings)
    }

    /** The average of [embeddings] as one voice, or null when there are fewer than [MIN]. */
    fun average(embeddings: List<FloatArray>): FloatArray? {
        if (embeddings.size < MIN) return null
        val sum = FloatArray(embeddings[0].size)
        for (e in embeddings) for (i in sum.indices) sum[i] += e[i]
        return VoiceProfile.normalize(sum)
    }

    @Synchronized
    fun add(context: Context, noise: NoiseReduction, embeddings: List<FloatArray>) {
        if (embeddings.isEmpty()) return
        val kept = read(context)?.takeIf { it.noise == noise }?.embeddings.orEmpty()
        val all = (kept + embeddings).takeLast(MAX)
        val buffer = ByteBuffer.allocate(16 + all.sumOf { it.size } * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(noise.ordinal).putInt(all.size).putInt(all[0].size)
        all.forEach { e -> e.forEach { buffer.putFloat(it) } }
        // Written beside the file and moved over it, so a crash mid-write can't leave half a file.
        val target = file(context)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(buffer.array())
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }

    /** One phrase of a listening session, as far as learning the voice is concerned. */
    class Phrase(val text: String, val accepted: Boolean, val voice: HeardVoice?)

    /** A voice worth learning, and why (for the log). */
    class Lesson(val embedding: FloatArray, val why: String)

    /**
     * What [now]'s session teaches about the owner's voice. [before] is the session just before
     * it, which ended [millisBetween] ago; null if there wasn't one.
     */
    fun lessons(now: List<Phrase>, before: List<Phrase>?, millisBetween: Long): List<Lesson> {
        val lessons = mutableListOf<Lesson>()
        for (p in now) {
            val v = p.voice ?: continue
            if (p.accepted && v.whole && v.seconds >= CONFIDENT_SECONDS && v.match.setup >= v.needed + CONFIDENT_MARGIN) {
                lessons += Lesson(v.match.embedding, "matched setup %.2f over a bar of %.2f".format(v.match.setup, v.needed))
            }
        }
        val said = now.filter { it.accepted }.joinToString(" ") { it.text }
        if (before != null && millisBetween <= REPEAT_WITHIN_MILLIS && said.isNotBlank()) {
            for (p in before) {
                val v = p.voice ?: continue
                if (p.accepted || v.seconds < REPEAT_SECONDS || v.match.setup < REPEAT_FLOOR) continue
                if (sameWords(p.text, said)) {
                    lessons += Lesson(v.match.embedding, "turned away at %.2f, then said again and accepted".format(v.match.score))
                }
            }
        }
        return lessons
    }

    /**
     * Whether [again] repeats [first]: at least two words, and [REPEAT_WORDS] of the longer
     * one's words in common (the recognizer rarely writes two tries exactly alike).
     */
    fun sameWords(first: String, again: String): Boolean {
        fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(' ').filter { it.isNotBlank() }
        val a = words(first)
        val b = words(again)
        if (a.size < 2 || b.size < 2) return false
        val left = b.toMutableList()
        val shared = a.count { left.remove(it) }
        return shared >= maxOf(a.size, b.size) * REPEAT_WORDS
    }
}
