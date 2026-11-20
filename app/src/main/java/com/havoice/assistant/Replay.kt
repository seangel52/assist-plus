package com.havoice.assistant

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Development only: runs the saved recordings (Settings → Advanced → save recordings) through
 * the listener again, oldest first, exactly as the mic would have delivered them, and logs what
 * each comes out as now. Listening can't be tested by hand from a computer; this checks a change
 * against what was really said.
 *
 * Started with
 * `adb shell am start -n com.havoice.assistant/.SettingsActivity --ez debug_replay true`
 * (add `--ez debug_replay_learn true` to also learn the everyday voice from them, as it would
 * have been learned had they been heard live). Not in release builds.
 */
object Replay {
    const val EXTRA = "debug_replay"
    const val EXTRA_LEARN = "debug_replay_learn"

    const val EXTRA_MODEL = "debug_model"

    private const val WAV_HEADER = 44

    /**
     * Development only: reads every saved recording whole with the app's speech model and with
     * another one whose files were copied to files/models/[name] (encoder.int8.onnx,
     * decoder.int8.onnx, joiner.int8.onnx, tokens.txt), logging the words and how long each took
     * on this phone. For deciding whether a larger model is worth offering.
     */
    fun timeModel(context: Context, name: String) {
        val dir = File(context.filesDir, "models/$name")
        val engines = SpeechEngines.get(context) ?: return
        val before = android.os.Debug.getNativeHeapAllocatedSize()
        val loadStarted = System.nanoTime()
        val other = com.k2fsa.sherpa.onnx.OfflineRecognizer(
            config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = MicRecorder.SAMPLE_RATE, featureDim = 80),
                modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").path,
                        decoder = File(dir, "decoder.int8.onnx").path,
                        joiner = File(dir, "joiner.int8.onnx").path,
                    ),
                    modelType = "nemo_transducer",
                    tokens = File(dir, "tokens.txt").path,
                    numThreads = 4,
                ),
            ),
        )
        AppLog.i(
            "Model test $name: loaded in ${(System.nanoTime() - loadStarted) / 1_000_000} ms, " +
                "${(android.os.Debug.getNativeHeapAllocatedSize() - before) / 1_000_000} MB of memory",
        )
        val recordings = Captures.dir(context).listFiles { f -> f.name.endsWith("_assist_raw.wav") }?.sortedBy { it.name }.orEmpty()
        var seconds = 0.0
        var appMillis = 0L
        var otherMillis = 0L
        fun read(recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer, samples: FloatArray): Pair<String, Long> {
            val started = System.nanoTime()
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, MicRecorder.SAMPLE_RATE)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim() to (System.nanoTime() - started) / 1_000_000
            } finally {
                stream.release()
            }
        }
        for (raw in recordings) {
            val samples = VoiceProfile.toFloats(raw.readBytes().drop(WAV_HEADER).toByteArray())
            seconds += samples.size / MicRecorder.SAMPLE_RATE.toDouble()
            val (appText, appTook) = read(engines.recognizer, samples)
            val (otherText, otherTook) = read(other, samples)
            appMillis += appTook
            otherMillis += otherTook
            AppLog.i("Model test ${raw.name.take(15)} %.1f s: app %d ms \"%s\" | $name %d ms \"%s\"".format(samples.size / 16000f, appTook, appText, otherTook, otherText))
        }
        AppLog.i("Model test done: %.0f s of audio; app model %d ms, $name %d ms".format(seconds, appMillis, otherMillis))
        other.release()
    }

    suspend fun run(context: Context, learn: Boolean) {
        val settings = HaSettings(context)
        val folder = Captures.dir(context)
        val recordings = folder.listFiles { f -> f.name.endsWith("_assist_clean.wav") }?.sortedBy { it.name }.orEmpty()
        AppLog.i("Replay: ${recordings.size} saved recordings" + if (learn) ", learning the voice from them" else "")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        var before: List<VoiceMemory.Phrase>? = null
        var beforeAt = 0L
        for (clean in recordings) {
            val name = clean.name.removeSuffix("_assist_clean.wav")
            val raw = File(folder, "${name}_assist_raw.wav").takeIf { it.exists() } ?: continue
            val cleanPcm = clean.readBytes().drop(WAV_HEADER).toByteArray()
            val rawPcm = raw.readBytes().drop(WAV_HEADER).toByteArray()
            // Loaded for each recording, so what was learned from the ones before it counts.
            val profile = if (!settings.voiceIdEnabled) null
            else VoiceProfile.load(context)?.let { if (settings.learnVoice) VoiceProfile.withEveryday(context, it) else it }
            val meter = SignalMeter()
            val listener = PhoneListener(
                context,
                profile,
                settings.voiceIdThreshold,
                endPauseSeconds = settings.endPauseSeconds,
                hotwords = NameSteering.hotwords(context, KnownNames.steering(KnownNames.all(context, settings), KnownNames.today())),
                meter = meter,
            )
            val frames = flow {
                var at = 0
                while (at < cleanPcm.size) {
                    val end = minOf(cleanPcm.size, at + MicRecorder.CHUNK_BYTES)
                    // The raw audio runs a little ahead of the cleaned, as it does live.
                    meter.add(rawPcm.copyOfRange(minOf(at, rawPcm.size), minOf(rawPcm.size, end)))
                    emit(cleanPcm.copyOfRange(at, end))
                    at = end
                }
                if (rawPcm.size > cleanPcm.size) meter.add(rawPcm.copyOfRange(cleanPcm.size, rawPcm.size))
            }
            val result = listener.listen(frames, CompletableDeferred()) { }
            AppLog.i("Replay $name: ${result.command?.let { "\"${it.text}\"" } ?: "nothing accepted"}")
            if (!learn || !settings.learnVoice || profile == null) continue
            val at = try {
                stamp.parse(name)?.time ?: 0L
            } catch (e: java.text.ParseException) {
                0L
            }
            val phrases = result.segments.map { VoiceMemory.Phrase(it.preview, it.accepted, it.voice) }
            val lessons = VoiceMemory.lessons(phrases, before, at - beforeAt)
            before = phrases
            beforeAt = at
            if (lessons.isNotEmpty()) {
                VoiceMemory.add(context, profile.noise, lessons.map { it.embedding })
                AppLog.i("Replay $name: voice ID learned ${lessons.size} phrase(s): ${lessons.joinToString("; ") { it.why }}")
            }
        }
        AppLog.i("Replay done (${VoiceMemory.count(context)} phrases of everyday voice kept)")
    }
}
