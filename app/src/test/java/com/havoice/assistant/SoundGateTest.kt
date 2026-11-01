package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundGateTest {
    private fun chunk(level: Float) = FloatArray(3200) { if (it % 2 == 0) level else -level }

    @Test
    fun quietRoomRunsNothing() {
        val gate = SoundGate()
        repeat(100) { assertTrue(gate.offer(chunk(0.002f)).isEmpty()) }
        assertEquals(0f, gate.share)
    }

    @Test
    fun speechIsPassedWithTheMomentBeforeAndAfter() {
        val gate = SoundGate()
        repeat(20) { gate.offer(chunk(0.002f)) }
        val start = gate.offer(chunk(0.1f))
        assertEquals(3, start.size) // two held-back quiet chunks, then the speech
        repeat(8) { assertEquals(1, gate.offer(chunk(0.002f)).size) } // the tail keeps going
        assertTrue(gate.offer(chunk(0.002f)).isEmpty())
    }

    @Test
    fun steadyNoiseBecomesTheNewNormal() {
        val gate = SoundGate()
        repeat(20) { gate.offer(chunk(0.002f)) }
        repeat(400) { gate.offer(chunk(0.03f)) } // a fan or TV left on for ~80 s
        assertTrue(gate.offer(chunk(0.03f)).isEmpty())
        assertTrue(gate.offer(chunk(0.2f)).isNotEmpty()) // a voice above it still gets through
    }
}
