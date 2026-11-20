package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMemoryTest {
    private fun voice(setup: Float, needed: Float = 0.35f, seconds: Float = 2.5f, whole: Boolean = true, everyday: Float? = null) =
        HeardVoice(VoiceMatch(floatArrayOf(setup, 0f), setup, everyday), needed, seconds, whole)

    private fun phrase(text: String, accepted: Boolean, voice: HeardVoice?) = VoiceMemory.Phrase(text, accepted, voice)

    @Test
    fun aClearMatchWithTheSetupRecordingsIsLearned() {
        val lessons = VoiceMemory.lessons(listOf(phrase("I had a serving of orzo.", true, voice(0.59f))), null, 0)
        assertEquals(1, lessons.size)
    }

    @Test
    fun aNarrowOrShortOrSharedMatchIsNot() {
        fun learned(v: HeardVoice) = VoiceMemory.lessons(listOf(phrase("Open the garage.", true, v)), null, 0).size
        assertEquals(0, learned(voice(0.40f)))
        assertEquals(0, learned(voice(0.60f, seconds = 1.2f)))
        assertEquals(0, learned(voice(0.60f, whole = false)))
    }

    @Test
    fun whatWasLearnedBeforeCannotDecideWhatIsLearnedNext() {
        // Accepted on the everyday voice alone: kept, but not evidence for learning more.
        val v = voice(0.30f, everyday = 0.80f)
        assertEquals(0, VoiceMemory.lessons(listOf(phrase("What have I eaten today?", true, v)), null, 0).size)
    }

    @Test
    fun aPhraseTurnedAwayThenSaidAgainIsLearned() {
        val before = listOf(phrase("Vlog, eight ounces of juice.", false, voice(0.26f, seconds = 1.5f)))
        val now = listOf(phrase("Log eight ounces of juice.", true, voice(0.60f, seconds = 1.5f)))
        val lessons = VoiceMemory.lessons(now, before, 6_000)
        assertEquals(1, lessons.size)
        assertEquals(0.26f, lessons.single().embedding[0], 0.0001f)
    }

    @Test
    fun differentWordsALongWaitOrAVideoAreNotARepeat() {
        val now = listOf(phrase("Log eight ounces of juice.", true, voice(0.60f, seconds = 1.5f)))
        val other = listOf(phrase("I want some of it.", false, voice(0.26f, seconds = 2f)))
        assertEquals(0, VoiceMemory.lessons(now, other, 6_000).size)
        val same = listOf(phrase("Log eight ounces of juice.", false, voice(0.26f, seconds = 1.5f)))
        assertEquals(0, VoiceMemory.lessons(now, same, 120_000).size)
        val video = listOf(phrase("Log eight ounces of juice.", false, voice(0.05f, seconds = 1.5f)))
        assertEquals(0, VoiceMemory.lessons(now, video, 6_000).size)
    }

    @Test
    fun theEverydayVoiceCountsOnlyWhereTheSetupRecordingsDontRuleItOut() {
        assertEquals(0.60f, VoiceMatch(floatArrayOf(1f), setup = 0.28f, everyday = 0.60f).score, 0.0001f)
        assertEquals(0.10f, VoiceMatch(floatArrayOf(1f), setup = 0.10f, everyday = 0.60f).score, 0.0001f)
        assertEquals(0.50f, VoiceMatch(floatArrayOf(1f), setup = 0.50f, everyday = null).score, 0.0001f)
    }

    @Test
    fun repeatsAreComparedByTheirWords() {
        assertTrue(VoiceMemory.sameWords("Does that include Christmas?", "Does that include Christmas?"))
        assertTrue(VoiceMemory.sameWords("Vlog, eight ounces of juice.", "Log eight ounces of juice."))
        assertFalse(VoiceMemory.sameWords("No.", "No."))
        assertFalse(VoiceMemory.sameWords("Does that include today?", "Does that include Christmas and the day after?"))
    }

    @Test
    fun theEverydayVoiceNeedsEnoughPhrases() {
        val few = List(VoiceMemory.MIN - 1) { floatArrayOf(1f, 0f) }
        assertNull(VoiceMemory.average(few))
        val enough = List(VoiceMemory.MIN) { i -> if (i % 2 == 0) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
        val average = VoiceMemory.average(enough)!!
        assertEquals(average[0], average[1], 0.0001f)
        assertEquals(1.0, (average[0] * average[0] + average[1] * average[1]).toDouble(), 0.0001)
    }
}
