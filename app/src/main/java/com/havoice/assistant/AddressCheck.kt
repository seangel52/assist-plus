package com.havoice.assistant

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * Looks a spoken street address up before navigating, so a misheard house number or street is
 * caught instead of sending the driver somewhere else. Uses Android's built-in geocoder (Google's,
 * the same lookup Maps does), searching near the Home Assistant home first.
 */
object AddressCheck {
    data class Address(
        val houseNumber: String?,
        val street: String?,
        val city: String?,
        val state: String?,
        val latitude: Double? = null,
        val longitude: Double? = null,
    ) {
        /** What the assistant says: "3140 East Oakland Street, Springfield". */
        val spoken get() = listOfNotNull(streetLine(), city).joinToString(", ")

        /** What the maps app gets, with the state so it can't pick another city's street. */
        val query get() = listOfNotNull(streetLine(), city, state).joinToString(", ")

        private fun streetLine() = listOfNotNull(houseNumber, street).joinToString(" ").ifBlank { null }
    }

    sealed class Result {
        /** [sure]: same house number and (nearly) the same street, so just go. */
        data class Found(val address: Address, val sure: Boolean) : Result()
        data object NotFound : Result()

        /** No geocoder on this phone, or it's offline: navigate as heard. */
        data object Unavailable : Result()
    }

    private val HOUSE_NUMBER = Regex("^(\\d+[a-z]?)\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** Only street addresses are checked; "Target on Main Street" is left to the maps app's search. */
    fun needsCheck(place: String) = HOUSE_NUMBER.matches(place)

    /**
     * [near]: the phone or home. Unless a city was said ("… Street, Springfield"), only places within
     * [FAR_KM] of it count: a street that doesn't exist here mustn't send the driver to another
     * state's street of the same name.
     */
    suspend fun check(context: Context, place: String, near: Pair<Double, Double>?): Result {
        if (!Geocoder.isPresent()) return Result.Unavailable
        val geocoder = Geocoder(context, Locale.getDefault())
        val anywhere = near == null || namesCity(place)
        fun search(text: String): List<Address> {
            val nearby = near?.let { center ->
                lookUp(geocoder, text, center.first, center.second).filter { anywhere || isNear(it, center) }
            }.orEmpty()
            return if (nearby.isEmpty() && anywhere) lookUp(geocoder, text, null, null) else nearby
        }
        val results = withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000) {
                try {
                    search(place)
                } catch (e: IOException) {
                    AppLog.w("Address lookup failed: ${e.message}")
                    null
                }
            }
        } ?: return Result.Unavailable
        AppLog.i("Address lookup for \"$place\": ${describe(results)}")
        judge(place, results).takeIf { it != Result.NotFound }?.let { return it }

        // Nothing at that number: look for the street alone, so the user can be asked about it.
        val (number, street) = HOUSE_NUMBER.find(place)?.destructured ?: return Result.NotFound
        val streetResults = withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000) {
                try {
                    search(street)
                } catch (e: IOException) {
                    null
                }
            }
        }.orEmpty()
        AppLog.i("Address lookup for the street \"$street\": ${describe(streetResults)}")
        // The number wasn't found, so this can only be a question ("Did you mean 956 Elmwood Drive?").
        return when (val streetOnly = judge(place, streetResults.map { it.copy(houseNumber = null) })) {
            is Result.Found -> Result.Found(streetOnly.address.copy(houseNumber = number), sure = false)
            else -> Result.NotFound
        }
    }

    private fun describe(results: List<Address>) =
        if (results.isEmpty()) "nothing" else results.joinToString("; ") { it.query.ifBlank { "(no street)" } }

    @Suppress("DEPRECATION") // The callback version needs Android 13.
    private fun lookUp(geocoder: Geocoder, place: String, lat: Double?, lng: Double?): List<Address> {
        val found = if (lat != null && lng != null) {
            geocoder.getFromLocationName(place, 5, lat - NEARBY_DEGREES, lng - NEARBY_DEGREES, lat + NEARBY_DEGREES, lng + NEARBY_DEGREES)
        } else {
            geocoder.getFromLocationName(place, 5)
        }
        return found.orEmpty().map { a ->
            Address(
                a.subThoroughfare, a.thoroughfare, a.locality, a.adminArea,
                latitude = if (a.hasLatitude()) a.latitude else null,
                longitude = if (a.hasLongitude()) a.longitude else null,
            )
        }
    }

    private fun isNear(address: Address, near: Pair<Double, Double>): Boolean {
        val lat = address.latitude ?: return false
        val lng = address.longitude ?: return false
        return kilometers(lat, lng, near.first, near.second) <= FAR_KM
    }

    /** True when words follow the street name ("3140 East Oakland Street Springfield") or a comma does. */
    fun namesCity(place: String): Boolean {
        val rest = HOUSE_NUMBER.find(place)?.destructured?.component2() ?: place
        return ',' in rest || streetPart(rest).length < rest.trim().trimEnd('.').length
    }

    /** Great-circle distance. */
    internal fun kilometers(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 6371.0 * 2 * Math.asin(Math.sqrt(a))
    }

    /** Picks the best of [results] for what was said ([place] starts with the house number). */
    fun judge(place: String, results: List<Address>): Result {
        val (number, rest) = HOUSE_NUMBER.find(place)?.destructured ?: return Result.NotFound
        val spokenStreet = streetPart(rest)
        val scored = results.filter { it.street != null }.map { address ->
            val sameNumber = address.houseNumber.equals(number, ignoreCase = true)
            Triple(address, sameNumber, similarity(normalize(spokenStreet), normalize(address.street!!)))
        }
        val best = scored.maxWithOrNull(compareBy({ (_, sameNumber, score) -> sameNumber && score >= SAME_STREET }, { it.third }))
            ?: return Result.NotFound
        val (address, sameNumber, score) = best
        return when {
            sameNumber && score >= SAME_STREET -> Result.Found(address, sure = true)
            (sameNumber && score >= SIMILAR_STREET) || score >= SAME_STREET -> Result.Found(address, sure = false)
            else -> Result.NotFound
        }
    }

    /** "East Oakland Street Springfield" → "East Oakland Street": a city said after the street isn't compared. */
    private fun streetPart(text: String): String {
        val words = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val last = words.indexOfLast { it.lowercase().trim('.') in PhoneActions.STREET_WORDS }
        return if (last >= 0) words.take(last + 1).joinToString(" ") else text
    }

    private val ABBREVIATIONS = mapOf(
        "n" to "north", "s" to "south", "e" to "east", "w" to "west",
        "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
        "st" to "street", "rd" to "road", "ave" to "avenue", "dr" to "drive", "ln" to "lane",
        "blvd" to "boulevard", "ct" to "court", "pl" to "place", "pkwy" to "parkway",
        "hwy" to "highway", "cir" to "circle",
    )

    /** "E Oakland St." and "East Oakland Street" both become "eastoaklandstreet". */
    private fun normalize(street: String) = street.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }
        .joinToString("") { ABBREVIATIONS[it] ?: it }

    /** 1 for the same text, falling toward 0 as more letters differ. */
    private fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return 1f - editDistance(a, b).toFloat() / maxOf(a.length, b.length)
    }

    /** Letters to add, remove or change to turn [a] into [b]. */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    // A misheard street ("Eastoak Lind" for "East Oakland") still scores about 0.9; a different street is far lower.
    private const val SAME_STREET = 0.75f
    private const val SIMILAR_STREET = 0.5f

    // About 75 km around the phone or home.
    private const val NEARBY_DEGREES = 0.7
    private const val FAR_KM = 150.0
}

/**
 * The answer to "Did you mean …?": a yes or a no, said any of the usual ways, or anything else
 * (a corrected address, the missing city), which is used as new information. A yes or no
 * followed by more than courtesy ("no, it's on Elm") counts as new information.
 */
object ConfirmAnswer {
    enum class Kind { YES, NO, OTHER }

    private val LEAD = setOf("um", "uh", "oh", "well", "hey", "hmm")
    private val YES_STARTS = listOf(
        "yes", "yeah", "yep", "yup", "correct", "right", "sure", "ok", "okay", "exactly", "perfect", "absolutely",
        "that's right", "thats right", "that's it", "thats it", "that's the one", "thats the one", "that one",
        "sounds good", "go ahead", "do it", "you got it",
    ).map { it.split(' ') }.sortedByDescending { it.size }
    private val NO_STARTS = listOf(
        "no", "nope", "nah", "wrong", "incorrect", "not that", "not it", "that's not it", "thats not it",
        "that's wrong", "thats wrong", "not that one",
    ).map { it.split(' ') }.sortedByDescending { it.size }

    // What may follow a yes or no without adding anything: "yes please", "no thank you", "yeah that's it".
    private val COURTESY = setOf(
        "please", "thanks", "thank", "you", "it", "is", "that", "that's", "thats", "one", "the", "go", "ahead",
        "do", "right", "correct", "exactly", "sir", "yes", "yeah", "no", "nope",
    )

    fun classify(answer: String): Kind {
        val words = answer.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }.dropWhile { it in LEAD }
        fun startsWith(phrases: List<List<String>>) = phrases.firstOrNull { words.take(it.size) == it }
        val yes = startsWith(YES_STARTS)
        val no = startsWith(NO_STARTS)
        // "No" phrases are checked first where both could match ("that's not it" vs "that's it").
        val (kind, start) = when {
            no != null -> Kind.NO to no
            yes != null -> Kind.YES to yes
            else -> return Kind.OTHER
        }
        return if (words.drop(start.size).all { it in COURTESY }) kind else Kind.OTHER
    }
}
