package com.havoice.assistant

import com.k2fsa.sherpa.onnx.Vad

/**
 * Feeds audio to the speech detector in the exact window size it was configured with,
 * carrying leftovers over to the next call. Silero processes one window per call, so
 * handing it larger chunks silently skips audio.
 */
class VadFeeder(private val vad: Vad, private val window: Int = WINDOW) {
    private var pending = FloatArray(0)

    fun accept(samples: FloatArray) {
        val buffer = if (pending.isEmpty()) samples else pending + samples
        var offset = 0
        while (buffer.size - offset >= window) {
            vad.acceptWaveform(buffer.copyOfRange(offset, offset + window))
            offset += window
        }
        pending = buffer.copyOfRange(offset, buffer.size)
    }

    companion object {
        /** Must match SileroVadModelConfig.windowSize in [SpeechEngines]. */
        const val WINDOW = 512
    }
}
