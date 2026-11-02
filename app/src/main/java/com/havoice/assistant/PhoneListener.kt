package com.havoice.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeechSegment
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withContext

/** One phrase the phone heard, with its voice ID result. */
data class HeardSegment(
    val startSeconds: Float,
    val seconds: Float,
    /** Similarity to the owner's voiceprint, or null when voice ID is off. */
    val score: Float?,
    val accepted: Boolean,
    /** How voice ID decided, for the log. */
    val how: String,
    /** The owner's words kept from this phrase, or what was heard if it was dropped. */
    val preview: String,
)

data class HeardCommand(
    val text: String,
    /** Why listening stopped: silence, other voices, button, no-speech, max-length or mic-ended. */
    val endReason: String,
    /** A short request whose audio sounded like "cancel" / "stop" / "never mind", whatever the text says. */
    val soundsLikeStop: Boolean = false,
)

/** Everything one listening session heard; [command] is null when nothing was accepted. */
data class ListenResult(
    val command: HeardCommand?,
    val segments: List<HeardSegment>,
    /** One-line summary of every phrase and its voice ID decision, for logs and notes. */
    val details: String,
)

/**
 * Does all the listening on the phone: finds phrases, shows their words live, keeps only the
 * owner's speech (when a voiceprint is given), decides when the command is finished, and
 * produces the text sent to Home Assistant.
 *
 * With voice ID, who is talking is tracked continuously ([SpeakerTimeline]), not decided once
 * per phrase: a TV that talks straight into or over the owner shares phrases with them. Each
 * phrase is transcribed whole (the most accurate), its words are kept by when they were said,
 * and the owner's part is then checked as a whole against the voiceprint. The command ends
 * when the owner has been quiet for the end pause, whether the room went quiet or not.
 */
class PhoneListener(
    private val context: Context,
    private val profile: Voiceprint?,
    /** Voice ID strictness from settings. */
    private val threshold: Float,
    /** Silence after a finished-sounding sentence before the command counts as done. */
    endPauseSeconds: Float = 1.2f,
    /** What's said is private (a spoken PIN): keep the words out of the log. */
    private val keepPrivate: Boolean = false,
    /** Names to lean toward, one per line (NameSteering.hotwords); empty for none. */
    private val hotwords: String = "",
    /** The raw mic's loudness, to allow for a distant voice or loud background. */
    private val meter: SignalMeter? = null,
) {
    private val endSilenceSamples = (endPauseSeconds * RATE).toInt()

    /**
     * Listens until the command ends. [onLive] gets the running transcript (called off the
     * main thread).
     */
    suspend fun listen(
        frames: Flow<ByteArray>,
        stop: Deferred<Unit>,
        onLive: (String) -> Unit,
    ): ListenResult = coroutineScope {
        // Start the mic right away and buffer it, so nothing is lost while the models load.
        val mic = frames.buffer(Channel.UNLIMITED).produceIn(this)
        val engines = withContext(Dispatchers.Default) { SpeechEngines.get(context) }
        if (engines == null) {
            mic.cancel()
            throw HaException("Couldn't load the on-phone speech engine")
        }
        val session = Session(engines, stop, onLive)
        withContext(Dispatchers.IO) {
            try {
                for (pcm in mic) {
                    session.step(VoiceProfile.toFloats(pcm))
                    if (session.endReason != null) break
                }
                session.finishSpeech()
            } finally {
                mic.cancel()
                session.release()
            }
            session.result()
        }
    }

    private class Word(val text: String, val start: Int, val mid: Int)

    private class Phrase(
        val start: Int,
        val end: Int,
        val score: Float?,
        val accepted: Boolean,
        val how: String,
        val text: String,
    )

    private inner class Session(
        private val engines: SpeechEngines,
        private val stop: Deferred<Unit>,
        private val onLive: (String) -> Unit,
    ) {
        private val vad = engines.vad.also { it.reset() }
        private val vadFeeder = VadFeeder(vad)
        private val audio = AudioBuffer()
        private val timeline = SpeakerTimeline(HOP_SAMPLES)

        private var phraseStart = -1
        private var lastWindowAt = 0
        private var lastLiveAt = 0
        private var partial = ""

        // End of the last phrase the speech detector found (how the end is found without voice ID).
        private var lastSpeechEnd = -1
        private val phrases = mutableListOf<Phrase>()
        var endReason: String? = null
            private set

        fun step(chunk: FloatArray) {
            audio.append(chunk)
            vadFeeder.accept(chunk)
            val speaking = vad.isSpeechDetected()
            if (speaking && phraseStart < 0) {
                // The detector notices speech a moment late; start a little earlier.
                phraseStart = maxOf(0, audio.size - PAD_SAMPLES)
                lastWindowAt = audio.size
                lastLiveAt = 0
            }
            if (phraseStart >= 0 && audio.size - lastWindowAt >= HOP_SAMPLES) {
                lastWindowAt = audio.size
                scoreWindow(maxOf(phraseStart, audio.size - WINDOW_SAMPLES), audio.size)
            }
            if (speaking && audio.size - lastLiveAt >= LIVE_EVERY_SAMPLES) {
                lastLiveAt = audio.size
                val text = liveWords()
                if (text != partial) {
                    partial = text
                    onLive(liveText())
                }
            }
            popSegments()
            endReason = endReason(speaking)
        }

        private fun endReason(speaking: Boolean): String? {
            if (stop.isCompleted) return "button"
            val lastHeard = if (profile != null) timeline.lastOwnerEnd() else lastSpeechEnd
            // A phrase that just started hasn't had its voice checked yet: it may be the owner.
            val unjudged = speaking && phraseStart >= 0 && audio.size - phraseStart < FIRST_VERDICT_SAMPLES
            val quietFor = audio.size - lastHeard
            return when {
                lastHeard >= 0 && !unjudged && (profile != null || !speaking) && quietFor > endSilenceNeeded() ->
                    if (speaking) "other voices" else "silence"
                lastHeard < 0 && audio.size > NO_SPEECH_SAMPLES -> "no-speech"
                audio.size > MAX_SAMPLES -> "max-length"
                else -> null
            }
        }

        /** Scores audio [from, to) against the voiceprint and adds it to the timeline. */
        private fun scoreWindow(from: Int, to: Int) {
            val owner = profile ?: return
            if (to - from < MIN_WINDOW_SAMPLES) return
            val score = VoiceProfile.score(engines, owner, audio.copy(from, to)) ?: return
            val needed = VoiceProfile.needed(threshold - WINDOW_SLACK, (to - from) / RATE.toFloat(), meter?.snr(from, to))
            timeline.add(from, to, score, needed)
        }

        /**
         * How long a pause ends the command. If what was said so far doesn't sound finished
         * (no closing punctuation, or it trails off on "and", "um", "the"…), wait longer: you're
         * probably taking a breath or remembering something.
         */
        private fun endSilenceNeeded(): Int {
            val last = liveText().trim()
            val lastWord = last.substringAfterLast(' ').trimEnd(',', '.', '?', '!').lowercase()
            val finished = last.endsWith(".") || last.endsWith("?") || last.endsWith("!")
            return if (finished && lastWord !in TRAILING_WORDS) endSilenceSamples else endSilenceSamples + UNFINISHED_EXTRA_SAMPLES
        }

        /**
         * The live words of the phrase in progress. With voice ID, only from where the owner has
         * been heard, so a video's words never appear on screen.
         */
        private fun liveWords(): String {
            if (phraseStart < 0) return ""
            if (profile == null) return recognize(audio.copy(phraseStart, audio.size), steer = false).joinToString(" ") { it.text }
            val spans = timeline.ownerSpans(phraseStart, audio.size).ifEmpty { return "" }
            val from = maxOf(phraseStart, spans.first().first - PAD_SAMPLES / 2)
            val to = minOf(audio.size, spans.last().last + 1 + PAD_SAMPLES)
            return recognize(audio.copy(from, to), steer = false).joinToString(" ") { it.text }
        }

        /** Treats any phrase still in progress as finished (the command ended mid-phrase). */
        fun finishSpeech() {
            vad.flush()
            popSegments()
        }

        fun release() {
            vad.reset()
        }

        fun result(): ListenResult {
            val kept = phrases.filter { it.accepted }
            val text = kept.joinToString(" ") { it.text }.trim()
            val command = text.takeIf { it.isNotEmpty() }?.let {
                HeardCommand(it, endReason ?: "mic-ended", soundsLikeStop = soundsLikeStop(it, kept))
            }
            return ListenResult(command, heardSegments(), logSegments(command))
        }

        /**
         * The recognizer is weakest on a lone word ("cancel" → "Hansel"). For a request that short,
         * ask the keyword spotter, which is built for short phrases, whether it sounded like a stop word.
         */
        private fun soundsLikeStop(text: String, kept: List<Phrase>): Boolean {
            if (keepPrivate) return false
            val wakePhrases = WakeWords.load(HaSettings(context))
            if (!ExitWords.maybeStopWord(text, wakePhrases)) return false
            val spotter = WakeSpotter.get(context) ?: return false
            val tokenizer = WakeTokenizer.get(context)
            val spec = ExitWords.SPOKEN_STOP_PHRASES.mapIndexed { i, phrase ->
                "${tokenizer.encode(phrase).joinToString(" ")} :2.0 #0.15 @$i"
            }.joinToString("/")
            val clip = audio.copy(maxOf(0, kept.first().start - PAD_SAMPLES), minOf(audio.size, kept.last().end + PAD_SAMPLES))
            return spotter.detects(clip, spec).also { if (it) AppLog.i("Short request ${'"'}$text${'"'} sounded like a stop word") }
        }

        private fun popSegments() {
            while (!vad.empty()) {
                handleSegment(vad.front())
                vad.pop()
            }
        }

        private fun handleSegment(segment: SpeechSegment) {
            val start = segment.start.toInt()
            val end = minOf(audio.size, start + segment.samples.size)
            // Score the end of the phrase too, and a phrase too short to have been scored yet.
            if (end - lastWindowAt > HOP_SAMPLES / 2 || phraseStart < 0) scoreWindow(maxOf(start, end - WINDOW_SAMPLES), end)
            phrases += judge(start, end)
            lastSpeechEnd = end
            phraseStart = -1
            partial = ""
            onLive(liveText())
        }

        /**
         * The owner's words in the phrase [start, end): those said while the timeline has the
         * owner talking, accepted if that part of the audio as a whole matches the voiceprint.
         * When it doesn't but sounds partly like the owner (talking over a video), VoxLock may
         * pull their voice out.
         */
        private fun judge(start: Int, end: Int): Phrase {
            val words = transcribe(start, end)
            val all = words.joinToString(" ") { it.text }
            val owner = profile ?: return Phrase(start, end, null, true, "kept", all)
            val spans = timeline.ownerSpans(start, end)
            if (spans.isEmpty()) {
                val mean = timeline.meanScore(start, end)
                return isolated(owner, start, end, mean) ?: Phrase(start, end, mean, false, "other", all)
            }
            val ownerAudio = spans.map { audio.copy(it.first, it.last + 1) }.reduce { a, b -> a + b }
            val seconds = ownerAudio.size / RATE.toFloat()
            val snr = meter?.snr(spans.first().first, spans.last().last + 1)
            val needed = VoiceProfile.needed(threshold, seconds, snr)
            val score = VoiceProfile.score(engines, owner, ownerAudio)
            val kept = words.filter { w -> spans.any { w.mid in it } }
            val part = if (seconds * RATE >= (end - start) * WHOLE_PHRASE) {
                "you"
            } else {
                "you %.1f–%.1fs".format((spans.first().first - start) / RATE.toFloat(), (spans.last().last + 1 - start) / RATE.toFloat())
            }
            val bar = "bar %.2f".format(needed) + (snr?.let { ", %.0f dB".format(it) } ?: "")
            if (score != null && score >= needed && kept.isNotEmpty()) {
                return Phrase(start, end, score, true, "$part ($bar)", kept.joinToString(" ") { it.text })
            }
            return isolated(owner, start, end, score)
                ?: Phrase(start, end, score, false, "not you ($bar)", kept.ifEmpty { words }.joinToString(" ") { it.text })
        }

        /** The owner's words pulled out of a mixed phrase by VoxLock, or null when that isn't possible or doesn't help. */
        private fun isolated(owner: Voiceprint, start: Int, end: Int, score: Float?): Phrase? {
            if ((score ?: 0f) < VoiceProfile.MIXED_MIN) return null
            val started = System.nanoTime()
            val isolated = VoiceIsolator.isolate(context, audio.copy(start, end)) ?: return null
            val millis = (System.nanoTime() - started) / 1_000_000
            val again = VoiceProfile.score(engines, owner, isolated) ?: return null
            val needed = VoiceProfile.needed(threshold, (end - start) / RATE.toFloat(), meter?.snr(start, end))
            if (again < needed) return null
            val how = "isolated %.2f→%.2f in %d ms".format(score ?: 0f, again, millis)
            return Phrase(start, end, again, true, how, recognize(isolated).joinToString(" ") { it.text })
        }

        /** Words of a phrase, read with a little audio around it; positions are absolute. */
        private fun transcribe(start: Int, end: Int): List<Word> {
            val from = maxOf(0, start - PAD_SAMPLES)
            val to = minOf(audio.size, end + PAD_SAMPLES)
            return recognize(audio.copy(from, to)).let { words ->
                words.mapIndexed { i, w ->
                    val wordStart = w.start + from
                    // A word's middle: halfway to the next word, at most a quarter second in.
                    val next = words.getOrNull(i + 1)?.start?.plus(from) ?: to
                    Word(w.text, wordStart, wordStart + minOf(MAX_HALF_WORD_SAMPLES, (next - wordStart) / 2))
                }
            }
        }

        /**
         * Words in [samples], with start positions relative to the clip. [steer]: lean toward the
         * user's names. Off for the live transcript, which re-reads clips too short to steer safely.
         */
        private fun recognize(samples: FloatArray, steer: Boolean = true): List<Word> {
            if (samples.isEmpty()) return emptyList()
            val model = engines.recognizer
            val stream = if (!steer || hotwords.isEmpty()) model.createStream() else model.createStream(hotwords)
            try {
                stream.acceptWaveform(samples, RATE)
                model.decode(stream)
                val result = model.getResult(stream)
                val words = mutableListOf<Pair<StringBuilder, Int>>()
                result.tokens.forEachIndexed { i, token ->
                    val at = (result.timestamps.getOrElse(i) { 0f } * RATE).toInt()
                    if (token.startsWith(" ") || words.isEmpty()) words += StringBuilder(token.trim()) to at
                    else words.last().first.append(token)
                }
                return words.map { (text, at) -> Word(text.toString(), at, at) }.filter { it.text.isNotEmpty() }
            } finally {
                stream.release()
            }
        }

        private fun liveText() =
            (phrases.filter { it.accepted }.map { it.text } + partial).filter { it.isNotBlank() }.joinToString(" ")

        private fun heardSegments() = phrases.map { p ->
            HeardSegment(
                startSeconds = p.start / RATE.toFloat(),
                seconds = (p.end - p.start) / RATE.toFloat(),
                score = p.score,
                accepted = p.accepted,
                how = p.how,
                preview = p.text,
            )
        }

        private fun logSegments(command: HeardCommand?): String {
            if (keepPrivate) {
                val line = "ended by ${endReason ?: "mic-ended"}: private (spoken PIN), ${phrases.size} phrase(s), words not logged"
                AppLog.i("Heard on phone, $line")
                return line
            }
            val details = heardSegments().joinToString("; ") { s ->
                val verdict = (if (s.accepted) "KEPT " else "DROPPED ") +
                    (s.score?.let { "%.2f ".format(it) } ?: "") + s.how
                "%.1fs+%.1fs %s \"%s\"".format(s.startSeconds, s.seconds, verdict, s.preview.take(80))
            }
            val outcome = command?.let { "final \"${it.text.take(200)}\"" } ?: "nothing accepted"
            val line = "ended by ${endReason ?: "mic-ended"}: $outcome | strictness %.2f | segments: %s"
                .format(threshold, details.ifEmpty { "none" })
            AppLog.i("Heard on phone, $line")
            return line
        }
    }

    /** A growable sample buffer for the whole session. */
    private class AudioBuffer {
        private var data = FloatArray(RATE * 10)
        var size = 0
            private set

        fun append(chunk: FloatArray) {
            if (size + chunk.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
            chunk.copyInto(data, size)
            size += chunk.size
        }

        fun copy(from: Int, to: Int): FloatArray = if (to <= from) FloatArray(0) else data.copyOfRange(from, to)
    }

    companion object {
        private const val RATE = MicRecorder.SAMPLE_RATE

        /** Extra audio read around phrases so first and last sounds aren't clipped. */
        private const val PAD_SAMPLES = RATE * 3 / 10

        /** How often the live transcript is refreshed while you talk. */
        private const val LIVE_EVERY_SAMPLES = RATE * 4 / 10

        // Who is talking: a 1.5 s window of the current phrase scored every 0.4 s (0.5 s at the
        // start of a phrase). Windows score a little lower than whole phrases, so their bar is
        // a little lower too; the owner's part is checked as a whole at the strictness later.
        private const val HOP_SAMPLES = RATE * 4 / 10
        private const val WINDOW_SAMPLES = RATE * 3 / 2
        private const val MIN_WINDOW_SAMPLES = RATE / 2
        private const val WINDOW_SLACK = 0.05f

        /** A new phrase gets this long to be checked before the owner counts as having stopped. */
        private const val FIRST_VERDICT_SAMPLES = MIN_WINDOW_SAMPLES + HOP_SAMPLES

        /** The owner's part covering this much of a phrase counts as the whole phrase, for the log. */
        private const val WHOLE_PHRASE = 0.9f

        private const val MAX_HALF_WORD_SAMPLES = RATE / 4

        /** Extra wait when the last words don't sound like a finished sentence. */
        private const val UNFINISHED_EXTRA_SAMPLES = RATE

        /** Words people trail off on mid-thought; ending on one means "not done yet". */
        private val TRAILING_WORDS = setOf(
            "and", "but", "or", "so", "because", "um", "uh", "the", "a", "an", "to", "of", "with",
            "for", "like", "if", "then", "that", "my", "is", "are", "was", "i",
        )

        private const val NO_SPEECH_SAMPLES = RATE * 8
        private const val MAX_SAMPLES = RATE * 30
    }
}
