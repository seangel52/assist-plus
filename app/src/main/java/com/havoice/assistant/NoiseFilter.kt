package com.havoice.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class NoiseReduction(val label: String, val description: String) {
    OFF("Off", "Raw microphone audio."),
    PHONE("Phone", "Your phone's built-in call noise suppression. No extra processing."),
    LIGHT("Light", "Small on-device AI model (GTCRN). Lowest delay."),
    STRONG("Strong", "Larger on-device AI model (DeepFilterNet). Removes the most noise."),
}

/**
 * Removes background noise from 16 kHz PCM as it streams, using an on-device model.
 * Not thread-safe: use one stream at a time and [reset] between streams.
 */
class NoiseFilter private constructor(private val denoiser: OnlineSpeechDenoiser) {
    /** How much audio the model needs before it can output a step — its added delay. */
    val stepMillis: Int get() = denoiser.frameShiftInSamples * 1000 / denoiser.sampleRate

    fun process(pcm: ByteArray): ByteArray = toPcm(denoiser.run(toFloats(pcm), MicRecorder.SAMPLE_RATE).samples)

    fun flush(): ByteArray = toPcm(denoiser.flush().samples)

    fun reset() = denoiser.reset()

    companion object {
        private val loaded = mutableMapOf<NoiseReduction, NoiseFilter>()

        /** The filter for [mode], loading its model on first use; null when [mode] isn't model-based. */
        @Synchronized
        fun get(context: Context, mode: NoiseReduction): NoiseFilter? {
            val model = when (mode) {
                NoiseReduction.LIGHT -> OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = "denoise/gtcrn_simple.onnx"),
                )
                NoiseReduction.STRONG -> OfflineSpeechDenoiserModelConfig(
                    dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model = "denoise/dpdfnet2.onnx"),
                )
                else -> return null
            }
            loaded[mode]?.let { return it }
            return try {
                val started = System.nanoTime()
                val denoiser = OnlineSpeechDenoiser(
                    assetManager = context.applicationContext.assets,
                    config = OnlineSpeechDenoiserConfig(model = model.apply { numThreads = 1 }),
                )
                AppLog.i(
                    "Noise model $mode loaded in ${(System.nanoTime() - started) / 1_000_000} ms " +
                        "(${denoiser.sampleRate} Hz, step ${denoiser.frameShiftInSamples} samples)",
                )
                NoiseFilter(denoiser).also { loaded[mode] = it }
            } catch (e: Exception) {
                // Fall back to the unfiltered mic rather than failing to listen at all.
                AppLog.e("Couldn't load noise model $mode", e)
                null
            }
        }

        private fun toFloats(pcm: ByteArray): FloatArray {
            val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        }

        private fun toPcm(samples: FloatArray): ByteArray {
            val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) out.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            return out.array()
        }
    }
}
