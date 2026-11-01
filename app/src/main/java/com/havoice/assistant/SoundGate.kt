package com.havoice.assistant

import kotlin.math.sqrt

/**
 * Picks the stretches of audio worth running the wake word model on: anything clearly louder
 * than the room's background noise, plus a little before (so the start of the phrase isn't
 * lost) and after (so the end is heard). In a quiet room the model barely runs.
 */
class SoundGate(private val preRollChunks: Int = 2, private val hangoverChunks: Int = 8) {
    private var floor = -1f
    private var hang = 0
    private val held = ArrayDeque<FloatArray>()
    private var active = 0L
    private var total = 0L

    /** Share of chunks passed to the model so far. */
    val share get() = if (total == 0L) 0f else active.toFloat() / total

    /** The chunks to run the model on now: nothing while quiet, held-back audio first when sound starts. */
    fun offer(samples: FloatArray): List<FloatArray> {
        total++
        val rms = sqrt(samples.fold(0.0) { sum, s -> sum + s * s } / samples.size.coerceAtLeast(1)).toFloat()
        if (floor < 0) floor = rms
        val loud = rms > maxOf(floor * LOUDER, MIN_LEVEL)
        // The floor follows the room: it drops right away and rises slowly, so speech doesn't
        // raise it but a fan or TV left on becomes the new normal within a minute or so.
        floor = if (rms < floor) rms else floor + (rms - floor) * FLOOR_RISE
        if (loud) hang = hangoverChunks
        if (hang == 0) {
            held.addLast(samples)
            while (held.size > preRollChunks) held.removeFirst()
            return emptyList()
        }
        if (!loud) hang--
        active++
        val out = held.toList() + listOf(samples)
        held.clear()
        return out
    }

    private companion object {
        // About 8 dB above the background.
        const val LOUDER = 2.5f
        // About -50 dBFS: below this it's silence, however quiet the room.
        const val MIN_LEVEL = 0.003f
        const val FLOOR_RISE = 0.02f
    }
}
