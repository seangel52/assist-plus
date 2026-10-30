package com.havoice.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserVoxLockModelConfig
import java.io.File

/**
 * VoxLock: pulls the owner's voice out of audio where someone else talks at the same time,
 * using the voice sample saved during voice setup. Too slow to run live, so it is used
 * only to rescue phrases that voice ID found to be a mix.
 */
object VoiceIsolator {
    private val MODELS = listOf("enroll.onnx", "chunk_int8.onnx")

    private var denoiser: OfflineSpeechDenoiser? = null
    private var loadedSampleStamp = 0L

    /** Ready isolator for the current voice sample, or null if there is no sample or it failed. */
    /** VoxLock is an optional download (80 MB, rarely needed); releases ship without it. */
    private fun bundled(context: Context) = context.assets.list("voxlock")?.contains("chunk_int8.onnx") == true

    @Synchronized
    fun get(context: Context): OfflineSpeechDenoiser? {
        val sample = VoiceProfile.sampleFile(context)
        if (!sample.exists() || !bundled(context)) return null
        denoiser?.let { if (loadedSampleStamp == sample.lastModified()) return it }
        denoiser?.release()
        denoiser = null
        return try {
            val started = System.nanoTime()
            // VoxLock reads the voice sample from a file, so its models load from files too.
            val dir = File(context.filesDir, "voxlock").apply { mkdirs() }
            for (name in MODELS) {
                val target = File(dir, name)
                if (target.exists()) continue
                val partial = File(dir, "$name.part")
                context.assets.open("voxlock/$name").use { input -> partial.outputStream().use { input.copyTo(it) } }
                partial.renameTo(target)
            }
            val created = OfflineSpeechDenoiser(
                config = OfflineSpeechDenoiserConfig(
                    model = OfflineSpeechDenoiserModelConfig(
                        voxlock = OfflineSpeechDenoiserVoxLockModelConfig(
                            enrollModel = File(dir, "enroll.onnx").absolutePath,
                            chunkModel = File(dir, "chunk_int8.onnx").absolutePath,
                            voiceSample = sample.absolutePath,
                        ),
                        numThreads = 4,
                    ),
                ),
            )
            AppLog.i("Voice isolation loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
            loadedSampleStamp = sample.lastModified()
            created.also { denoiser = it }
        } catch (e: Exception) {
            AppLog.e("Couldn't load voice isolation", e)
            null
        }
    }

    /** The owner's voice from [samples] (16 kHz), or null when isolation isn't available. */
    @Synchronized
    fun isolate(context: Context, samples: FloatArray): FloatArray? =
        get(context)?.run(samples, MicRecorder.SAMPLE_RATE)?.samples
}
