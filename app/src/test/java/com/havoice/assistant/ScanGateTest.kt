package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanGateTest {
    private fun ScanGate.feed(vararg frames: List<String>) = frames.map { offer(it) }

    @Test
    fun countsOnceAfterTwoMatchingFrames() {
        val gate = ScanGate()
        assertEquals(listOf(null, "123"), gate.feed(listOf("123"), listOf("123")))
    }

    @Test
    fun aMisreadInOneFrameDoesntCount() {
        val gate = ScanGate()
        assertEquals(listOf(null, null, null), gate.feed(listOf("123"), listOf("128"), emptyList()))
    }

    @Test
    fun holdingTheItemThereAddsItOnce() {
        val gate = ScanGate()
        val results = gate.feed(*Array(30) { listOf("123") })
        assertEquals(1, results.count { it == "123" })
    }

    @Test
    fun theSameItemCountsAgainOnlyAfterLeavingTheView() {
        val gate = ScanGate(goneFrames = 3)
        gate.feed(listOf("123"), listOf("123"))
        // Out of view for two frames only: back in, still the same scan.
        assertEquals(listOf(null, null, null, null), gate.feed(emptyList(), emptyList(), listOf("123"), listOf("123")))
        // Out of view long enough: a second scan of the same item.
        gate.feed(emptyList(), emptyList(), emptyList())
        assertEquals(listOf(null, "123"), gate.feed(listOf("123"), listOf("123")))
    }

    @Test
    fun aDifferentItemCountsRightAway() {
        val gate = ScanGate()
        gate.feed(listOf("123"), listOf("123"))
        assertEquals(listOf(null, "456"), gate.feed(listOf("456"), listOf("456")))
    }

    @Test
    fun forgetLetsTheSameItemCountAgain() {
        val gate = ScanGate()
        gate.feed(listOf("123"), listOf("123"))
        gate.forget()
        assertNull(gate.offer(listOf("123")))
        assertEquals("123", gate.offer(listOf("123")))
    }
}
