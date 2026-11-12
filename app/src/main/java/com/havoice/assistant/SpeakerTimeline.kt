package com.havoice.assistant

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

enum class Speaker { OWNER, OTHER, UNSURE }

/**
 * Who is talking when, over one listening session. Voice ID scores overlapping windows of
 * audio (about 1.5 s, every 0.4 s); each short slice of time gets the weighted average of the
 * windows over it, the ones centred on it counting most. A slice is the owner when that
 * average reaches the bar, someone else when it is clearly low, and unsure in between: the
 * owner and another voice at once, or a change of speaker inside the window.
 *
 * Positions are sample offsets from the start of the session.
 */
class SpeakerTimeline(private val slice: Int) {
    private class Slice {
        var score = 0.0
        var needed = 0.0
        var weight = 0.0
    }

    private val slices = mutableListOf<Slice>()

    /** Adds a window [start, end) that scored [score] against the voiceprint, where [needed] counts as the owner. */
    fun add(start: Int, end: Int, score: Float, needed: Float) {
        if (end <= start) return
        val center = (start + end) / 2.0
        val half = (end - start) / 2.0
        for (k in start / slice..(end - 1) / slice) {
            while (slices.size <= k) slices += Slice()
            val sliceStart = k * slice
            val covered = (minOf(end, sliceStart + slice) - maxOf(start, sliceStart)) / slice.toDouble()
            val nearCenter = 1.0 - abs(sliceStart + slice / 2.0 - center) / half
            val weight = covered * nearCenter.coerceAtLeast(MIN_WEIGHT)
            slices[k].score += score * weight
            slices[k].needed += needed * weight
            slices[k].weight += weight
        }
    }

    /** The speaker of slice [k], or null where nothing was scored (silence). */
    fun speaker(k: Int): Speaker? {
        val s = slices.getOrNull(k)?.takeIf { it.weight > 0 } ?: return null
        val score = s.score / s.weight
        return when {
            score >= s.needed / s.weight -> Speaker.OWNER
            score <= OTHER_MAX -> Speaker.OTHER
            else -> Speaker.UNSURE
        }
    }

    private fun scoreOf(k: Int): Double? = slices.getOrNull(k)?.takeIf { it.weight > 0 }?.let { it.score / it.weight }

    /**
     * The owner's speech within [from, to): owner slices, plus unsure ones that are the owner
     * talking over another voice: between two owner stretches, or right next to one with a
     * score that still has the owner in it.
     *
     * A cut needs evidence of someone else. An unsure stretch that runs from the owner's speech
     * to the start of the range (or to its end, when [closedEnd]: the phrase is over) has no
     * other voice beyond it: that's the owner's first or last words, judged on too little audio
     * (the first windows of a phrase are short and partly silence), so it's kept whole. Where
     * another voice does sit beyond it, only the part that still sounds like the owner is kept.
     * The open end of a phrase still in progress is left out: the next windows will tell.
     */
    fun ownerSpans(from: Int, to: Int, closedEnd: Boolean = false): List<IntRange> {
        if (to <= from) return emptyList()
        val first = from / slice
        val last = (to - 1) / slice
        val labels = (first..last).map { speaker(it) }
        val keep = BooleanArray(labels.size) { labels[it] == Speaker.OWNER }
        var i = 0
        while (i < labels.size) {
            if (labels[i] != Speaker.UNSURE) {
                i++
                continue
            }
            var j = i
            while (j < labels.size && labels[j] == Speaker.UNSURE) j++
            val ownerBefore = i > 0 && labels[i - 1] == Speaker.OWNER
            val ownerAfter = j < labels.size && labels[j] == Speaker.OWNER
            // Nothing scored beyond the run (the range's edge, or silence): no other voice there.
            val openStart = i == 0 || labels[i - 1] == null
            val openEnd = (j == labels.size && closedEnd) || (j < labels.size && labels[j] == null)
            when {
                ownerBefore && ownerAfter -> for (k in i until j) keep[k] = true
                ownerAfter && openStart -> for (k in i until j) keep[k] = true
                ownerBefore && openEnd -> for (k in i until j) keep[k] = true
                ownerBefore -> {
                    var k = i
                    while (k < j && (scoreOf(first + k) ?: 0.0) >= MIXED_KEEP) keep[k++] = true
                }
                ownerAfter -> {
                    var k = j - 1
                    while (k >= i && (scoreOf(first + k) ?: 0.0) >= MIXED_KEEP) keep[k--] = true
                }
            }
            i = j
        }
        val spans = mutableListOf<IntRange>()
        var k = 0
        while (k < keep.size) {
            if (!keep[k]) {
                k++
                continue
            }
            val runStart = k
            while (k < keep.size && keep[k]) k++
            val start = maxOf(from, (first + runStart) * slice)
            val end = minOf(to, (first + k) * slice)
            if (end > start) spans += start until end
        }
        return spans
    }

    /** Where the owner was last heard (end of their last stretch), or -1 if not yet. */
    fun lastOwnerEnd(): Int = ownerSpans(0, slices.size * slice).lastOrNull()?.let { it.last + 1 } ?: -1

    /** Average voice ID score over [from, to), for the log. */
    fun meanScore(from: Int, to: Int): Float? {
        val scores = (from / slice..maxOf(from, to - 1) / slice).mapNotNull { scoreOf(it) }
        return if (scores.isEmpty()) null else scores.average().toFloat()
    }

    companion object {
        /** At or below this a slice is someone else: a TV or video scores 0–0.16. */
        const val OTHER_MAX = 0.2f

        /** An unsure slice next to the owner's speech, scoring at least this, still has the owner in it. */
        const val MIXED_KEEP = 0.3

        // Windows count a little at their edges too, so every covered slice gets a score.
        private const val MIN_WEIGHT = 0.1
    }
}

/**
 * Loudness of the raw microphone (before noise reduction), 40 ms at a time, to tell how far
 * speech stands above the background. A distant voice, or one over a loud TV, stands out less.
 */
class SignalMeter {
    private val levels = mutableListOf<Float>()
    private val ends = mutableListOf<Int>()
    private var samples = 0

    @Synchronized
    fun add(pcm: ByteArray) {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = shorts.remaining()
        if (n == 0) return
        var sum = 0.0
        for (i in 0 until n) {
            val s = shorts.get(i) / 32768.0
            sum += s * s
        }
        samples += n
        levels += (10 * log10(sum / n + 1e-12)).toFloat()
        ends += samples
    }

    /**
     * How many dB the loudest half of [from, to) stands above the background (the quietest
     * tenth of everything heard so far); null until there's enough audio to tell.
     */
    @Synchronized
    fun snr(from: Int, to: Int): Float? {
        if (levels.size < MIN_CHUNKS) return null
        val inRange = levels.indices.filter { ends[it] > from && ends[it] <= to + CHUNK_SLACK }.map { levels[it] }
        if (inRange.isEmpty()) return null
        val speech = inRange.sortedDescending().take(maxOf(1, inRange.size / 2)).average().toFloat()
        val background = levels.sorted()[levels.size / 10]
        return speech - background
    }

    private companion object {
        const val MIN_CHUNKS = 12
        const val CHUNK_SLACK = 640
    }
}
