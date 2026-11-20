package com.havoice.assistant

/**
 * Settles what was said from more than one reading of the same audio.
 *
 * The recognizer's borderline words flip with small things: where the audio is cut, the noise
 * in it. Read all together, a request keeps the words that lean on their neighbours ("today",
 * not "to day"); read phrase by phrase, a word near the start can come out right where the
 * long reading slipped ("matcha tea" against "Macha T"). Neither way is always the better one,
 * so where the two disagree a third reading, of the noise-reduced audio, has the casting vote.
 */
object Readings {
    private fun plain(word: String) = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    /**
     * The words of [together] (the request read in one pass), except where [byPhrase] (read
     * phrase by phrase) differs and [third] agrees with it and not with [together]. [third] is
     * only asked for when the first two differ.
     */
    fun reconcile(together: List<String>, byPhrase: List<String>, third: () -> List<String>): List<String> {
        val a = together.map(::plain)
        val b = byPhrase.map(::plain)
        if (a == b) return together
        val pairs = matches(a, b)
        val witness by lazy { third().map(::plain).filter { it.isNotEmpty() } }
        val out = mutableListOf<String>()
        var i = 0
        var j = 0
        for ((nextI, nextJ) in pairs + (a.size to b.size)) {
            if (nextI > i || nextJ > j) {
                // A stretch where the readings differ, judged with the agreed word on each side of
                // it, so a short or empty stretch ("pro." said twice) is looked for in the right place.
                val before = listOfNotNull(a.getOrNull(i - 1))
                val after = listOfNotNull(a.getOrNull(nextI))
                val asTogether = before + a.subList(i, nextI) + after
                val asByPhrase = before + b.subList(j, nextJ) + after
                // The same letters written as other words ("today" / "to day", "percent" / "per
                // cent") are one reading, and the long pass spells it the usual way.
                val sameLetters = a.subList(i, nextI).joinToString("") == b.subList(j, nextJ).joinToString("")
                val useByPhrase = !sameLetters && contains(witness, asByPhrase) && !contains(witness, asTogether)
                out += if (useByPhrase) byPhrase.subList(j, nextJ) else together.subList(i, nextI)
            }
            if (nextI < a.size) out += together[nextI]
            i = nextI + 1
            j = nextJ + 1
        }
        return out
    }

    private fun contains(words: List<String>, run: List<String>): Boolean {
        val wanted = run.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return false
        return (0..words.size - wanted.size).any { at -> words.subList(at, at + wanted.size) == wanted }
    }

    /** The longest run of words the two readings share in order, as index pairs. */
    private fun matches(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val length = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                length[i][j] = if (a[i] == b[j]) length[i + 1][j + 1] + 1 else maxOf(length[i + 1][j], length[i][j + 1])
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> pairs += i++ to j++
                length[i + 1][j] >= length[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }
}
