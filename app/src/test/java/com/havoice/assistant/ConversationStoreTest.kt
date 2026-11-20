package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {
    private val saved = SavedConversation(
        conversationId = "01ABC",
        pipelineId = "pipe",
        at = 1_000_000L,
        messages = listOf(
            AssistMessage("Log eight ounces of juice.", isInput = true),
            AssistMessage("Logged 8 ounces of juice, about 110 calories.", isInput = false),
            AssistMessage("Couldn't reach Home Assistant", isInput = false, isError = true),
        ),
        waiting = true,
    )

    @Test
    fun aConversationComesBackAsItWasSaved() {
        assertEquals(saved, ConversationStore.decode(ConversationStore.encode(saved)))
    }

    @Test
    fun aMissingConversationIdStaysMissing() {
        val fresh = saved.copy(conversationId = null, pipelineId = null)
        assertEquals(fresh, ConversationStore.decode(ConversationStore.encode(fresh)))
    }

    @Test
    fun aDamagedFileIsNoConversation() {
        assertNull(ConversationStore.decode("{not json"))
    }

    @Test
    fun onlyTheLatestTurnsAreKept() {
        val long = saved.copy(messages = List(50) { AssistMessage("turn $it", isInput = it % 2 == 0) })
        val back = ConversationStore.decode(ConversationStore.encode(long))!!
        assertEquals(SavedConversation.MAX_MESSAGES, back.messages.size)
        assertEquals("turn 49", back.messages.last().message)
    }

    @Test
    fun aConversationIsShownAgainOnlyWhileRecent() {
        assertTrue(saved.worthShowing(saved.at + 10 * 60_000L))
        assertFalse(saved.worthShowing(saved.at + SavedConversation.SHOW_FOR_MILLIS + 1))
        assertFalse(saved.copy(messages = emptyList()).worthShowing(saved.at))
    }
}
