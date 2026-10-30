package com.havoice.assistant

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved recordings for tuning voice ID: raw mic audio, the cleaned audio the models saw,
 * and a notes file with scores. Kept only on the phone, newest [MAX_SETS] sets.
 */
object Captures {
    private const val MAX_SETS = 60

    fun dir(context: Context) = File(context.filesDir, "captures")

    fun count(context: Context): Int = dir(context).listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** Collects raw and cleaned audio while recording, then [save]s them as one set. */
    class Recorder {
        val raw = ByteArrayOutputStream()
        val clean = ByteArrayOutputStream()
    }

    fun save(context: Context, label: String, recorder: Recorder, notes: String) {
        val folder = dir(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = "${stamp}_$label"
        writeWav(File(folder, "${base}_raw.wav"), recorder.raw.toByteArray())
        writeWav(File(folder, "${base}_clean.wav"), recorder.clean.toByteArray())
        File(folder, "$base.txt").writeText(notes + "\n")
        prune(folder)
    }

    private fun prune(folder: File) {
        val sets = folder.listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.name } ?: return
        sets.dropLast(MAX_SETS).forEach { notes ->
            val base = notes.name.removeSuffix(".txt")
            folder.listFiles { f -> f.name.startsWith(base) }?.forEach { it.delete() }
        }
    }

    private fun writeWav(target: File, pcm: ByteArray) {
        val rate = MicRecorder.SAMPLE_RATE
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2)
            .putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        target.outputStream().use {
            it.write(header.array())
            it.write(pcm)
        }
    }
}
