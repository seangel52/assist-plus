package com.havoice.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

enum class NavigationApp(val label: String, val packageName: String) {
    GOOGLE_MAPS("Google Maps", "com.google.android.apps.maps"),
    WAZE("Waze", "com.waze"),
}

/** Something the phone does itself instead of asking Home Assistant. */
sealed class PhoneAction {
    /** Shown (and, for a timer, spoken) when the action starts. */
    abstract val reply: String

    /** Opens another app, so a locked phone has to be unlocked first. */
    open val opensApp: Boolean = true

    data class Navigate(val place: String, val mode: TravelMode, val app: NavigationApp) : PhoneAction() {
        override val reply get() = "Starting ${mode.word}directions to $place in ${app.label}."
    }

    data class Timer(val seconds: Int) : PhoneAction() {
        override val opensApp get() = false
        override val reply get() = "Timer set for ${PhoneActions.describe(seconds)}."
    }
}

enum class TravelMode(val code: String, val word: String) {
    DRIVING("d", ""),
    WALKING("w", "walking "),
    BIKING("b", "biking "),
}

/**
 * Recognizes requests the phone can handle on its own: navigation and timers. Patterns are strict
 * on purpose: anything they don't clearly match ("how long is left on the timer") goes to Home
 * Assistant. Music goes to Home Assistant too: Spotify only takes play requests through its
 * online service, which Home Assistant (SpotifyPlus) reaches for the phone and every speaker.
 */
object PhoneActions {
    private val LEAD = Regex(
        "^(?:(?:hey|ok|okay|so|um|uh|please|can you|could you|would you|will you|i want you to|i said)[,\\s]+)+",
        RegexOption.IGNORE_CASE,
    )
    private val TRAIL = Regex("(?:[,\\s]+please)?[.?!,\\s]*$", RegexOption.IGNORE_CASE)

    private val NAVIGATE = Regex(
        "^(take me|get me|bring me|drive me|walk me|bike me|route me|navigate me|navigate|start navigation|" +
            "(?:give me |get |start )?(?:driving |walking |biking |cycling )?directions|how do i get)" +
            "\\s+(?:to|towards)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val NAVIGATE_HOME = Regex(
        "^(take me|get me|bring me|drive me|walk me|navigate|directions)\\s+(home)$",
        RegexOption.IGNORE_CASE,
    )
    private val NAVIGATION_APP = Regex("\\s+(?:on|in|with|using|via)\\s+(google maps|maps|waze)$", RegexOption.IGNORE_CASE)

    fun parse(text: String, navigationApp: NavigationApp): PhoneAction? {
        val request = text.trim().replace(LEAD, "").replace(TRAIL, "")
        if (request.isEmpty()) return null
        return navigation(request, navigationApp) ?: timer(request)
    }

    private fun navigation(request: String, defaultApp: NavigationApp): PhoneAction? {
        val named = NAVIGATION_APP.find(request)
        val app = when (named?.groupValues?.get(1)?.lowercase()) {
            null -> defaultApp
            "waze" -> NavigationApp.WAZE
            else -> NavigationApp.GOOGLE_MAPS
        }
        val rest = named?.let { request.removeRange(it.range) } ?: request
        val match = NAVIGATE.find(rest) ?: NAVIGATE_HOME.find(rest) ?: return null
        val (how, said) = match.destructured
        // The recognizer ends sentences where you pause ("nine fifty six. Elmwood Street."); an address is one.
        val place = said.replace(Regex("[.?!]+\\s+"), " ").trimEnd('.', '?', '!', ' ')
        val mode = when {
            how.contains("walk", ignoreCase = true) -> TravelMode.WALKING
            how.contains("bik", ignoreCase = true) || how.contains("cycl", ignoreCase = true) -> TravelMode.BIKING
            else -> TravelMode.DRIVING
        }
        return PhoneAction.Navigate(withHouseNumber(place.trim()), mode, app)
    }

    val STREET_WORDS = setOf(
        "street", "st", "road", "rd", "avenue", "ave", "drive", "dr", "lane", "ln", "boulevard", "blvd",
        "way", "court", "ct", "place", "pl", "parkway", "pkwy", "highway", "hwy", "circle", "cir",
        "terrace", "trail", "pike", "square", "row", "run", "crossing", "loop",
    )

    /**
     * Maps apps can't find "three thousand one forty East Main Street", and the speech model
     * writes house numbers out as words, so a spoken house number becomes digits ("3140 East
     * Main Street"). Only a number at the start, before a street name, is changed: "Seven Eleven
     * on Main Street" and "Five Guys" stay as said.
     */
    fun withHouseNumber(place: String): String {
        val words = place.replace(Regex("(?<=[a-zA-Z])-(?=[a-zA-Z])"), " ").split(' ').filter { it.isNotEmpty() }
        fun plain(word: String) = word.lowercase().trim(',', '.')
        var end = 0
        while (end < words.size && (isNumberWord(plain(words[end])) || (end > 0 && plain(words[end]) == "and"))) end++
        // A trailing "and" isn't part of the number, unless a numbered street follows ("one hundred and twelfth").
        val ordinalNext = words.getOrNull(end)?.let { plain(it) in ORDINALS } == true
        while (end > 0 && plain(words[end - 1]) == "and" && !ordinalNext) end--
        if (end == 0) return place
        val rest = words.drop(end)
        if (rest.isEmpty() || plain(rest.first()) in setOf("on", "at", "near", "in", "by")) return place
        if (rest.none { plain(it) in STREET_WORDS }) return place

        // A numbered street takes the number words just before its ordinal:
        // "twelve twelve one hundred first Avenue" is 1212 101st Avenue.
        var houseEnd = end
        var street = rest
        ORDINALS[plain(rest.first())]?.let { ordinal ->
            var value = ordinal
            if (ordinal < 10 && houseEnd > 0 && plain(words[houseEnd - 1]) in TENS) {
                value += TENS.getValue(plain(words[houseEnd - 1])).toInt()
                houseEnd--
            }
            if (houseEnd > 0 && plain(words[houseEnd - 1]) == "and") houseEnd--
            if (houseEnd >= 2 && plain(words[houseEnd - 1]) == "hundred" && plain(words[houseEnd - 2]) in ONES) {
                value += ONES.getValue(plain(words[houseEnd - 2])).toInt() * 100
                houseEnd -= 2
            } else if (houseEnd < end && plain(words[houseEnd]) == "and") {
                houseEnd++ // the "and" wasn't part of the street after all
            }
            street = listOf(ordinalText(value)) + rest.drop(1)
        }
        // "…East one hundred first Street": the numbered street comes after a direction.
        if (street === rest) street = numberedStreetAfterDirection(rest)
        if (houseEnd == 0) return street.joinToString(" ")
        val number = spokenNumber(words.take(houseEnd).map(::plain)) ?: return place
        return (listOf(number) + street).joinToString(" ")
    }

    private val DIRECTIONS = setOf(
        "north", "south", "east", "west", "northeast", "northwest", "southeast", "southwest",
        "n", "s", "e", "w", "ne", "nw", "se", "sw",
    )

    /** "East one hundred first Street" → "East 101st Street"; anything else is returned as is. */
    private fun numberedStreetAfterDirection(words: List<String>): List<String> {
        fun plain(word: String) = word.lowercase().trim(',', '.')
        var start = 0
        while (start < words.size && plain(words[start]) in DIRECTIONS) start++
        if (start == 0) return words
        var ordinalAt = start
        while (ordinalAt < words.size && (isNumberWord(plain(words[ordinalAt])) || plain(words[ordinalAt]) == "and")) ordinalAt++
        val ordinal = words.getOrNull(ordinalAt)?.let { ORDINALS[plain(it)] } ?: return words
        val prefix = words.subList(start, ordinalAt).map(::plain)
        val tens = if (prefix.isEmpty()) 0 else spokenNumber(prefix)?.toIntOrNull() ?: return words
        return words.take(start) + ordinalText(tens + ordinal) + words.drop(ordinalAt + 1)
    }

    private val ORDINALS = listOf(
        "zeroth", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
        "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
        "eighteenth", "nineteenth",
    ).withIndex().associate { (i, w) -> w to i } + mapOf(
        "twentieth" to 20, "thirtieth" to 30, "fortieth" to 40, "fiftieth" to 50, "sixtieth" to 60,
        "seventieth" to 70, "eightieth" to 80, "ninetieth" to 90, "hundredth" to 100,
    )

    /** 101 → "101st", 112 → "112th", 23 → "23rd". */
    private fun ordinalText(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    // The recognizer sometimes clips "three" to "thir".
    private val NUMBER_ALIASES = mapOf("oh" to 0, "thir" to 3)

    private fun isNumberWord(word: String) =
        word.all { it.isDigit() } || word in ONES || word in TENS || word in NUMBER_ALIASES ||
            word == "hundred" || word == "thousand"

    /**
     * Reads a spoken number the way addresses are said: "three thousand one hundred forty",
     * "three thousand one forty", "thirty one forty", "three one four zero" and "twelve oh five"
     * are all one number.
     */
    private fun spokenNumber(words: List<String>): String? {
        var total = 0L
        val groups = mutableListOf<String>() // spoken pieces since the last "hundred"/"thousand"
        var current: Int? = null
        var afterTens = false
        fun close() {
            current?.let { groups += it.toString() }
            current = null
            afterTens = false
        }
        // "one forty" is 1-40, "thirty one forty" 31-40, "twelve oh five" 12-05, "nine one one" 9-1-1.
        fun piecesValue(): Long? {
            if (groups.isEmpty()) return null
            val merged = mutableListOf<String>()
            for (group in groups) {
                if (merged.lastOrNull() == "0" && group.length == 1) merged[merged.lastIndex] = "0$group" else merged += group
            }
            val digits = if (merged.all { it.length == 1 }) {
                merged.joinToString("")
            } else {
                merged.first() + merged.drop(1).joinToString("") { it.padStart(2, '0') }
            }
            groups.clear()
            return digits.toLongOrNull()
        }
        for (word in words) {
            val value = NUMBER_ALIASES[word] ?: ONES[word]?.toInt() ?: TENS[word]?.toInt()
            when {
                word == "and" -> close()
                word == "hundred" -> {
                    close()
                    total += (piecesValue() ?: 1) * 100
                }
                word == "thousand" -> {
                    close()
                    total = (total + (piecesValue() ?: 1)) * 1000
                }
                word.all { it.isDigit() } -> {
                    close()
                    groups += word
                }
                value == null -> return null
                word in TENS -> {
                    close()
                    current = value
                    afterTens = true
                }
                afterTens && value < 10 -> {
                    current = current!! + value
                    close()
                }
                else -> {
                    close()
                    current = value
                    if (value >= 10) close()
                }
            }
        }
        close()
        val result = total + (piecesValue() ?: 0)
        return if (result > 0 || words.any { it == "zero" || it == "0" }) result.toString() else null
    }

    private val TIMER_START = Regex("^(?:set|start|create|make|put on)\\b|^(?:a |an )?[0-9a-z ]+ timer$", RegexOption.IGNORE_CASE)
    private val NOT_A_NEW_TIMER = Regex("\\b(?:cancel|stop|delete|remove|pause|how|left|remaining|check)\\b", RegexOption.IGNORE_CASE)

    private fun timer(request: String): PhoneAction? {
        if (!Regex("\\btimer\\b", RegexOption.IGNORE_CASE).containsMatchIn(request)) return null
        if (!TIMER_START.containsMatchIn(request) || NOT_A_NEW_TIMER.containsMatchIn(request)) return null
        val seconds = durationSeconds(request) ?: return null
        return PhoneAction.Timer(seconds)
    }

    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { (i, w) -> w to i.toDouble() }
    private val TENS = mapOf("twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0, "fifty" to 50.0, "sixty" to 60.0,
        "seventy" to 70.0, "eighty" to 80.0, "ninety" to 90.0,
    )
    private val UNITS = mapOf(
        "hour" to 3600, "hours" to 3600, "hr" to 3600, "hrs" to 3600,
        "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60,
        "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1,
    )

    /** "ten minutes", "a 5 minute", "an hour and a half", "half an hour" → seconds; null if none. */
    fun durationSeconds(text: String): Int? {
        var total = 0.0
        var number: Double? = null
        var fromArticle = false // number is just "a"/"an"
        var afterTens = false // "twenty" can take a "five" after it
        var half = false // "half an hour"
        var lastUnit: Int? = null
        for (token in text.lowercase().replace('-', ' ').split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }) {
            val value = token.toDoubleOrNull() ?: ONES[token] ?: TENS[token]
            when {
                value != null -> {
                    number = if (afterTens && value < 10) (number ?: 0.0) + value else value
                    afterTens = token in TENS
                    fromArticle = false
                }
                token == "a" || token == "an" -> if (number == null) {
                    number = 1.0
                    fromArticle = true
                }
                token == "couple" -> {
                    number = 2.0
                    fromArticle = false
                }
                token == "half" -> when {
                    // "one and a half hours"
                    number != null && !fromArticle -> number += 0.5
                    // "an hour and a half"
                    lastUnit != null -> {
                        total += 0.5 * lastUnit
                        number = null
                    }
                    // "a half hour"
                    number != null -> number = 0.5
                    // "half an hour"
                    else -> half = true
                }
                token in UNITS -> {
                    val unit = UNITS.getValue(token)
                    total += (number ?: 1.0) * unit * (if (half) 0.5 else 1.0)
                    lastUnit = unit
                    number = null
                    half = false
                    afterTens = false
                }
                else -> afterTens = false
            }
        }
        val seconds = total.toInt()
        return seconds.takeIf { it in 1..24 * 3600 }
    }

    fun describe(seconds: Int): String {
        fun part(n: Int, unit: String) = if (n == 0) null else "$n $unit" + if (n == 1) "" else "s"
        return listOfNotNull(part(seconds / 3600, "hour"), part(seconds % 3600 / 60, "minute"), part(seconds % 60, "second"))
            .joinToString(" and ")
    }

    /** Does [action]; returns a message to show if it couldn't. */
    fun start(context: Context, action: PhoneAction): String? = when (action) {
        is PhoneAction.Navigate -> navigate(context, action)
        is PhoneAction.Timer -> {
            val timer = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (launch(context, timer)) null else "No clock app on this phone can set timers."
        }
    }

    private fun navigate(context: Context, action: PhoneAction.Navigate): String? {
        val place = Uri.encode(action.place)
        val preferred = when (action.app) {
            NavigationApp.GOOGLE_MAPS -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$place&mode=${action.mode.code}"))
            NavigationApp.WAZE -> Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$place&navigate=yes"))
        }.setPackage(action.app.packageName)
        // Without that app, any maps app can at least search for the place.
        val started = launch(context, preferred) || launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$place")))
        return if (started) null else "No maps app is installed."
    }

    /** Starts [intent] in its own task; false if no app handles it. */
    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
