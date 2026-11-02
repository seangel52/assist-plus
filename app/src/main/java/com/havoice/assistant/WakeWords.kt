package com.havoice.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * A wake phrase and how it was tuned from the owner's recordings.
 * [threshold]: the spotter's trigger threshold (lower = triggers more easily).
 * [voiceMin]: the lowest voice ID score accepted for this phrase, or null if not recorded.
 */
data class WakePhrase(val text: String, val threshold: Float = DEFAULT_THRESHOLD, val voiceMin: Float? = null) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.25f
    }
}

/** A tuned phrase and how many of the close and far recordings it catches. */
data class TuneResult(val phrase: WakePhrase, val near: Int, val far: Int)

/** Custom wake phrases: storing them, turning them into the spotter's format, and tuning them. */
object WakeWords {
    // How strongly the spotter favours the owner's phrases over other speech. Tuning showed
    // 1.5 made people raise their voice; 2.0 catches normal speech.
    private const val BOOST = 2.0f
    private const val LOWEST_THRESHOLD = 0.05f

    fun load(settings: HaSettings): List<WakePhrase> {
        val array = try {
            JSONArray(settings.wakePhrasesJson)
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            WakePhrase(
                text = o.getString("text"),
                threshold = o.optDouble("threshold", WakePhrase.DEFAULT_THRESHOLD.toDouble()).toFloat(),
                voiceMin = if (o.has("voiceMin")) o.getDouble("voiceMin").toFloat() else null,
            )
        }
    }

    fun save(settings: HaSettings, phrases: List<WakePhrase>) {
        settings.wakePhrasesJson = JSONArray(
            phrases.map { p ->
                JSONObject().put("text", p.text).put("threshold", p.threshold.toDouble()).apply {
                    p.voiceMin?.let { put("voiceMin", it.toDouble()) }
                }
            },
        ).toString()
    }

    /**
     * The spotter's keyword list: "▁HE Y ▁JA R VI S :2.0 #0.25 @0/…", one entry per phrase,
     * labelled by index. [sensitivity] (0–1, 0.5 = as tuned) shifts every threshold by up to ±0.1.
     */
    fun spec(context: Context, phrases: List<WakePhrase>, sensitivity: Float = 0.5f, looser: Float = 0f): String =
        phrases.mapIndexedNotNull { i, p ->
            val tokens = WakeTokenizer.get(context).encode(p.text)
            val threshold = (p.threshold + (0.5f - sensitivity) * 0.2f - looser).coerceIn(LOWEST_THRESHOLD, 0.9f)
            if (tokens.isEmpty()) null else "${tokens.joinToString(" ")} :$BOOST #${"%.2f".format(java.util.Locale.US, threshold)} @$i"
        }.joinToString("/")

    /**
     * Listens until one of [phrases] is heard and returns it with the last 2 s of audio (to
     * check whose voice said it), or null if the model isn't available. Caller holds RECORD_AUDIO;
     * cancel the coroutine to stop. [onTick] runs about every 200 ms.
     *
     * With [onNearMiss], a second, looser copy of the spotter listens too: when it hears a phrase
     * and the real one doesn't within a moment, [onNearMiss] gets the phrase and the audio around
     * it. Those are the wake words that almost worked, for tuning from real use.
     */
    @SuppressLint("MissingPermission")
    suspend fun awaitWake(
        context: Context,
        phrases: List<WakePhrase>,
        sensitivity: Float,
        onNearMiss: ((WakePhrase, FloatArray) -> Unit)? = null,
        onTick: () -> Unit = {},
    ): Pair<WakePhrase, FloatArray>? = withContext(Dispatchers.IO) {
        val spotter = WakeSpotter.get(context)?.spotter ?: return@withContext null
        val spec = spec(context, phrases, sensitivity)
        if (spec.isEmpty()) return@withContext null
        val loose = onNearMiss?.let { spotter.createStream(spec(context, phrases, sensitivity, looser = NEAR_MISS_LOOSER)) }
        var nearMiss: WakePhrase? = null
        var nearMissAt = 0L
        val rate = MicRecorder.SAMPLE_RATE
        // A fifth of a second per read: the processor wakes 5 times a second instead of 25,
        // which matters for a listener that runs all day. Detection is no slower to notice.
        val chunk = MicRecorder.CHUNK_BYTES * 5
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 4),
        )
        // Music or a video playing from this phone comes straight back into its mic and buries
        // the wake phrase; the phone's echo canceller subtracts its own sound. It also softens a
        // distant voice, so it only runs while the phone is playing something.
        val audioManager = context.getSystemService(AudioManager::class.java)
        val echoCanceller = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = audioManager.isMusicActive }
        } else {
            null
        }
        val stream = spotter.createStream(spec)
        val gate = SoundGate()
        // Android hands a background app silence while another app has the microphone; note it in the log.
        var silenced = false
        var chunksRead = 0
        try {
            record.startRecording()
            val buffer = ByteArray(chunk)
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                if (++chunksRead % SILENCE_CHECK_CHUNKS == 0) echoCanceller?.let { aec ->
                    val playing = audioManager.isMusicActive
                    if (aec.enabled != playing) aec.enabled = playing
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && chunksRead % SILENCE_CHECK_CHUNKS == 0) {
                    val now = record.activeRecordingConfiguration?.isClientSilenced == true
                    if (now != silenced) {
                        silenced = now
                        AppLog.i(
                            if (now) "Wake word: Android gave the microphone to another app; not hearing anything"
                            else "Wake word: microphone is back",
                        )
                    }
                }
                val samples = VoiceProfile.toFloats(buffer.copyOf(read))
                WakeHistory.add(samples)
                // The model only runs while there's sound worth checking; a quiet room costs
                // almost nothing (it was most of the listener's battery use).
                for (piece in gate.offer(samples)) {
                    stream.acceptWaveform(piece, rate)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val label = spotter.getResult(stream).keyword
                        if (label.isNotEmpty()) {
                            val phrase = phrases.getOrNull(label.toIntOrNull() ?: -1) ?: continue
                            return@withContext phrase to WakeHistory.last(2f)
                        }
                    }
                    if (loose != null) {
                        loose.acceptWaveform(piece, rate)
                        while (spotter.isReady(loose)) {
                            spotter.decode(loose)
                            val label = spotter.getResult(loose).keyword
                            if (label.isNotEmpty()) {
                                spotter.reset(loose)
                                if (nearMiss == null) {
                                    nearMiss = phrases.getOrNull(label.toIntOrNull() ?: -1)
                                    nearMissAt = SystemClock.elapsedRealtime()
                                }
                            }
                        }
                    }
                }
                // The real spotter had a moment to catch up; it didn't, so this one almost worked.
                nearMiss?.takeIf { SystemClock.elapsedRealtime() - nearMissAt > NEAR_MISS_WAIT_MS }?.let { phrase ->
                    nearMiss = null
                    onNearMiss?.invoke(phrase, WakeHistory.last(NEAR_MISS_CLIP_SECONDS))
                }
                soundShare = gate.share
                onTick()
            }
            null
        } finally {
            loose?.release()
            stream.release()
            record.stop()
            echoCanceller?.release()
            record.release()
        }
    }

    /**
     * Whether [audio] (around a detected wake phrase) could be the owner's voice, when "only wake
     * for my voice" is on. Returns the verdict and the score (null when not checked).
     *
     * A wake phrase is under a second of speech, too little for a firm voice check: the owner's
     * scored 0.23–0.56 where a TV or video scores about 0–0.16. So only clearly-other voices are
     * turned away here; the request that follows gets the full voice check anyway.
     */
    fun voiceMatches(context: Context, settings: HaSettings, phrase: WakePhrase, audio: FloatArray): Pair<Boolean, Float?> {
        val profile = if (settings.wakeOnlyMyVoice && settings.voiceIdEnabled) VoiceProfile.load(context) else null
        profile ?: return true to null
        val engines = SpeechEngines.get(context) ?: return true to null
        val clean = VoiceProfile.asEnrolled(context, profile, audio)
        val score = VoiceProfile.score(engines, profile, VoiceProfile.speechOnly(engines, clean)) ?: 0f
        val needed = WAKE_VOICE_MIN
        return (score >= needed) to score
    }

    // In noisy rooms, or from across the room, the owner's wake phrase has scored as low as 0.2;
    // a TV or video scores about 0–0.16. (Tuning records each phrase's own scores in the log.)
    private const val WAKE_VOICE_MIN = 0.15f

    // About every 2 seconds (200 ms reads).
    private const val SILENCE_CHECK_CHUNKS = 10

    // The near-miss spotter: this much looser than the real one, which gets this long to catch up.
    private const val NEAR_MISS_LOOSER = 0.15f
    private const val NEAR_MISS_WAIT_MS = 1_500L
    // Enough to hold the whole phrase: the wait above has already passed since it ended.
    private const val NEAR_MISS_CLIP_SECONDS = 6f

    /** How loud the loudest half second of [samples] is, in dB below full scale. */
    fun loudestDb(samples: FloatArray): Float {
        val half = MicRecorder.SAMPLE_RATE / 2
        var best = 0.0
        var start = 0
        while (start < samples.size) {
            val end = minOf(samples.size, start + half)
            var sum = 0.0
            for (i in start until end) sum += samples[i] * samples[i]
            best = maxOf(best, sum / (end - start))
            start += half / 2
        }
        return (10 * kotlin.math.log10(best + 1e-12)).toFloat()
    }

    /** Share of time the current listening session ran the model (had sound), for the battery log. */
    @Volatile
    var soundShare = 0f
        private set

    /**
     * Tunes a phrase from the owner saying it several times: picks the strictest trigger
     * threshold that still catches every recording, close and far, then a step looser for
     * margin, and the lowest voice ID score seen (minus a margin) for the voice check. Missing
     * the owner is the worse mistake: the voice check already turns away the TV and others
     * (catching all but one set the bar right at the edge of a normal voice; real use showed
     * quieter wake phrases missed).
     * Returns the tuned phrase and how many recordings it caught.
     */
    fun tune(context: Context, phrase: String, clips: List<FloatArray>, profile: Voiceprint?, farFrom: Int = clips.size): TuneResult {
        val spotter = WakeSpotter.get(context) ?: throw HaException("Couldn't load the wake word model")
        val tokens = WakeTokenizer.get(context).encode(phrase)
        if (tokens.isEmpty()) throw HaException("Type a wake phrase first")
        val needed = clips.size
        var chosen = LOWEST_THRESHOLD
        var caught = 0
        for (t in listOf(0.50f, 0.45f, 0.40f, 0.35f, 0.30f, 0.25f, 0.20f, 0.15f, 0.10f, 0.05f)) {
            val hits = clips.count { spotter.detects(it, "${tokens.joinToString(" ")} :$BOOST #${"%.2f".format(java.util.Locale.US, t)} @0") }
            if (hits >= needed) {
                chosen = t
                caught = hits
                break
            }
            caught = maxOf(caught, hits)
        }
        val voiceMin = profile?.let { owner ->
            val engines = SpeechEngines.get(context) ?: return@let null
            clips.mapNotNull { VoiceProfile.score(engines, owner, VoiceProfile.speechOnly(engines, VoiceProfile.asEnrolled(context, owner, it))) }
                .minOrNull()?.let { maxOf(0.15f, it - 0.10f) }
        }
        val tuned = WakePhrase(phrase, threshold = maxOf(LOWEST_THRESHOLD, chosen - 0.05f), voiceMin = voiceMin)
        // Which takes the tuned phrase catches, close and far apart: if far ones are missed even at
        // the loosest threshold, the spotter can't hear the phrase from there.
        val spec = "${tokens.joinToString(" ")} :$BOOST #${"%.2f".format(java.util.Locale.US, tuned.threshold)} @0"
        val hits = clips.map { spotter.detects(it, spec) }
        val near = hits.take(farFrom).count { it }
        val far = hits.drop(farFrom).count { it }
        AppLog.i(
            "Wake phrase \"$phrase\" tuned: close $near of $farFrom, far $far of ${clips.size - farFrom}, threshold %.2f, voice min %s"
                .format(tuned.threshold, voiceMin?.let { "%.2f".format(it) } ?: "-"),
        )
        return TuneResult(tuned, near, far)
    }
}

/** The keyword spotter model (English, 3.3M parameters), loaded once. */
class WakeSpotter private constructor(val spotter: KeywordSpotter) {
    /** Whether [samples] contain the keyword in [spec], using a fresh stream. */
    fun detects(samples: FloatArray, spec: String): Boolean {
        val stream = spotter.createStream(spec)
        try {
            for (start in samples.indices step CHUNK) {
                stream.acceptWaveform(samples.copyOfRange(start, minOf(samples.size, start + CHUNK)), MicRecorder.SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    if (spotter.getResult(stream).keyword.isNotEmpty()) return true
                }
            }
            // Trailing silence lets the spotter finish a phrase said right at the end.
            stream.acceptWaveform(FloatArray(MicRecorder.SAMPLE_RATE / 2), MicRecorder.SAMPLE_RATE)
            stream.inputFinished()
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotEmpty()) return true
            }
            return false
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val CHUNK = MicRecorder.SAMPLE_RATE / 25

        @Volatile
        private var loaded: WakeSpotter? = null

        @Synchronized
        fun get(context: Context): WakeSpotter? {
            loaded?.let { return it }
            return try {
                val spotter = KeywordSpotter(
                    assetManager = context.applicationContext.assets,
                    config = KeywordSpotterConfig(
                        featConfig = FeatureConfig(sampleRate = MicRecorder.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = "kws/encoder.int8.onnx",
                                decoder = "kws/decoder.int8.onnx",
                                joiner = "kws/joiner.int8.onnx",
                            ),
                            tokens = "kws/tokens.txt",
                            modelType = "zipformer2",
                            numThreads = 1,
                        ),
                        // A placeholder that never fires; the real phrases come per stream.
                        keywordsFile = "kws/keywords.txt",
                    ),
                )
                WakeSpotter(spotter).also { loaded = it }
            } catch (e: Exception) {
                AppLog.e("Couldn't load the wake word model", e)
                null
            }
        }
    }
}

/**
 * Splits a typed phrase into the spotter's word pieces the way its SentencePiece (unigram)
 * tokenizer does: per word, the split of "▁WORD" with the highest total piece score.
 * Checked against SentencePiece itself on a set of sample phrases.
 */
class WakeTokenizer private constructor(private val scores: Map<String, Float>) {
    fun encode(text: String): List<String> =
        text.uppercase().replace(Regex("[^A-Z' ]"), " ").split(' ').filter { it.isNotBlank() }.flatMap { word ->
            val s = "▁$word"
            val bestScore = FloatArray(s.length + 1) { if (it == 0) 0f else Float.NEGATIVE_INFINITY }
            val bestFrom = IntArray(s.length + 1) { -1 }
            for (end in 1..s.length) {
                for (start in maxOf(0, end - MAX_PIECE)..<end) {
                    if (bestScore[start] == Float.NEGATIVE_INFINITY) continue
                    val score = scores[s.substring(start, end)] ?: if (end - start == 1) UNKNOWN_SCORE else continue
                    if (bestScore[start] + score > bestScore[end]) {
                        bestScore[end] = bestScore[start] + score
                        bestFrom[end] = start
                    }
                }
            }
            val pieces = ArrayDeque<String>()
            var end = s.length
            while (end > 0) {
                val start = bestFrom[end]
                pieces.addFirst(s.substring(start, end))
                end = start
            }
            pieces
        }

    companion object {
        private const val MAX_PIECE = 16
        private const val UNKNOWN_SCORE = -100f

        @Volatile
        private var loaded: WakeTokenizer? = null

        @Synchronized
        fun get(context: Context): WakeTokenizer = loaded ?: WakeTokenizer(
            context.assets.open("kws/vocab.txt").bufferedReader().readLines().mapNotNull { line ->
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).toFloatOrNull() ?: return@mapNotNull null)
            }.toMap(),
        ).also { loaded = it }
    }
}

/**
 * The last 10 seconds the wake word listener heard (raw mic), so a missed wake word can be
 * looked at afterwards: when Assist is opened by hand right after, the wake word that didn't
 * work is in here.
 */
object WakeHistory {
    private val ring = FloatArray(MicRecorder.SAMPLE_RATE * 10)
    private var pos = 0
    private var filled = 0

    @Synchronized
    fun add(samples: FloatArray) {
        for (s in samples) {
            ring[pos] = s
            pos = (pos + 1) % ring.size
        }
        filled = minOf(ring.size, filled + samples.size)
    }

    /** The last [seconds] (at most 10) heard, oldest first. */
    @Synchronized
    fun last(seconds: Float): FloatArray {
        val n = minOf(filled, (seconds * MicRecorder.SAMPLE_RATE).toInt())
        return FloatArray(n) { i -> ring[(pos - n + i + ring.size) % ring.size] }
    }

    @Synchronized
    fun clear() {
        pos = 0
        filled = 0
    }
}
