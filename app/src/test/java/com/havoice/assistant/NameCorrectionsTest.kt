package com.havoice.assistant

import com.havoice.assistant.NameCorrections.Correction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameCorrectionsTest {
    @Test
    fun tagIsTakenOutOfTheReply() {
        val (clean, learned) = NameCorrections.strip("Saved Marlon's costume idea. [heard: Marlin -> Marlon]")
        assertEquals("Saved Marlon's costume idea.", clean)
        assertEquals(listOf(Correction("Marlin", "Marlon")), learned)
    }

    @Test
    fun arrowAndSpacingVariants() {
        val (clean, learned) = NameCorrections.strip("Paused it [ Heard: Living Romu → Living Room Roku ] for you.")
        assertEquals("Paused it for you.", clean)
        assertEquals(listOf(Correction("Living Romu", "Living Room Roku")), learned)
    }

    @Test
    fun replyWithoutTagIsUnchanged() {
        val reply = "Turned off the lights -> all of them."
        assertEquals(reply to emptyList<Correction>(), NameCorrections.strip(reply))
    }

    @Test
    fun sameNameIsNotACorrection() {
        val (clean, learned) = NameCorrections.strip("Done. [heard: Riley -> riley]")
        assertEquals("Done.", clean)
        assertEquals(emptyList<Correction>(), learned)
    }

    @Test
    fun namesAreSplitIntoTheModelsPieces() {
        val vocab = setOf("▁R", "il", "ey", "▁Mar", "lon", "▁M", "a", "r", "l", "o", "n")
        assertEquals("▁R il ey", NameSteering.encode(vocab, "Riley"))
        assertEquals("▁R il ey ▁Mar lon", NameSteering.encode(vocab, "Riley Marlon"))
        assertNull(NameSteering.encode(vocab, "Zoë"))
    }
}
