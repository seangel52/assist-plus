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
    /** The voice check behind [score], for learning the owner's voice afterward; null without voice ID. */
    val voice: HeardVoice? = null,
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
 *
 * Two kinds of audio are used. Voices are told apart on the noise-reduced audio, as the
 * voiceprint was made. Words are read from the raw microphone: on the owner's saved requests
 * noise reduction made the recognizer mishear ("protein shake" → "protein sake"). And the text that is sent is read in one pass over everything the owner
 * said, not phrase by phrase: the recognizer leans on the words around a word, and a phrase cut
 * off at a short pause lost them ("today" → "to day", "cat shaped mug" → "catch it's mug",
 * "strawberry protein shake" → "strawberry pro. Protein shake"). The words shown while talking
 * are a quick phrase-by-phrase draft of that.
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
    /** The raw mic: its loudness (to allow for a distant voice or loud background) and its audio (to read words from). */
    private val meter: SignalMeter? = null,
    /** Dictation: while this says so, listening goes on through any pause until the button is pressed. */
    private val keepListening: () -> Boolean = { false },
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
        /** Where in the session this phrase's words count as the owner's. */
        val keep: List<IntRange> = emptyList(),
        val voice: HeardVoice? = null,
    ) {
        /** The live words on screen for this phrase just before it ended, for comparing with [text] in the log. */
        var shown: String = ""
    }

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
            if (keepListening()) return if (audio.size > MAX_DICTATION_SAMPLES) "max-length" else null
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

        /**
         * Scores audio [from, to) against the voiceprint and adds it to the timeline. [isolated]: the
         * window is a whole short phrase on its own (only known once it ended), which gets the
         * short-phrase allowance; windows of a phrase still going on don't.
         */
        private fun scoreWindow(from: Int, to: Int, isolated: Boolean = false) {
            val owner = profile ?: return
            if (to - from < (if (isolated) MIN_PHRASE_SAMPLES else MIN_WINDOW_SAMPLES)) return
            val score = VoiceProfile.score(engines, owner, audio.copy(from, to)) ?: return
            val needed = VoiceProfile.needed(threshold - WINDOW_SLACK, (to - from) / RATE.toFloat(), meter?.snr(from, to), isolated)
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
            if (profile == null) return recognize(speech(phraseStart, audio.size), steer = false).joinToString(" ") { it.text }
            val spans = timeline.ownerSpans(phraseStart, audio.size).ifEmpty { return "" }
            val from = maxOf(phraseStart, spans.first().first - PAD_SAMPLES / 2)
            val to = minOf(audio.size, spans.last().last + 1 + PAD_SAMPLES)
            return recognize(speech(from, to), steer = false).joinToString(" ") { it.text }
        }

        /** The audio words are read from: the raw microphone, or what the listener was given if that isn't there. */
        private fun speech(from: Int, to: Int): FloatArray = meter?.audio(from, to) ?: audio.copy(from, to)

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
            val text = finalText(kept)
            val command = text.takeIf { it.isNotEmpty() }?.let {
                HeardCommand(it, endReason ?: "mic-ended", soundsLikeStop = soundsLikeStop(it, kept))
            }
            return ListenResult(command, heardSegments(), logSegments(command))
        }

        /**
         * The text to send: the owner's phrases read together, in one pass, wherever they follow
         * each other within [READ_TOGETHER_SAMPLES] (see the class comment). Words are dropped by
         * when they were said, only where another voice was: in a phrase that was turned away, or
         * outside the owner's part of a shared phrase.
         */
        private fun finalText(kept: List<Phrase>): String {
            val pieces = mutableListOf<String>()
            var i = 0
            while (i < kept.size) {
                var j = i
                // Not more at once than the recognizer reads well (dictation can run for minutes).
                while (j + 1 < kept.size && kept[j + 1].start - kept[j].end <= READ_TOGETHER_SAMPLES &&
                    kept[j + 1].end - kept[i].start <= MAX_SAMPLES
                ) j++
                pieces += readTogether(kept.subList(i, j + 1))
                i = j + 1
            }
            return pieces.filter { it.isNotBlank() }.joinToString(" ").trim()
        }

        private fun readTogether(run: List<Phrase>): String {
            val draft = run.joinToString(" ") { it.text }
            // One phrase was already read whole, with the audio around it.
            if (run.size == 1) return draft
            val from = run.first().start
            val to = run.last().end
            val turnedAway = phrases.filter { !it.accepted && it.end > from && it.start < to }
            fun owners(words: List<Word>) = words.filter { w ->
                val phrase = run.firstOrNull { w.mid in it.start until it.end }
                when {
                    run.any { p -> p.keep.any { w.mid in it } } -> true
                    // In an owner's phrase, but not in the owner's part of it.
                    phrase != null -> false
                    // Between phrases: the owner's, unless that's where a turned-away phrase was.
                    else -> turnedAway.none { w.mid in it.start until it.end }
                }
            }.map { it.text }
            val together = owners(transcribe(from, to))
            val byPhrase = draft.split(' ').filter { it.isNotBlank() }
            // A pass that lost much of what the phrases held on their own went wrong; keep the draft.
            if (together.size < byPhrase.size * MIN_SHARE_OF_DRAFT) {
                if (!keepPrivate) AppLog.i("Reading ${run.size} phrases together gave too little (\"${together.joinToString(" ").take(80)}\"); kept them as read one by one")
                return draft
            }
            // Where the two readings differ, the noise-reduced audio has the casting vote (see Readings).
            val settled = Readings.reconcile(together, byPhrase) { owners(transcribe(from, to, cleaned = true)) }
            if (!keepPrivate && settled != together) {
                AppLog.i("Readings differed: together \"${together.joinToString(" ").take(160)}\", settled as \"${settled.joinToString(" ").take(160)}\"")
            }
            return settled.joinToString(" ")
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
            // Score the end of the phrase too. A phrase no longer than a window is scored whole,
            // as a short phrase on its own.
            val short = end - start <= WINDOW_SAMPLES
            if (short || end - lastWindowAt > HOP_SAMPLES / 2 || phraseStart < 0) {
                scoreWindow(maxOf(start, end - WINDOW_SAMPLES), end, isolated = short)
            }
            // While the phrase was going on, its first words were only ever judged by short
            // windows (0.5 s, 0.9 s…) that also held the silence before it, and those score low.
            // Now that it's over, give its start one full window from the first word, so the
            // start is judged on as much audio as the rest.
            if (!short) scoreWindow(start, start + WINDOW_SAMPLES)
            val shownBefore = partial
            phrases += judge(start, end).also { it.shown = shownBefore }
            lastSpeechEnd = end
            phraseStart = -1
            partial = ""
            onLive(liveText())
        }

        /**
         * The owner's words in the phrase [start, end): those said while the timeline has the
         * owner talking, accepted if that part of the audio as a whole matches the voiceprint.
         */
        private fun judge(start: Int, end: Int): Phrase {
            val verdict = judgeAlone(start, end)
            if (verdict.accepted) return verdict
            // The speech detector splits a sentence at tiny pauses ("I ate half of a Lean." /
            // "Burger."), and a piece under a second and a half is too little audio to tell a voice
            // by: it scores low or can't be scored at all. Right after the owner's own words, with
            // no sign of another voice in it, it's the owner going on.
            val previous = phrases.lastOrNull()
            val continues = previous != null && previous.accepted && start - previous.end <= CONTINUE_GAP_SAMPLES
            val brief = end - start <= WINDOW_SAMPLES
            val notOther = verdict.score == null || verdict.score > SpeakerTimeline.OTHER_MAX
            if (continues && brief && notOther) {
                val text = transcribe(start, end).joinToString(" ") { it.text }
                if (text.isNotBlank()) {
                    val all = listOf(start - PAD_SAMPLES until end + PAD_SAMPLES)
                    return Phrase(start, end, verdict.score, true, "you, continuing (${verdict.how})", text, all, verdict.voice)
                }
            }
            return verdict
        }

        private fun judgeAlone(start: Int, end: Int): Phrase {
            val words = transcribe(start, end)
            val all = words.joinToString(" ") { it.text }
            val owner = profile ?: return Phrase(start, end, null, true, "kept", all, listOf(start - PAD_SAMPLES until end + PAD_SAMPLES))
            val spans = timeline.ownerSpans(start, end, closedEnd = true)
            if (spans.isEmpty()) {
                // No stretch of it passed as the owner. The phrase as a whole is still checked, so
                // that if the owner says it again and is accepted, their voice can be learned from it.
                val seconds = (end - start) / RATE.toFloat()
                val voice = VoiceProfile.match(engines, owner, audio.copy(start, end))?.let {
                    HeardVoice(it, VoiceProfile.needed(threshold, seconds, meter?.snr(start, end)), seconds, whole = true)
                }
                return Phrase(start, end, timeline.meanScore(start, end), false, "other", all, voice = voice)
            }
            val ownerAudio = spans.map { audio.copy(it.first, it.last + 1) }.reduce { a, b -> a + b }
            val seconds = ownerAudio.size / RATE.toFloat()
            val snr = meter?.snr(spans.first().first, spans.last().last + 1)
            val whole = seconds * RATE >= (end - start) * WHOLE_PHRASE
            val needed = VoiceProfile.needed(threshold, seconds, snr, isolated = whole)
            val match = VoiceProfile.match(engines, owner, ownerAudio)
            val score = match?.score
            val voice = match?.let { HeardVoice(it, needed, seconds, whole) }
            // The speech detector trims a phrase tightly, so a first or last word can sit partly
            // outside it: a stretch of the owner's speech that reaches the phrase's edge covers
            // the words read from the padding there too.
            val reach = spans.map { span ->
                val from = if (span.first - start < HOP_SAMPLES) start - PAD_SAMPLES else span.first
                val to = if (end - (span.last + 1) < HOP_SAMPLES) end + PAD_SAMPLES else span.last + 1
                from until to
            }
            val kept = words.filter { w -> reach.any { w.mid in it } }
            val part = if (whole) {
                "you"
            } else {
                "you %.1f–%.1fs".format((spans.first().first - start) / RATE.toFloat(), (spans.last().last + 1 - start) / RATE.toFloat())
            }
            val bar = "bar %.2f".format(needed) + (snr?.let { ", %.0f dB".format(it) } ?: "")
            if (score != null && score >= needed && kept.isNotEmpty()) {
                // Which way the voice matched, when it was the everyday voice that carried it.
                val by = match?.everyday?.takeIf { it > match.setup }?.let { ", everyday voice; setup %.2f".format(match.setup) } ?: ""
                return Phrase(start, end, score, true, "$part ($bar$by)", kept.joinToString(" ") { it.text }, reach, voice)
            }
            return Phrase(start, end, score, false, "not you ($bar)", kept.ifEmpty { words }.joinToString(" ") { it.text }, voice = voice)
        }

        /**
         * Words of a phrase, read with a little audio around it; positions are absolute.
         * [cleaned]: read the noise-reduced audio instead of the raw microphone.
         */
        private fun transcribe(start: Int, end: Int, cleaned: Boolean = false): List<Word> {
            val from = maxOf(0, start - PAD_SAMPLES)
            val to = minOf(audio.size, end + PAD_SAMPLES)
            return recognize(if (cleaned) audio.copy(from, to) else speech(from, to)).let { words ->
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
                voice = p.voice,
            )
        }

        private fun logSegments(command: HeardCommand?): String {
            if (keepPrivate) {
                val line = "ended by ${endReason ?: "mic-ended"}: private (spoken PIN), ${phrases.size} phrase(s), words not logged"
                AppLog.i("Heard on phone, $line")
                return line
            }
            fun plain(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), "").replace(Regex(" +"), " ").trim()
            val details = heardSegments().zip(phrases).joinToString("; ") { (s, p) ->
                val verdict = (if (s.accepted) "KEPT " else "DROPPED ") +
                    (s.score?.let { "%.2f ".format(it) } ?: "") + s.how
                // What the screen showed for this phrase just before it ended, when that differs.
                val shown = if (p.shown.isNotBlank() && plain(p.shown) != plain(p.text)) " shown \"${p.shown.take(80)}\"" else ""
                "%.1fs+%.1fs %s \"%s\"%s".format(s.startSeconds, s.seconds, verdict, s.preview.take(80), shown)
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

        // Who is talking: a 1.5 s window of the current phrase scored every 0.4 s, from 0.5 s in;
        // a phrase no longer than a window is also scored whole when it ends. Windows score a
        // little lower than whole phrases, so their bar is
        // a little lower too; the owner's part is checked as a whole at the strictness later.
        private const val HOP_SAMPLES = RATE * 4 / 10
        private const val WINDOW_SAMPLES = RATE * 3 / 2
        private const val MIN_WINDOW_SAMPLES = RATE / 2
        private const val MIN_PHRASE_SAMPLES = RATE / 4
        private const val WINDOW_SLACK = 0.05f

        /** A new phrase gets this long to be checked before the owner counts as having stopped. */
        private const val FIRST_VERDICT_SAMPLES = MIN_WINDOW_SAMPLES + HOP_SAMPLES

        /** The owner's part covering this much of a phrase counts as the whole phrase, for the log. */
        private const val WHOLE_PHRASE = 0.9f

        private const val MAX_HALF_WORD_SAMPLES = RATE / 4

        /** A brief piece starting this soon after the owner's accepted words counts as them going on (see judge). */
        private const val CONTINUE_GAP_SAMPLES = RATE * 8 / 10

        /** Extra wait when the last words don't sound like a finished sentence. */
        private const val UNFINISHED_EXTRA_SAMPLES = RATE

        /** Words people trail off on mid-thought; ending on one means "not done yet". */
        private val TRAILING_WORDS = setOf(
            "and", "but", "or", "so", "because", "um", "uh", "the", "a", "an", "to", "of", "with",
            "for", "like", "if", "then", "that", "my", "is", "are", "was", "i",
        )

        /** The owner's phrases this close together are read in one pass for the text that is sent. */
        private const val READ_TOGETHER_SAMPLES = RATE * 3

        /** Reading phrases together must give at least this share of the words they held one by one. */
        private const val MIN_SHARE_OF_DRAFT = 0.6f

        private const val NO_SPEECH_SAMPLES = RATE * 8
        private const val MAX_SAMPLES = RATE * 30

        /** Dictation stops by itself after this long, in case the button is never pressed. */
        private const val MAX_DICTATION_SAMPLES = RATE * 300
    }
}
