package com.havoice.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the speech recognizer got wrong before, and the names it should lean toward. Pairs come
 * from [HeardReview] after each exchange, or from a tag like "[heard: Marlin -> Marlon]" in an
 * agent's reply (taken out before the reply is shown or spoken).
 *
 * Two kinds, because they call for different trust:
 * - A **name** (a person, brand, product, drug, place, title the recognizer doesn't know) is
 *   learned at once: the meant name joins the names to listen for, and the pair goes along with
 *   later requests that have the same wrong words ([recallNote]).
 * - A **slip** (ordinary words misheard: "paint it" for "change it") is usually a one-off. It is
 *   only remembered once the same slip happens again, and even then it is only recalled, never
 *   used to steer the recognizer, which would bend everyday speech toward it.
 *
 * Learned pairs are kept so the user can see and undo them.
 */
object NameCorrections {
    enum class Kind { NAME, SLIP }

    data class Correction(val heard: String, val meant: String, val kind: Kind = Kind.NAME)

    /** A slip has to be seen this many times before it's remembered. */
    const val SLIP_REPEATS = 2

    private val TAG = Regex("\\s*\\[\\s*heard\\s*:\\s*([^\\]]+?)\\s*(?:->|→)\\s*([^\\]]+?)\\s*]", RegexOption.IGNORE_CASE)

    /** The reply without correction tags, and the corrections they carried. */
    fun strip(reply: String): Pair<String, List<Correction>> {
        if (!TAG.containsMatchIn(reply)) return reply to emptyList()
        val found = TAG.findAll(reply).map { Correction(it.groupValues[1].trim(), it.groupValues[2].trim()) }
            .filter { it.meant.isNotBlank() && !it.meant.equals(it.heard, ignoreCase = true) }.toList()
        return TAG.replace(reply, " ").replace(Regex(" {2,}"), " ").replace(Regex(" ([.,!?])"), "$1").trim() to found
    }

    fun load(settings: HaSettings): List<Correction> = parseList(settings.nameCorrectionsJson).map { it.first }

    /**
     * A possible mishearing and the evidence for it: [said] are the user's lines that had the
     * wrong words (each a separate time they were said), and [userSaidMeant] tells whether the
     * user themselves said the right words in the conversation (a correction or a spelling).
     */
    data class Finding(val correction: Correction, val said: List<String>, val userSaidMeant: Boolean)

    private class Pending(val correction: Correction, val seen: MutableSet<String>)

    private fun parseList(json: String): List<Pair<Correction, Set<String>>> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { array.getJSONObject(it) }.map {
            val kind = if (it.optString("kind").equals("slip", true)) Kind.SLIP else Kind.NAME
            val seen = it.optJSONArray("seen")?.let { s -> (0 until s.length()).map { i -> s.getString(i) }.toSet() } ?: emptySet()
            Correction(it.getString("heard"), it.getString("meant"), kind) to seen
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun samePair(a: Correction, b: Correction) = a.heard.equals(b.heard, true) && a.meant.equals(b.meant, true)

    /** One key per thing the user said, so the same line read again in a later review isn't a repeat. */
    private fun lineKey(line: String) = line.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().hashCode().toString(16)

    /**
     * Learns a pair only on evidence from the user, never on the assistant's say-so: the user said
     * the right words themselves, or the same wrong words came through in [SLIP_REPEATS] different
     * things they said. (A review rereads recent lines after every reply, so the same line seen
     * twice is one occurrence.) A name then joins the names to listen for; a slip is only recalled.
     * Returns the pairs learned now.
     */
    fun record(settings: HaSettings, findings: List<Finding>): List<Correction> {
        val known = load(settings).toMutableList()
        val fresh = findings.filter { f -> known.none { samePair(it, f.correction) } }
        if (fresh.isEmpty()) return emptyList()
        val learned = mutableListOf<Correction>()
        val pending = parseList(settings.pendingSlipsJson).map { Pending(it.first, it.second.toMutableSet()) }.toMutableList()
        for (f in fresh) {
            val c = f.correction
            if (learned.any { samePair(it, c) }) continue
            var p = pending.firstOrNull { samePair(it.correction, c) }
            if (p == null) p = Pending(c, mutableSetOf()).also { pending += it }
            p.seen += f.said.map { lineKey(it) }
            if (f.userSaidMeant || p.seen.size >= SLIP_REPEATS) {
                pending.remove(p)
                learned += c
            } else {
                AppLog.i("Noted a possible mishearing (learned only if it comes up again): heard \"${c.heard}\", meant \"${c.meant}\"")
            }
        }
        settings.pendingSlipsJson = JSONArray(pending.takeLast(MAX_PENDING).map { p ->
            JSONObject().put("heard", p.correction.heard).put("meant", p.correction.meant)
                .put("kind", p.correction.kind.name.lowercase()).put("seen", JSONArray(p.seen.toList()))
        }).toString()
        if (learned.isEmpty()) return learned
        val names = names(settings).toMutableList()
        val fromLearning = settings.learnedNames.lines().filter { it.isNotBlank() }.toMutableList()
        for (c in learned) {
            known += c
            if (c.kind == Kind.NAME) {
                if (names.none { it.equals(c.meant, true) }) {
                    names += c.meant
                    fromLearning += c.meant
                }
                AppLog.i("Learned a name: heard \"${c.heard}\", meant \"${c.meant}\"")
            } else {
                AppLog.i("Learned a mishearing: heard \"${c.heard}\", meant \"${c.meant}\"")
            }
        }
        save(settings, known.takeLast(MAX_CORRECTIONS))
        settings.listenNames = names.joinToString("\n")
        settings.learnedNames = fromLearning.distinct().joinToString("\n")
        return learned
    }

    /**
     * Forgets [correction]. Its meant name stops being listened for too, unless the user typed
     * that name in themselves or another correction still points to it. (Corrections saved before
     * learned names were tracked count their meant name as learned.)
     */
    fun remove(settings: HaSettings, correction: Correction) {
        val rest = load(settings).filterNot { samePair(it, correction) }
        save(settings, rest)
        val fromLearning = settings.learnedNames.lines().filter { it.isNotBlank() }
        val tracked = fromLearning.isNotEmpty()
        val learnedHere = !tracked || fromLearning.any { it.equals(correction.meant, true) }
        val stillUsed = rest.any { it.meant.equals(correction.meant, true) }
        if (learnedHere && !stillUsed) {
            settings.listenNames = names(settings).filterNot { it.equals(correction.meant, true) }.joinToString("\n")
            settings.learnedNames = fromLearning.filterNot { it.equals(correction.meant, true) }.joinToString("\n")
        }
    }

    /**
     * A note for the agent when [request] has words that turned out wrong before, so it starts
     * from what they meant last time; empty otherwise. Only matching pairs go along, so the
     * list can grow without making every request longer.
     */
    fun recallNote(known: List<Correction>, request: String): String {
        val matching = known.filter { Regex("\\b${Regex.escape(it.heard)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) }
        if (matching.isEmpty()) return ""
        return "\n\n[Speech recognizer history: " +
            matching.joinToString("; ") { "\"${it.heard}\" has turned out to mean \"${it.meant}\"" } +
            ". Use that unless this request clearly means otherwise.]"
    }

    /** The names to listen for, one per line in settings. */
    fun names(settings: HaSettings): List<String> =
        settings.listenNames.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun save(settings: HaSettings, list: List<Correction>) {
        settings.nameCorrectionsJson = JSONArray(list.map { c ->
            JSONObject().put("heard", c.heard).put("meant", c.meant).put("kind", c.kind.name.lowercase())
        }).toString()
    }

    private const val MAX_CORRECTIONS = 200
    private const val MAX_PENDING = 100
}

/**
 * Finds misheard words by reading the conversation afterward, on a separate quick AI Task. The
 * agent answering the user is busy with the request and rarely stops to report a mishearing, and a
 * correction can come several turns later, so this job is separate and sees the turns so far.
 */
object HeardReview {
    data class Turn(val fromUser: Boolean, val text: String)

    const val TURNS = 12

    fun instructions(turns: List<Turn>): String =
        "This is a conversation with a home voice assistant. The user's lines came through a speech " +
            "recognizer, which can write the wrong words. Find words in the user's lines that the rest of the " +
            "conversation shows were wrong: the user corrected or spelled them, even several lines later, or " +
            "the assistant took them to be a real name and the user went along with it. Include brands, " +
            "products, foods, exercises, people, places and device names. Leave out small words (a, the, of), " +
            "plurals, spelling variants of the same sound with the same meaning, and anything you're not sure " +
            "of. A word the assistant only guessed at and the user rejected doesn't count, and neither does " +
            "detail the user added later (\"cauliflower\", then \"riced cauliflower\"): only words that came " +
            "through wrong. If the user repeats or insists on their own words after the assistant used different " +
            "ones, the user's words were right: that is not a mishearing. " +
            "A nickname or short form of the same name (Katherine / Kate) is not a mishearing, and " +
            "neither is the user naming a different item (\"white sauce pizza\", then \"chicken veggie pizza\"). " +
            "\"heard\" must be the exact words from the user's line, the whole wrong phrase " +
            "(\"rice cauliflower\", not just \"rice\"), so it can't match ordinary speech. Give each a \"kind\": " +
            "\"name\" when the right words are a name the recognizer may not know (a person, brand, product, " +
            "medicine, place, show, device), or \"slip\" when ordinary words were misheard (\"paint it\" for " +
            "\"change it\"). Reply with only a JSON array, like " +
            "[{\"heard\": \"wrong words\", \"meant\": \"right words\", \"kind\": \"name\"}], or [] if there are none.\n\n" +
            turns.joinToString("\n") { (if (it.fromUser) "User: " else "Assistant: ") + it.text }

    /**
     * The pairs in the AI's [reply] whose wrong words really are in what the user said. A pair
     * whose right words contain the wrong ones ("rice" -> "riced", "cauliflower" -> "riced
     * cauliflower") is the user adding detail, not a mishearing, and would turn every later
     * "rice" into "riced", so it's dropped.
     */
    fun parse(reply: String, turns: List<Turn>): List<NameCorrections.Correction> = findings(reply, turns).map { it.correction }

    /** [parse] with the evidence for each pair: which user lines had the wrong words, and whether the user said the right ones. */
    fun findings(reply: String, turns: List<Turn>): List<NameCorrections.Finding> {
        val userLines = turns.filter { it.fromUser }.map { it.text }
        return parseRaw(reply, turns).map { c ->
            NameCorrections.Finding(
                correction = c,
                said = userLines.filter { it.contains(c.heard, ignoreCase = true) },
                userSaidMeant = userLines.any { containsWords(it, c.meant) },
            )
        }
    }

    /** Whole-word, case-insensitive: "meant" words said by the user, not just part of a longer word. */
    private fun containsWords(line: String, words: String) =
        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(words)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(line)

    private fun parseRaw(reply: String, turns: List<Turn>): List<NameCorrections.Correction> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = try {
            JSONArray(reply.substring(start, end + 1))
        } catch (e: org.json.JSONException) {
            return emptyList()
        }
        val said = turns.filter { it.fromUser }.joinToString("\n") { it.text }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .map {
                // Anything not clearly a name is treated as a slip: it has to repeat before it's used.
                val kind = if (it.optString("kind").equals("name", true)) NameCorrections.Kind.NAME else NameCorrections.Kind.SLIP
                NameCorrections.Correction(it.optString("heard").trim(), it.optString("meant").trim(), kind)
            }
            .filter { it.heard.isNotEmpty() && it.meant.isNotEmpty() && !it.meant.contains(it.heard, ignoreCase = true) }
            .filter { said.contains(it.heard, ignoreCase = true) }
    }
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
