package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTimelineTest {
    private val rate = 16_000
    private val hop = rate * 4 / 10
    private val window = rate * 3 / 2

    /** Windows every hop, as the listener scores them: [scoreAt] gives the score of the window ending at a time (s). */
    private fun timeline(seconds: Float, needed: Float = 0.40f, scoreAt: (Float) -> Float): SpeakerTimeline {
        val t = SpeakerTimeline(hop)
        var end = rate / 2
        while (end <= (seconds * rate).toInt()) {
            val start = maxOf(0, end - window)
            t.add(start, end, scoreAt(end / rate.toFloat()), needed)
            end += hop
        }
        return t
    }

    private fun seconds(span: IntRange) = (span.first / rate.toFloat()) to ((span.last + 1) / rate.toFloat())

    @Test
    fun ownerAloneIsOneStretch() {
        val t = timeline(4f) { 0.6f }
        val spans = t.ownerSpans(0, 4 * rate)
        assertEquals(1, spans.size)
        assertEquals(4f, seconds(spans[0]).second, 0.01f)
    }

    @Test
    fun tvAloneIsNobody() {
        val t = timeline(6f) { 0.1f }
        assertTrue(t.ownerSpans(0, 6 * rate).isEmpty())
        assertEquals(-1, t.lastOwnerEnd())
    }

    @Test
    fun ownerThenTvEndsWhereTheOwnerStopped() {
        // The owner talks for 3 s, then the TV talks on: windows reaching back into the owner's
        // speech score in between, then low.
        val t = timeline(8f) { end ->
            val ownerShare = ((3f - (end - 1.5f)) / 1.5f).coerceIn(0f, 1f)
            0.1f + 0.5f * ownerShare
        }
        val stop = t.lastOwnerEnd() / rate.toFloat()
        assertTrue("owner heard until about 3 s, got $stop", stop in 2.6f..3.7f)
    }

    @Test
    fun tvThenOwnerStartsNearWhereTheOwnerStarted() {
        val t = timeline(7f) { end ->
            val ownerShare = ((end - 3f) / 1.5f).coerceIn(0f, 1f)
            0.1f + 0.5f * ownerShare
        }
        val spans = t.ownerSpans(0, 7 * rate)
        val start = seconds(spans.first()).first
        assertTrue("owner starts about 3 s, got $start", start in 2.4f..3.6f)
    }

    @Test
    fun talkingOverTheTvBetweenClearStretchesIsKept() {
        // Owner clear, then owner and TV at once (mixed score), then owner clear again.
        val t = timeline(9f) { end -> if (end in 3.5f..5.5f) 0.33f else 0.6f }
        val spans = t.ownerSpans(0, 9 * rate)
        assertEquals(1, spans.size)
    }

    @Test
    fun mixedAfterTheOwnerIsKeptOnlyWhileItStillSoundsLikeThem() {
        val t = timeline(10f) { end ->
            when {
                end < 4f -> 0.6f
                end < 6f -> 0.34f // owner still talking over the TV
                else -> 0.12f // TV alone
            }
        }
        val stop = t.lastOwnerEnd() / rate.toFloat()
        assertTrue("owner heard until about 5–6 s, got $stop", stop in 4.8f..6.6f)
    }

    @Test
    fun silenceSplitsStretches() {
        val t = SpeakerTimeline(hop)
        t.add(0, rate, 0.6f, 0.4f)
        t.add(3 * rate, 4 * rate, 0.6f, 0.4f)
        assertEquals(2, t.ownerSpans(0, 4 * rate).size)
    }

    @Test
    fun barDropsForShortAndNoisySpeech() {
        // Long, clean speech: the strictness itself.
        assertEquals(0.45f, VoiceProfile.needed(0.45f, 3f, 30f), 0.001f)
        // A one-word answer: down to the floor.
        assertEquals(VoiceProfile.FLOOR, VoiceProfile.needed(0.45f, 0.5f, 30f), 0.001f)
        // Far away or over a loud TV: a little lower.
        assertEquals(0.39f, VoiceProfile.needed(0.45f, 3f, 6f), 0.001f)
        assertEquals(0.42f, VoiceProfile.needed(0.45f, 3f, 13f), 0.001f)
        // Never below the floor, whatever the strictness.
        assertEquals(VoiceProfile.FLOOR, VoiceProfile.needed(0.28f, 3f, 0f), 0.001f)
        // Unknown background: no allowance.
        assertEquals(0.45f, VoiceProfile.needed(0.45f, 3f, null), 0.001f)
        // A short sliver of a longer phrase someone else fills gets no short-phrase allowance.
        assertEquals(0.45f, VoiceProfile.needed(0.45f, 0.8f, 30f, isolated = false), 0.001f)
        assertEquals(0.39f, VoiceProfile.needed(0.45f, 0.8f, 6f, isolated = false), 0.001f)
    }

    @Test
    fun meterMeasuresSpeechAboveTheBackground() {
        val meter = SignalMeter()
        fun chunk(level: Float) = java.nio.ByteBuffer.allocate(1280).order(java.nio.ByteOrder.LITTLE_ENDIAN).also { b ->
            repeat(640) { i -> b.putShort(((if (i % 2 == 0) level else -level) * 32767).toInt().toShort()) }
        }.array()
        repeat(20) { meter.add(chunk(0.001f)) } // quiet room, -60 dB
        repeat(20) { meter.add(chunk(0.1f)) } // speech, -20 dB
        val snr = meter.snr(20 * 640, 40 * 640)!!
        assertEquals(40f, snr, 1f)
    }
}
