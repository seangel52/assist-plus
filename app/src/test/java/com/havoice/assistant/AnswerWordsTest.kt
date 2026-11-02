package com.havoice.assistant

import com.havoice.assistant.ConfirmAnswer.Kind.NO
import com.havoice.assistant.ConfirmAnswer.Kind.OTHER
import com.havoice.assistant.ConfirmAnswer.Kind.YES
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerWordsTest {
    private val wake = listOf(WakePhrase("hey samsung"))

    @Test
    fun stopWordsCloseOnlyWhenThatsAllThereIs() {
        assertEquals(ExitWords.Kind.CANCEL, ExitWords.classify("Cancel.", wake))
        assertEquals(ExitWords.Kind.CANCEL, ExitWords.classify("Never mind, I don't know.", wake))
        assertEquals(ExitWords.Kind.CANCEL, ExitWords.classify("Hey Samsung, stop.", wake))
        assertEquals(ExitWords.Kind.GOODBYE, ExitWords.classify("No thank you, that's all.", wake))
        assertEquals(ExitWords.Kind.NONE, ExitWords.classify("Cancel my plans for tonight.", wake))
        assertEquals(ExitWords.Kind.NONE, ExitWords.classify("Stop the music.", wake))
        // A misheard "cancel" is judged from the audio (keyword spotter), not guessed from the text.
        assertEquals(ExitWords.Kind.NONE, ExitWords.classify("Hansel.", wake))
    }

    @Test
    fun confirmationAnswers() {
        assertEquals(YES, ConfirmAnswer.classify("Yes."))
        assertEquals(YES, ConfirmAnswer.classify("Yeah, that's it."))
        assertEquals(YES, ConfirmAnswer.classify("That's the one, thanks."))
        assertEquals(YES, ConfirmAnswer.classify("Um, sounds good."))
        assertEquals(NO, ConfirmAnswer.classify("No."))
        assertEquals(NO, ConfirmAnswer.classify("That's not it."))
        assertEquals(NO, ConfirmAnswer.classify("Nope, no thank you."))
        assertEquals(OTHER, ConfirmAnswer.classify("No, it's 3140 East Oakland Street."))
        assertEquals(OTHER, ConfirmAnswer.classify("Springfield."))
        assertEquals(OTHER, ConfirmAnswer.classify("Yes, but in Springfield."))
    }
}
