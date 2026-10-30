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
    /** How voice ID decided: kept, you, split, isolated, mix or other. */
    val how: String,
    /** The owner's words kept from this phrase, or what was heard if it was dropped. */
    val preview: String,
)

data class HeardCommand(
    val text: String,
    /** Why listening stopped: silence, button, no-speech, max-length or mic-ended. */
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
 * Does all the listening on the phone: finds phrases, shows their words live, keeps
 * only phrases in the owner's voice (when a voiceprint is given), decides when the
 * command is finished, and produces the text sent to Home Assistant.
 *
 * Each phrase is transcribed whole, with a little audio around it, which gives the best
 * accuracy. When another voice runs straight into the owner's without a pause, the phrase
 * is split in its text: at the sentence break nearest to where the voice changes.
 */
class PhoneListener(
    private val context: Context,
    private val profile: FloatArray?,
    /** Voice ID strictness from settings. */
    private val threshold: Float,
    /** Assist just asked a question, so a one- or two-word answer ("No", "Yes please") is likely. */
    private val expectingAnswer: Boolean = false,
    /** Silence after a finished-sounding sentence before the command counts as done. */
    endPauseSeconds: Float = 1.2f,
    /** What's said is private (a spoken PIN): keep the words out of the log. */
    private val keepPrivate: Boolean = false,
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

    private class Word(val text: String, val start: Int) {
        val endsSentence get() = text.lastOrNull()?.let { it == '.' || it == '?' || it == '!' } == true
    }

    private class Phrase(
        val start: Int,
        val end: Int,
        val score: Float?,
        val accepted: Boolean,
        val how: String,
        val text: String,
    )

    /** A speaker change found inside a phrase: [cut] is relative to the phrase start. */
    private class Split(val cut: Int, val keepRight: Boolean, val mine: Float, val other: Float, val millis: Long)

    private inner class Session(
        private val engines: SpeechEngines,
        private val stop: Deferred<Unit>,
        private val onLive: (String) -> Unit,
    ) {
        private val vad = engines.vad.also { it.reset() }
        private val vadFeeder = VadFeeder(vad)
        private val audio = AudioBuffer()

        private var phraseStart = -1

        // Where the owner started talking in the current phrase (-1 until heard). With voice ID
        // on, live words only show from here, so a video's words never appear on screen.
        private var ownerFrom = -1
        private var lastLiveAt = 0
        private var partial = ""
        private var lastKeptEnd = -1
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
                lastLiveAt = 0
            }
            if (speaking && audio.size - lastLiveAt >= LIVE_EVERY_SAMPLES) {
                lastLiveAt = audio.size
                val from = liveFrom()
                val text = if (from >= 0) recognize(audio.copy(from, audio.size)).joinToString(" ") { it.text } else ""
                if (text != partial) {
                    partial = text
                    onLive(liveText())
                }
            }
            popSegments()

            endReason = when {
                stop.isCompleted -> "button"
                lastKeptEnd >= 0 && !speaking && audio.size - lastKeptEnd > endSilenceNeeded() -> "silence"
                lastKeptEnd < 0 && !speaking && audio.size > NO_SPEECH_SAMPLES -> "no-speech"
                audio.size > MAX_SAMPLES -> "max-length"
                else -> null
            }
        }

        /**
         * How long a pause ends the command. If what was said so far doesn't sound finished
         * (no closing punctuation, or it trails off on "and", "um", "the"…), wait longer: you're
         * probably taking a breath or remembering something.
         */
        private fun endSilenceNeeded(): Int {
            val last = phrases.lastOrNull { it.accepted }?.text?.trim().orEmpty()
            val lastWord = last.substringAfterLast(' ').trimEnd(',', '.', '?', '!').lowercase()
            val finished = last.endsWith(".") || last.endsWith("?") || last.endsWith("!")
            return if (finished && lastWord !in TRAILING_WORDS) endSilenceSamples else endSilenceSamples + UNFINISHED_EXTRA_SAMPLES
        }

        /**
         * Where live words should start in the current phrase, or -1 to show nothing yet.
         * With voice ID, that's once the last 1.5 s of audio sounds like the owner.
         */
        private fun liveFrom(): Int {
            val owner = profile ?: return phraseStart
            if (ownerFrom < 0) {
                val windowStart = maxOf(phraseStart, audio.size - LIVE_WINDOW_SAMPLES)
                if (audio.size - windowStart >= LIVE_MIN_SAMPLES) {
                    val score = VoiceProfile.score(engines, owner, audio.copy(windowStart, audio.size)) ?: 0f
                    if (score >= threshold - LIVE_SLACK) ownerFrom = windowStart
                }
            }
            return if (ownerFrom < 0) -1 else maxOf(phraseStart, ownerFrom - PAD_SAMPLES / 2)
        }

        /** Treats any phrase still in progress as finished (e.g. the mic button was tapped mid-word). */
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
            val end = start + segment.samples.size
            phrases += if (profile == null) {
                Phrase(start, end, null, true, "kept", transcribe(start, end).joinToString(" ") { it.text })
            } else {
                judge(profile, segment.samples, start, end)
            }
            if (phrases.last().accepted) lastKeptEnd = end
            phraseStart = -1
            ownerFrom = -1
            partial = ""
            onLive(liveText())
        }

        /**
         * Decides whether a phrase is the owner's. A middling score on a longer phrase usually
         * means another voice ran into the owner's without a pause: then the voice change is
         * located and the text is cut at the nearest sentence break. A phrase that sounds
         * partly like the owner (talking over a video) gets their voice pulled out by VoxLock.
         */
        private fun judge(profile: FloatArray, samples: FloatArray, start: Int, end: Int): Phrase {
            val seconds = samples.size / RATE.toFloat()
            val needed = VoiceProfile.thresholdFor(seconds, threshold)
            val whole = VoiceProfile.score(engines, profile, samples)
                ?: return Phrase(start, end, null, false, "too short", "")
            if (whole >= CLEARLY_YOU) return Phrase(start, end, whole, true, "you", text(start, end))

            if (samples.size >= 2 * SPLIT_MIN_PART) {
                val split = bestSplit(profile, samples)
                if (split != null) {
                    val words = transcribe(start, end)
                    val at = splitWords(words, start + split.cut)
                    if (at != null) {
                        val kept = if (split.keepRight) words.drop(at) else words.take(at)
                        val how = "split at sentence break %.2fs, you %.2f / other %.2f in %d ms"
                            .format(split.cut / RATE.toFloat(), split.mine, split.other, split.millis)
                        return Phrase(start, end, split.mine, kept.isNotEmpty(), how, kept.joinToString(" ") { it.text })
                    }
                }
            }
            if (whole >= needed) return Phrase(start, end, whole, true, "you", text(start, end))

            if (whole >= VoiceProfile.MIXED_MIN) {
                val started = System.nanoTime()
                val isolated = VoiceIsolator.isolate(context, samples)
                val millis = (System.nanoTime() - started) / 1_000_000
                if (isolated != null) {
                    val again = VoiceProfile.score(engines, profile, isolated)
                    if (again != null && again >= needed) {
                        val how = "isolated %.2f→%.2f in %d ms".format(whole, again, millis)
                        return Phrase(start, end, again, true, how, recognize(isolated).joinToString(" ") { it.text })
                    }
                    val how = "mix, isolated only %.2f in %d ms".format(again ?: 0f, millis)
                    return shortAnswer(start, end) ?: Phrase(start, end, whole, false, how, text(start, end))
                }
            }
            return shortAnswer(start, end) ?: Phrase(start, end, whole, false, "other", text(start, end))
        }

        /**
         * A one- or two-word answer tacked onto another voice ("…sitting in bed? No") is too
         * short for voice ID. When Assist just asked a question, keep such a piece if it is
         * clearly louder than the rest of the phrase: the owner is near the phone, the TV isn't.
         */
        private fun shortAnswer(start: Int, end: Int): Phrase? {
            if (!expectingAnswer) return null
            val words = transcribe(start, end)
            val from = maxOf(0, start - PAD_SAMPLES)
            val to = minOf(audio.size, end + PAD_SAMPLES)
            for (i in 1 until words.size) {
                if (!words[i - 1].endsSentence) continue
                val cut = words[i].start
                for (keepRight in listOf(true, false)) {
                    val piece = if (keepRight) words.drop(i) else words.take(i)
                    val (pieceFrom, pieceTo) = if (keepRight) cut to to else from to cut
                    if (piece.size > 2 || pieceTo - pieceFrom > SHORT_ANSWER_MAX_SAMPLES) continue
                    val louder = loudness(pieceFrom, pieceTo) - if (keepRight) loudness(from, cut) else loudness(cut, to)
                    if (louder >= SHORT_ANSWER_LOUDER_DB) {
                        val how = "short answer, %.1f dB louder than the rest".format(louder)
                        return Phrase(start, end, null, true, how, piece.joinToString(" ") { it.text })
                    }
                }
            }
            return null
        }

        /** Speech loudness in dB, ignoring near-silent samples. */
        private fun loudness(from: Int, to: Int): Float {
            val loud = audio.copy(from, to).filter { kotlin.math.abs(it) > 0.01f }
            if (loud.isEmpty()) return -99f
            val rms = kotlin.math.sqrt(loud.sumOf { (it * it).toDouble() } / loud.size)
            return (20 * kotlin.math.log10(rms + 1e-9)).toFloat()
        }

        /**
         * Index of the word to cut before: the sentence break nearest to [cutAt] (an absolute
         * sample position), within [SNAP_SAMPLES]. Null when there's no break near the change,
         * which usually means it was one speaker after all.
         */
        private fun splitWords(words: List<Word>, cutAt: Int): Int? =
            (1 until words.size)
                .filter { i -> words[i - 1].endsSentence && kotlin.math.abs(words[i].start - cutAt) <= SNAP_SAMPLES }
                .minByOrNull { i -> kotlin.math.abs(words[i].start - cutAt) }

        /** The cut (on a quarter-second grid) where one side is clearly the owner and the other isn't. */
        private fun bestSplit(profile: FloatArray, samples: FloatArray): Split? {
            val started = System.nanoTime()
            var best: Triple<Int, Boolean, Pair<Float, Float>>? = null
            for (cut in SPLIT_MIN_PART..samples.size - SPLIT_MIN_PART step SPLIT_STEP) {
                val left = VoiceProfile.score(engines, profile, samples.copyOfRange(0, cut)) ?: 0f
                val right = VoiceProfile.score(engines, profile, samples.copyOfRange(cut, samples.size)) ?: 0f
                for ((keepRight, mine, other) in listOf(Triple(true, right, left), Triple(false, left, right))) {
                    val margin = best?.third?.let { it.first - it.second }
                    if (mine >= threshold && other <= SPLIT_OTHER_MAX && (margin == null || mine - other > margin)) {
                        best = Triple(cut, keepRight, mine to other)
                    }
                }
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            return best?.let { (cut, keepRight, scores) -> Split(cut, keepRight, scores.first, scores.second, millis) }
        }

        /** Words of a phrase, read with a little audio around it; word starts are absolute positions. */
        private fun transcribe(start: Int, end: Int): List<Word> {
            val from = maxOf(0, start - PAD_SAMPLES)
            return recognize(audio.copy(from, minOf(audio.size, end + PAD_SAMPLES))).map { Word(it.text, it.start + from) }
        }

        private fun text(start: Int, end: Int) = transcribe(start, end).joinToString(" ") { it.text }

        /** Words in [samples], with start positions relative to the clip. */
        private fun recognize(samples: FloatArray): List<Word> {
            if (samples.isEmpty()) return emptyList()
            val model = engines.recognizer
            val stream = model.createStream()
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
                return words.map { (text, at) -> Word(text.toString(), at) }.filter { it.text.isNotEmpty() }
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

        /** Live check of who is talking: the last 1.5 s (at least 1 s) must sound like the owner. */
        private const val LIVE_WINDOW_SAMPLES = RATE * 3 / 2
        private const val LIVE_MIN_SAMPLES = RATE
        private const val LIVE_SLACK = 0.05f

        /** Above this a phrase is plainly the owner and isn't searched for a speaker change. */
        private const val CLEARLY_YOU = 0.65f

        // Speaker-change search: cuts every 0.25 s, each side at least 0.8 s, and the other
        // side must score like someone else. The text is then cut at a sentence break within
        // 0.7 s of that point.
        private const val SPLIT_STEP = RATE / 4
        private const val SPLIT_MIN_PART = RATE * 8 / 10
        private const val SPLIT_OTHER_MAX = 0.30f
        private const val SNAP_SAMPLES = RATE * 7 / 10

        /** Short answers: at most 1.2 s, and at least 3 dB louder than the voice they're attached to. */
        private const val SHORT_ANSWER_MAX_SAMPLES = RATE * 6 / 5
        private const val SHORT_ANSWER_LOUDER_DB = 3f

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
