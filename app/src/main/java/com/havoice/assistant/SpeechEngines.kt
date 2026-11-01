package com.havoice.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * The on-phone speech models, loaded once and shared:
 * - [vad] finds where speech starts and stops,
 * - [recognizer] turns speech into text — re-run a few times a second for the live
 *   transcript, and once at the end for the text sent to Home Assistant,
 * - [speaker] turns a phrase into a voiceprint for voice ID.
 * None of them is thread-safe; one listening session uses them at a time.
 */
class SpeechEngines private constructor(
    val vad: Vad,
    val recognizer: OfflineRecognizer,
    val speaker: SpeakerEmbeddingExtractor,
) {
    companion object {
        @Volatile
        private var loaded: SpeechEngines? = null

        @Synchronized
        fun get(context: Context): SpeechEngines? {
            loaded?.let { return it }
            val assets = context.applicationContext.assets
            return try {
                val started = System.nanoTime()
                val vad = Vad(
                    assetManager = assets,
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = "vad/silero_vad.onnx",
                            threshold = 0.5f,
                            minSilenceDuration = 0.3f,
                            minSpeechDuration = 0.25f,
                            windowSize = VadFeeder.WINDOW,
                            // Commands are short; one phrase per segment keeps voice ID simple.
                            maxSpeechDuration = 20f,
                        ),
                        sampleRate = MicRecorder.SAMPLE_RATE,
                    ),
                )
                // NVIDIA Parakeet 110M (English, int8) with its TDT decoder: reads a whole command
                // in ~50 ms on the phone, fast enough to also drive the live transcript. The same
                // model's CTC decoder was as fast but spelled out sounds ("East Oakland" →
                // "Eastoak Lind"); TDT weighs each word against the ones before it.
                val recognizer = OfflineRecognizer(
                    assetManager = assets,
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = MicRecorder.SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = "final/encoder.int8.onnx",
                                decoder = "final/decoder.int8.onnx",
                                joiner = "final/joiner.int8.onnx",
                            ),
                            modelType = "nemo_transducer",
                            tokens = "final/tokens.txt",
                            numThreads = 2,
                            // Hotwords are plain names, split into the model's pieces with this
                            // list (tokens.txt, each piece scored the same so fewer pieces win).
                            // Without both set, sherpa-onnx exits the app on the first hotword.
                            modelingUnit = "bpe",
                            bpeVocab = "final/bpe.vocab",
                        ),
                        // Beam search lets each request lean toward the user's names ("hotwords",
                        // see NameSteering) for ~10% more time. At 1.0 a sound-alike name wins
                        // only when the audio is unclear ("Marlin" → Marlon); at 2.5 it made names
                        // up in noise and in short clips ("Edwards." from half a second of "Hey").
                        decodingMethod = "modified_beam_search",
                        maxActivePaths = 4,
                        hotwordsScore = 1.0f,
                    ),
                )
                val speaker = SpeakerEmbeddingExtractor(
                    assetManager = assets,
                    // NVIDIA TitaNet small: on this phone's real recordings it separated the owner
                    // (0.70–0.87) from a video (0.12–0.16); WeSpeaker CAM++ could not.
                    config = SpeakerEmbeddingExtractorConfig(model = "speaker/titanet_small_en.onnx", numThreads = 2),
                )
                AppLog.i("Speech engines loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
                SpeechEngines(vad, recognizer, speaker).also { loaded = it }
            } catch (e: Exception) {
                AppLog.e("Couldn't load speech engines", e)
                null
            }
        }
    }
}
