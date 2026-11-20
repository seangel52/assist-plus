package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingsTest {
    private fun words(s: String) = s.split(' ')

    private fun settle(together: String, byPhrase: String, third: String) =
        Readings.reconcile(words(together), words(byPhrase)) { words(third) }.joinToString(" ")

    @Test
    fun agreeingReadingsNeedNoThird() {
        val out = Readings.reconcile(words("Turn off the kitchen lights."), words("turn off the kitchen lights")) {
            throw AssertionError("not needed")
        }
        assertEquals("Turn off the kitchen lights.", out.joinToString(" "))
    }

    @Test
    fun theLongReadingWinsWhenTheThirdAgreesWithIt() {
        assertEquals(
            "How many dips did I do today?",
            settle("How many dips did I do today?", "How many dips did I do to day?", "how many dips did I do today"),
        )
        assertEquals(
            "Cat shaped mug No, a cat shaped cat.",
            settle("Cat shaped mug No, a cat shaped cat.", "Catch it's mug. No, a cat shaped. Cat.", "Cat shapes mug. No, a cat shaped cat."),
        )
    }

    @Test
    fun aWordSaidTwiceAtAPhraseBreakIsDropped() {
        assertEquals(
            "I had a Porters Pick Strawberry Protein Shake.",
            settle(
                "I had a Porters Pick Strawberry Protein Shake.",
                "I had a Porters Pick Strawberry Pro. Protein Shake",
                "I had a porter's pick strawberry protein shake.",
            ),
        )
    }

    @Test
    fun thePhraseReadingWinsWhenTheThirdAgreesWithIt() {
        assertEquals(
            "I had matcha tea and I had a slice of sandwich bread wheat",
            settle(
                "I had Macha T and I had a slice of sandwich bread wheat",
                "I had matcha tea and I had a slice of sandwich bread wheat,",
                "I had matcha tea, and I had a slice of sandwich bread, wheat",
            ),
        )
    }

    @Test
    fun theSameLettersWrittenAsOtherWordsAreNotADifference() {
        assertEquals(
            "I walked today at five percent incline",
            settle("I walked today at five percent incline", "I walked to day at five per cent incline,", "I walked to day at five per cent incline"),
        )
    }

    @Test
    fun withNoAgreementTheLongReadingStays() {
        assertEquals("I had a serving of Ozo", settle("I had a serving of Ozo", "I had a serving of Orzo", "I had a serving of also"))
    }

    @Test
    fun differencesAtBothEndsAreSettledSeparately() {
        assertEquals(
            "Log eight ounces of juice today",
            settle("Vlog eight ounces of juice today", "Log eight ounces of juice to day", "log eight ounces of juice today"),
        )
    }
}
