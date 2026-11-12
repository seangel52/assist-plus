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

    private val turns = listOf(
        HeardReview.Turn(true, "Also log twenty more face poles."),
        HeardReview.Turn(false, "Sorry, what is that?"),
        HeardReview.Turn(true, "pulls"),
    )

    @Test
    fun reviewFindsPairsFromAnyWrappingOfTheJson() {
        val reply = "Here you go:\n```json\n[{\"heard\": \"face poles\", \"meant\": \"face pulls\", \"kind\": \"name\"}]\n```"
        assertEquals(listOf(Correction("face poles", "face pulls")), HeardReview.parse(reply, turns))
    }

    @Test
    fun reviewKeepsTheKindAndTreatsUnlabelledAsSlips() {
        val said = listOf(HeardReview.Turn(true, "Paint it to Targit please"))
        val reply = "[{\"heard\": \"Paint it\", \"meant\": \"Change it\", \"kind\": \"slip\"}, " +
            "{\"heard\": \"Targit\", \"meant\": \"Target\", \"kind\": \"name\"}, {\"heard\": \"please\", \"meant\": \"police\"}]"
        assertEquals(
            listOf(
                Correction("Paint it", "Change it", NameCorrections.Kind.SLIP),
                Correction("Targit", "Target", NameCorrections.Kind.NAME),
                Correction("please", "police", NameCorrections.Kind.SLIP),
            ),
            HeardReview.parse(reply, said),
        )
    }

    @Test
    fun reviewDropsWordsTheUserNeverSaid() {
        val reply = "[{\"heard\": \"flat bench\", \"meant\": \"flat press\"}, {\"heard\": \"pulls\", \"meant\": \"pulls\"}]"
        assertEquals(emptyList<Correction>(), HeardReview.parse(reply, turns))
    }

    @Test
    fun addedDetailIsNotAMishearing() {
        val said = listOf(HeardReview.Turn(true, "I had rice cauliflower"), HeardReview.Turn(true, "it was riced"))
        val reply = "[{\"heard\": \"rice\", \"meant\": \"riced\"}, {\"heard\": \"cauliflower\", \"meant\": \"riced cauliflower\"}, " +
            "{\"heard\": \"rice cauliflower\", \"meant\": \"riced cauliflower\", \"kind\": \"name\"}]"
        assertEquals(listOf(Correction("rice cauliflower", "riced cauliflower")), HeardReview.parse(reply, said))
    }

    @Test
    fun theAssistantsOwnWordsAreNotEvidence() {
        // Seen 2026-11-11: the user insisted on "built puff bar"; the assistant called it a vape;
        // the review offered "built puff bar" -> "Buff bar". The user never said "Buff bar".
        val talk = listOf(
            HeardReview.Turn(true, "I had a half of a built puff bar, the brownie flavoured"),
            HeardReview.Turn(false, "I didn't log the puff bar since it isn't food."),
            HeardReview.Turn(true, "A built puff bar is definitely food."),
        )
        val f = HeardReview.findings("[{\"heard\": \"built puff bar\", \"meant\": \"Buff bar\", \"kind\": \"name\"}]", talk).single()
        assertEquals(false, f.userSaidMeant)
        assertEquals(2, f.said.size)
    }

    @Test
    fun aCorrectionTheUserSaidIsEvidence() {
        val talk = listOf(
            HeardReview.Turn(true, "I took my Clarity"),
            HeardReview.Turn(false, "What's Clarity?"),
            HeardReview.Turn(true, "Claritin, the allergy pill"),
        )
        val f = HeardReview.findings("[{\"heard\": \"Clarity\", \"meant\": \"Claritin\", \"kind\": \"name\"}]", talk).single()
        assertEquals(true, f.userSaidMeant)
        assertEquals(listOf("I took my Clarity"), f.said)
    }

    @Test
    fun oneSentenceIsOneSighting() {
        // "Paul slaw" was "learned as repeated" 30 s after it was noted: the review had read the same line twice.
        val talk = listOf(HeardReview.Turn(true, "a tiny bit of Paul slaw"), HeardReview.Turn(false, "Logged coleslaw."))
        val f = HeardReview.findings("[{\"heard\": \"Paul slaw\", \"meant\": \"coleslaw\", \"kind\": \"slip\"}]", talk).single()
        assertEquals(false, f.userSaidMeant)
        assertEquals(1, f.said.size)
    }

    @Test
    fun reviewSurvivesAnAnswerThatIsNotJson() {
        assertEquals(emptyList<Correction>(), HeardReview.parse("None that I can see.", turns))
        assertEquals(emptyList<Correction>(), HeardReview.parse("[oops]", turns))
    }

    @Test
    fun onlyMatchingWordsAreRecalled() {
        val known = listOf(Correction("mere", "Mira"), Correction("face poles", "face pulls"))
        val note = NameCorrections.recallNote(known, "I had a Mere frozen sandwich")
        assertEquals(true, note.contains("\"mere\" has turned out to mean \"Mira\""))
        assertEquals(false, note.contains("face"))
        // Whole words only: "mere" isn't in "camera".
        assertEquals("", NameCorrections.recallNote(known, "turn on the camera"))
    }
}
