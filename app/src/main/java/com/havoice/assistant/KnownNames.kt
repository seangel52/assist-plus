package com.havoice.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

/** Where a name is known from. */
enum class NameSource {
    /** Typed by the user into Settings ("Names to listen for"). */
    PINNED,

    /** Home Assistant: devices, areas, people, scripts, media sources. */
    HOME,

    /** The user's own data, through the optional Home Assistant script (see [NameSources]). */
    YOURS,

    /** Apps installed on the phone. */
    APP,

    /** The phone's contacts. */
    CONTACT,

    /** Conversations: typed by the user, or used by the assistant where the recognizer wrote something else. */
    TALK,
}

/**
 * A name from the user's world (a person, device, brand, product, place) and what is known
 * about it: where it is listed, and how much it comes up.
 *
 * [uses] counts the requests it came up in, as of [day] (days since 1970), and halves every
 * [KnownNames.HALF_LIFE_DAYS], so what the user talks about now outweighs what they used to.
 */
data class KnownName(
    val text: String,
    val sources: Set<NameSource>,
    val uses: Double = 0.0,
    val day: Long = 0,
    val blocked: Boolean = false,
) {
    fun usesOn(today: Long): Double = uses * 0.5.pow(maxOf(0L, today - day) / KnownNames.HALF_LIFE_DAYS)

    /** Listed somewhere the user keeps names (not only heard in conversation). */
    val listed: Boolean get() = sources.any { it != NameSource.TALK }
}

/**
 * The names the speech recognizer should know about.
 *
 * The recognizer hears ordinary words well; what it can't know is the user's own names
 * ("Marlon" → "Marlin", "Orzo" → "Ozo"). This is how speech products handle it: the recognizer
 * is given the user's own lists (contacts, devices, apps) and leans toward them, and what it
 * still gets wrong is caught afterward by looking for known names that sound like what it wrote.
 *
 * **Where names come from.** Lists the user already has ([NameSource]: Home Assistant, contacts,
 * apps, their own data), each only if switched on in Settings, and conversations. A listed name
 * is known to be real and is used from the start. A name seen only in conversation has to come
 * up in [TALK_USES_NEEDED] separate requests first (typing it counts double), and only evidence
 * from outside the recognizer counts for it: the assistant used it where the recognizer wrote
 * something else, or the user typed it. The recognizer's own mistakes can't teach it.
 *
 * **How much each counts.** Every request a name comes up in adds to its [KnownName.uses]; a
 * hundred uses in a day are a hundred uses. Uses fade with a [HALF_LIFE_DAYS] half-life.
 *
 * **How they are used.** Measured on the owner's recordings with the on-phone model: a list of
 * 5,000 other names changed no words and cost no accuracy, but building it for every request
 * costs time, so the recognizer leans toward the [STEER_MAX] that matter most; a boost of 2 or
 * more began turning half-second noises into words, so a name's boost runs from 1.0 (listed,
 * never used) to [BOOST_MAX] (used [USES_FOR_FULL_BOOST] times or more). All [MAX_NAMES] are
 * used for the second check ([hint]): a request with words that sound like a known name goes to
 * the assistant with a note saying so, and the assistant, which knows what is being asked,
 * decides.
 *
 * Kept in the app's files, not its cache.
 */
object KnownNames {
    const val FROM_REPLY = 1.0
    const val FROM_TYPED = 2.0
    const val FROM_HEARD = 1.0
    const val TALK_USES_NEEDED = 2.0
    const val HALF_LIFE_DAYS = 90.0
    const val MAX_USES = 50.0
    const val MAX_NAMES = 5000
    const val STEER_MAX = 600
    const val BOOST_MAX = 1.5f
    const val USES_FOR_FULL_BOOST = 5.0

    /** Below this a name that is listed nowhere is forgotten. */
    private const val FORGET_BELOW = 0.2

    private const val MAX_WORDS = 4
    private const val MAX_HINTS = 3

    /** An ordinary-looking word is only second-guessed against names at least this long. */
    private const val LONG_NAME = 7

    /** The first word of a sentence is only second-guessed against names at least this long. */
    private const val INITIAL_NAME_LETTERS = 6

    private val SENTENCE_ENDS = setOf('.', '!', '?', ':')

    // Capitalized in any sentence, and no news to the recognizer.
    private val CALENDAR = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december",
    )

    private fun letters(word: String) = word.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    private fun isNameWord(token: String): Boolean {
        val first = token.firstOrNull() ?: return false
        if (!first.isUpperCase()) return false
        // "I", "I'm", "I'll"; and "TV", "PC", "OK", "AM".
        if (token == "I" || token.startsWith("I'") || token.startsWith("I’")) return false
        if (token.count { it.isLetter() } < 3) return false
        return letters(token) !in CALENDAR
    }

    /**
     * The names in [text]: runs of capitalized words ("Field & Fern", "Porter's Pick"). The
     * first word of a sentence is capitalized whatever it is, so it only counts when [known] says
     * it is a name already. A long run is a product's full label ("Field & Fern Sustainably
     * Caught Chunk Light Tuna"); its first two words, the brand, are kept.
     */
    fun namesIn(text: String, known: (String) -> Boolean = { false }): List<String> {
        val names = mutableListOf<String>()
        val run = mutableListOf<String>()
        fun close() {
            while (run.lastOrNull() == "&") run.removeAt(run.lastIndex)
            if (run.isNotEmpty()) {
                val words = if (run.count { it != "&" } > MAX_WORDS) run.take(if (run.getOrNull(1) == "&") 3 else 2) else run.toList()
                val name = words.joinToString(" ").replace(Regex("['’]s$"), "")
                if (name.count { it.isLetter() } >= 3) names += name
            }
            run.clear()
        }
        var sentenceStart = true
        for (raw in text.split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            val token = raw.trim { !it.isLetterOrDigit() && it != '&' }
            val opens = raw.takeWhile { !it.isLetterOrDigit() && it != '&' }
            val closes = raw.takeLastWhile { !it.isLetterOrDigit() && it != '&' }
            // A quote or bracket before the word starts something new.
            if (opens.isNotEmpty()) close()
            when {
                token == "&" && run.isNotEmpty() -> run += "&"
                isNameWord(token) && (!sentenceStart || known(token.replace(Regex("['’]s$"), ""))) -> run += token
                else -> close()
            }
            // Punctuation after the word ends the name; a full stop ends the sentence.
            if (closes.isNotEmpty()) close()
            sentenceStart = closes.any { it == '.' || it == '!' || it == '?' || it == ':' }
        }
        close()
        return names.distinctBy { it.lowercase() }
    }

    /** Whether every word of [name] is in [text] as a word, so the recognizer already wrote it. */
    private fun saidIn(name: String, text: String): Boolean {
        val words = text.split(Regex("\\s+")).map { letters(it.replace(Regex("['’]s\\b"), "")) }.toSet()
        return name.split(' ').filter { it != "&" }.all { letters(it.replace(Regex("['’]s$"), "")) in words }
    }

    /**
     * The evidence in one exchange: names the user [typed] (null for a spoken request), and names
     * in the assistant's [reply] that weren't in what the recognizer [heard] (null for a typed one).
     */
    fun sightings(typed: String?, heard: String?, reply: String, known: (String) -> Boolean): Map<String, Double> {
        val found = linkedMapOf<String, Double>()
        typed?.let { t -> namesIn(t, known).forEach { found[it] = FROM_TYPED } }
        namesIn(reply, known)
            .filter { name -> heard == null || !saidIn(name, heard) }
            .forEach { name -> if (found.keys.none { it.equals(name, true) }) found[name] = FROM_REPLY }
        return found
    }

    /** The listed names said in [heard] and written right by the recognizer. */
    fun saidNames(heard: String, names: List<KnownName>): List<String> {
        val words = heard.split(Regex("\\s+")).map { w -> letters(w.trim { !it.isLetterOrDigit() }.replace(Regex("['\u2019]s$"), "")) }.filter { it.isNotEmpty() }
        val runs = HashSet<String>()
        for (i in words.indices) for (size in 1..MAX_WORDS) if (i + size <= words.size) runs += words.subList(i, i + size).joinToString(" ")
        return names.filter { n ->
            !n.blocked && n.listed && n.text.split(' ').filter { it != "&" }.joinToString(" ") { letters(it) } in runs
        }.map { it.text }
    }

    /**
     * [names] after one request in which the names in [came] came up, each with how much it
     * counts. A name not seen before starts as a conversation-only name.
     */
    fun observe(names: List<KnownName>, came: Map<String, Double>, today: Long): List<KnownName> {
        val byKey = LinkedHashMap<String, KnownName>()
        names.forEach { byKey[it.text.lowercase()] = it }
        for ((name, amount) in came) {
            val key = name.lowercase()
            val old = byKey[key]
            if (old?.blocked == true) continue
            val uses = minOf(MAX_USES, (old?.usesOn(today) ?: 0.0) + amount)
            byKey[key] = old?.copy(uses = uses, day = today) ?: KnownName(name, setOf(NameSource.TALK), uses, today)
        }
        return trim(byKey.values.toList(), today)
    }

    /** [names] with [source] now listing exactly [listed]: new ones join, ones no longer listed lose that source. */
    fun withSource(names: List<KnownName>, source: NameSource, listed: List<String>, today: Long): List<KnownName> {
        val byKey = LinkedHashMap<String, KnownName>()
        names.forEach { byKey[it.text.lowercase()] = it.copy(sources = it.sources - source) }
        for (name in listed.map { it.trim() }.filter { it.count { c -> c.isLetter() } >= 3 }) {
            val key = name.lowercase()
            val old = byKey[key]
            if (old?.blocked == true) continue
            byKey[key] = old?.copy(sources = old.sources + source) ?: KnownName(name, setOf(source), 0.0, today)
        }
        return trim(byKey.values.filter { it.blocked || it.sources.isNotEmpty() }, today)
    }

    /** Drops what is listed nowhere and has faded, and keeps the [MAX_NAMES] that matter most. */
    private fun trim(names: List<KnownName>, today: Long): List<KnownName> =
        names.filter { it.blocked || it.listed || it.usesOn(today) >= FORGET_BELOW }
            .sortedWith(compareByDescending<KnownName> { it.blocked }.thenByDescending { it.usesOn(today) }.thenBy { rank(it) })
            .take(MAX_NAMES)

    /** Which lists matter most when there is room for only some: the user's own first, contacts last. */
    private fun rank(name: KnownName): Int = name.sources.minOfOrNull { it.ordinal } ?: NameSource.entries.size

    /** The names in use on [today]: listed ones, and conversation-only ones that came up often enough. */
    fun inUse(names: List<KnownName>, today: Long): List<KnownName> =
        names.filter { !it.blocked && (it.listed || it.usesOn(today) >= TALK_USES_NEEDED) }

    /** How strongly the recognizer leans toward [name]: more, the more it comes up. */
    fun boost(name: KnownName, today: Long): Float =
        1f + (BOOST_MAX - 1f) * (name.usesOn(today) / USES_FOR_FULL_BOOST).coerceIn(0.0, 1.0).toFloat()

    /** The [STEER_MAX] names the recognizer leans toward, with their boosts: the most used, then by list. */
    fun steering(names: List<KnownName>, today: Long): List<Pair<String, Float>> =
        inUse(names, today)
            .sortedWith(compareByDescending<KnownName> { it.usesOn(today) }.thenBy { rank(it) }.thenBy { it.text })
            .take(STEER_MAX)
            .map { it.text to boost(it, today) }

    /** A plural or possessive of the same name is the name ("kitchen lights" for "Kitchen Light"). */
    private fun stem(key: String) = key.removeSuffix("s")

    /**
     * Words in [request] that sound like one of [names] without being it, as (heard, name); the
     * closest name for each stretch of the request, a few at most. The rules come from a
     * phone's real requests checked against its real list of names (the examples here are
     * made up, of the same kinds):
     *
     * - Words that run together into exactly the name match however written ("abrolls" for
     *   "Ab Rolls", "sun porch" for "SunPorch").
     * - A word the recognizer wrote as a name itself (capitalized inside a sentence: "Velmonde",
     *   "Ozo") is compared by spelling: at most a quarter of the letters may differ. Every
     *   mishearing of a name on record was within that (like "Picogril" for "Tico Grill"). A
     *   looser comparison by rough sound was tried and taken out: it matched ordinary words
     *   such as "Protein" to device names with the same consonants.
     * - With the same number of words, each word is compared with its own: a mishearing bends
     *   letters inside a word, it doesn't swap a word ("Garden list" is not "Garden Plus").
     * - The first word of a sentence is capitalized whatever it is, so there it takes a longer
     *   name ([INITIAL_NAME_LETTERS]) to count: "Marlin wants…" is "Marlon", but "Does that…" is
     *   not an app called "Docs".
     * - A word written as an ordinary one is second-guessed only when it is long and one letter
     *   from a name ("provlone" for "Provolone"); ordinary speech is otherwise left alone.
     * - Where the request already has a known name, written right, nothing around it is
     *   second-guessed ("a Porters Pick shake" is not a mishearing of "Porters").
     */
    fun soundAlikes(request: String, names: List<String>): List<Pair<String, String>> {
        val raw = request.split(Regex("\\s+")).filter { it.any { c -> c.isLetterOrDigit() || c == '&' } }
        if (raw.isEmpty() || names.isEmpty()) return emptyList()
        val tokens = raw.map { it.trim { c -> !c.isLetterOrDigit() && c != '&' } }
        // Whether each word starts a sentence (its capital then says nothing).
        val starts = raw.indices.map { i -> i == 0 || raw[i - 1].trimEnd('"', '\'', ')', '\u201d').lastOrNull() in SENTENCE_ENDS }
        class Name(val text: String, val key: String, val words: List<String>)
        val known = names.map { name -> Name(name, letters(name), name.split(' ').map { letters(it) }.filter { it.isNotEmpty() }) }
            .filter { it.key.length >= 4 }
        val byKey = known.associateBy { it.key }
        val stems = known.map { stem(it.key) }.toHashSet()
        class Window(val from: Int, val to: Int, val words: List<String>, val heard: String)
        val windows = (1..minOf(tokens.size, MAX_WORDS + 1)).flatMap { size ->
            (0..tokens.size - size).map { from ->
                val words = tokens.subList(from, from + size).map { letters(it) }
                Window(from, from + size, words, words.joinToString(""))
            }
        }.filter { it.heard.length >= 3 }
        // Stretches that are a known name already (or its plural), split into words as it is.
        val right = windows.filter { w ->
            val name = byKey[w.heard]
            if (name != null) name.words == w.words else stem(w.heard) in stems
        }
        fun capital(i: Int) = tokens[i].first().isUpperCase() && tokens[i] != "I" && !tokens[i].startsWith("I'")
        class Found(val from: Int, val to: Int, val name: String, val distance: Int)
        val found = mutableListOf<Found>()
        for (w in windows) {
            if (right.any { w.from < it.to && it.from < w.to }) continue
            val exact = byKey[w.heard]
            if (exact != null) {
                // The same letters split another way.
                found += Found(w.from, w.to, exact.text, 0)
                continue
            }
            val asName = (w.from until w.to).any { capital(it) && !starts[it] }
            val asFirstWord = !asName && (w.from until w.to).any { capital(it) }
            for (name in known) {
                val allowed = when {
                    asName -> name.key.length / 4
                    asFirstWord && name.key.length >= INITIAL_NAME_LETTERS -> name.key.length / 4
                    name.key.length >= LONG_NAME -> 1
                    else -> 0
                }
                if (allowed == 0 || abs(name.words.size - w.words.size) > 1) continue
                val distance = if (name.words.size == w.words.size) {
                    // Word by word: each within a quarter of its own letters.
                    var total = 0
                    for (i in name.words.indices) {
                        val limit = name.words[i].length / 4
                        val d = editDistance(w.words[i], name.words[i], limit)
                        total = if (d > limit) allowed + 1 else total + d
                        if (total > allowed) break
                    }
                    total
                } else {
                    editDistance(w.heard, name.key, allowed)
                }
                if (distance in 1..allowed) found += Found(w.from, w.to, name.text, distance)
            }
        }
        // The closest match for each stretch of the request; no stretch explained twice.
        val chosen = mutableListOf<Found>()
        for (f in found.sortedWith(compareBy({ it.distance }, { it.to - it.from }, { it.name }))) {
            if (chosen.none { f.from < it.to && it.from < f.to }) chosen += f
        }
        return chosen.sortedBy { it.from }.take(MAX_HINTS).map { tokens.subList(it.from, it.to).joinToString(" ") to it.name }
    }

    /**
     * A note for the assistant when [request] has words that sound like known names, or nothing.
     * It is told, not overruled: the words on screen stay as heard and the assistant decides.
     */
    fun hint(request: String, names: List<String>): String {
        val alike = soundAlikes(request, names)
        if (alike.isEmpty()) return ""
        return "\n\n[Speech recognizer check: " +
            alike.joinToString("; ") { (heard, name) -> "\"$heard\" sounds like \"$name\", a name from this home's earlier conversations" } +
            ". Go with the known name where it fits what is being asked.]"
    }

    /** How many letters must change to turn [a] into [b]; stops counting once past [limit]. */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        if (abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (current[j] < best) best = current[j]
            }
            if (best > limit) return limit + 1
            previous = current
        }
        return previous[b.length]
    }

    // Storage.

    private fun file(context: Context) = File(context.filesDir, "known_names.json")

    fun today(): Long = System.currentTimeMillis() / 86_400_000L

    @Synchronized
    fun load(context: Context): List<KnownName> {
        val f = file(context)
        if (!f.exists()) {
            // The first run after single word fixes were replaced: they go; the names they
            // pointed to are already in "Names to listen for".
            HaSettings(context).forgetWordFixes()
            return emptyList()
        }
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { array.getJSONObject(it) }.map { o ->
                val sources = o.optJSONArray("sources")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> NameSource.entries.firstOrNull { it.name == a.getString(i) } }.toSet()
                } ?: setOf(NameSource.TALK)
                KnownName(o.getString("name"), sources, o.optDouble("uses", 0.0), o.optLong("day", 0), o.optBoolean("blocked", false))
            }
        } catch (e: Exception) {
            // A damaged file only means starting again.
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, names: List<KnownName>) {
        val json = JSONArray(names.map { n ->
            JSONObject().put("name", n.text).put("sources", JSONArray(n.sources.map { it.name })).put("uses", n.uses).put("day", n.day)
                .apply { if (n.blocked) put("blocked", true) }
        }).toString()
        val target = file(context)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(json)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** The names the user typed into Settings, one per line. */
    fun pinned(settings: HaSettings): List<String> =
        settings.listenNames.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Everything known, with the user's typed names as they are in Settings right now. */
    @Synchronized
    fun all(context: Context, settings: HaSettings): List<KnownName> =
        withSource(load(context), NameSource.PINNED, pinned(settings), today())

    /** Replaces what [source] lists (see [withSource]) and saves. */
    @Synchronized
    fun setSource(context: Context, source: NameSource, listed: List<String>) {
        save(context, withSource(load(context), source, listed, today()))
    }

    /**
     * Learns from one finished request: names from outside the recognizer (see [sightings];
     * [tagged] are names the assistant marked with a "[heard: X -> Y]" tag), and listed names
     * the user said and the recognizer heard right. Returns conversation names that came into
     * use just now.
     */
    @Synchronized
    fun learn(context: Context, settings: HaSettings, typed: String?, heard: String?, reply: String, tagged: List<String> = emptyList()): List<String> {
        val today = today()
        val before = all(context, settings)
        val have = before.filter { !it.blocked }.flatMap { it.text.split(' ') }.map { letters(it) }.toSet()
        val came = sightings(typed, heard, reply) { letters(it) in have }.toMutableMap()
        tagged.forEach { name -> if (came.keys.none { it.equals(name, true) }) came[name] = FROM_REPLY }
        (heard ?: typed)?.let { said ->
            saidNames(said, before).forEach { name -> if (came.keys.none { it.equals(name, true) }) came[name] = FROM_HEARD }
        }
        if (came.isEmpty()) return emptyList()
        val after = observe(before, came, today)
        save(context, after)
        val was = inUse(before, today).map { it.text.lowercase() }.toSet()
        return inUse(after, today).filter { !it.listed && it.text.lowercase() !in was }.map { it.text }
    }

    /** Stops using [name] and keeps it from being learned again. */
    @Synchronized
    fun block(context: Context, name: String) {
        val today = today()
        val rest = load(context).filterNot { it.text.equals(name, true) }
        save(context, trim(rest + KnownName(name, emptySet(), 0.0, today, blocked = true), today))
    }

    /** Forgets every name that came only from conversations, and how often any name was used. */
    @Synchronized
    fun forgetLearned(context: Context) {
        save(context, load(context).filter { it.listed && !it.blocked }.map { it.copy(uses = 0.0) })
    }
}

/** Tags an agent may put in a reply, taken out before it is shown or spoken. */
object ReplyTags {
    private val HEARD = Regex("\\s*\\[\\s*heard\\s*:\\s*([^\\]]+?)\\s*(?:->|→)\\s*([^\\]]+?)\\s*]", RegexOption.IGNORE_CASE)

    /** The reply without "[heard: X -> Y]" tags, and the names (Y) they carried. */
    fun stripHeard(reply: String): Pair<String, List<String>> {
        if (!HEARD.containsMatchIn(reply)) return reply to emptyList()
        val names = HEARD.findAll(reply).map { it.groupValues[2].trim() }.filter { it.isNotBlank() }.toList()
        return HEARD.replace(reply, " ").replace(Regex(" {2,}"), " ").replace(Regex(" ([.,!?])"), "$1").trim() to names
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

    /**
     * Hotwords for [names] (each with its boost), one per line, in sherpa-onnx's format:
     * "Name :1.25".
     */
    fun hotwords(context: Context, names: List<Pair<String, Float>>): String {
        if (names.isEmpty()) return ""
        val vocab = pieces(context)
        return names.filter { (name, _) -> PLAIN.matches(name) && encode(vocab, name) != null }
            .joinToString("\n") { (name, boost) -> "$name :%.2f".format(java.util.Locale.US, boost) }
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
