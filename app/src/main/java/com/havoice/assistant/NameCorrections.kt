package com.havoice.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names the speech recognizer leans toward, taught by the agent. When the agent works out (or
 * is told) that a name was misheard, it ends its reply with a tag like "[heard: Marlin -> Marlon]";
 * the app takes the tag out before showing or speaking the reply, adds "Marlon" to the names to
 * listen for, and keeps the pair so the user can see and undo what was learned.
 */
object NameCorrections {
    data class Correction(val heard: String, val meant: String)

    private val TAG = Regex("\\s*\\[\\s*heard\\s*:\\s*([^\\]]+?)\\s*(?:->|→)\\s*([^\\]]+?)\\s*]", RegexOption.IGNORE_CASE)

    /** The reply without correction tags, and the corrections they carried. */
    fun strip(reply: String): Pair<String, List<Correction>> {
        if (!TAG.containsMatchIn(reply)) return reply to emptyList()
        val found = TAG.findAll(reply).map { Correction(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            .filter { it.meant.isNotBlank() && !it.meant.equals(it.heard, ignoreCase = true) }.toList()
        return TAG.replace(reply, " ").replace(Regex(" {2,}"), " ").replace(Regex(" ([.,!?])"), "$1").trim() to found
    }

    fun load(settings: HaSettings): List<Correction> = try {
        val array = JSONArray(settings.nameCorrectionsJson)
        (0 until array.length()).map { array.getJSONObject(it) }.map { Correction(it.getString("heard"), it.getString("meant")) }
    } catch (e: Exception) {
        emptyList()
    }

    /** Remembers [corrections] and makes sure each meant name is listened for. */
    fun record(settings: HaSettings, corrections: List<Correction>) {
        if (corrections.isEmpty()) return
        val known = load(settings).toMutableList()
        val names = names(settings).toMutableList()
        for (c in corrections) {
            if (known.none { it.heard.equals(c.heard, true) && it.meant.equals(c.meant, true) }) known += c
            if (names.none { it.equals(c.meant, true) }) names += c.meant
            AppLog.i("Learned a name: heard \"${c.heard}\", meant \"${c.meant}\"")
        }
        save(settings, known.takeLast(MAX_CORRECTIONS))
        settings.listenNames = names.joinToString("\n")
    }

    fun remove(settings: HaSettings, correction: Correction) = save(settings, load(settings) - correction)

    /** The names to listen for, one per line in settings. */
    fun names(settings: HaSettings): List<String> =
        settings.listenNames.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun save(settings: HaSettings, list: List<Correction>) {
        settings.nameCorrectionsJson = JSONArray(list.map { JSONObject().put("heard", it.heard).put("meant", it.meant) }).toString()
    }

    private const val MAX_CORRECTIONS = 200
}

/**
 * The names passed to the speech model's "hotwords" steering. sherpa-onnx splits them into the
 * model's word pieces itself (see SpeechEngines); names it couldn't spell ("Zoë") are left out
 * here, along with characters its hotword format reads as settings (":", "#", "@").
 */
object NameSteering {
    @Volatile
    private var pieces: Set<String>? = null

    private fun pieces(context: Context): Set<String> = pieces ?: synchronized(this) {
        pieces ?: context.assets.open("final/tokens.txt").bufferedReader().useLines { lines ->
            lines.map { it.substringBefore(' ') }.filter { it.isNotEmpty() }.toSet()
        }.also { pieces = it }
    }

    /** Hotwords for [names], one per line. */
    fun hotwords(context: Context, names: List<String>): String {
        if (names.isEmpty()) return ""
        val vocab = pieces(context)
        return names.filter { PLAIN.matches(it) && encode(vocab, it) != null }.joinToString("\n")
    }

    private val PLAIN = Regex("[A-Za-z][A-Za-z' -]*")

    /** Longest matching piece first, each word starting with "▁" as the model writes them. */
    fun encode(vocab: Set<String>, name: String): String? {
        val out = mutableListOf<String>()
        for (word in name.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val text = "▁$word"
            var i = 0
            while (i < text.length) {
                val end = (text.length downTo i + 1).firstOrNull { text.substring(i, it) in vocab } ?: return null
                out += text.substring(i, end)
                i = end
            }
        }
        return out.joinToString(" ").ifEmpty { null }
    }
}
