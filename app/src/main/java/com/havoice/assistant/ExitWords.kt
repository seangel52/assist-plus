package com.havoice.assistant

/**
 * Recognizes when what was said only means "stop" or "we're done", so Assist can close
 * without sending it to Home Assistant. Every word must be a stop word or filler: anything
 * with real content ("cancel my plans", "Stu cancelled game night", "stop the music") is a
 * normal request and goes to the agent.
 */
object ExitWords {
    enum class Kind {
        /** Not a stop phrase: handle normally. */
        NONE,

        /** "cancel", "never mind", "stop", "exit": close right away. */
        CANCEL,

        /** "no thank you", "that's all", "goodbye": let the agent sign off, then close. */
        GOODBYE,
    }

    private val CANCEL_WORDS = setOf("cancel", "nevermind", "stop", "exit", "quit", "close", "forget", "dismiss")
    private val GOODBYE_WORDS = setOf("bye", "goodbye", "done", "thanks", "thank", "all", "finished")
    private val FILLER = setOf(
        "hey", "ok", "okay", "um", "uh", "hmm", "so", "just", "actually", "please", "i", "dont", "don't", "know",
        "the", "request", "that", "it", "this", "assist", "assistant", "now", "for", "no", "you", "thats", "that's",
        "is", "im", "i'm", "am", "all", "good", "right", "alright", "oh", "well", "yeah", "nope", "and", "then",
        "we're", "were", "we", "are", "out", "of", "here", "listening", "go", "away", "anymore", "everything",
    )

    fun classify(text: String, wakePhrases: List<WakePhrase>): Kind {
        val words = words(text, wakePhrases)
        if (words.isEmpty() || words.size > 8) return Kind.NONE
        // A lone word gets sound-alike matching: "hansel" and "can sell" are a misheard "cancel".
        val cancelLike = { w: String -> w in CANCEL_WORDS || (words.size <= 2 && soundsLikeCancelWord(w)) }
        if (words.any { it !in FILLER && it !in GOODBYE_WORDS && !cancelLike(it) }) return Kind.NONE
        return when {
            words.any(cancelLike) -> Kind.CANCEL
            words.any { it in GOODBYE_WORDS } -> Kind.GOODBYE
            else -> Kind.NONE
        }
    }

    /**
     * Whether [text] is short enough that it may be nothing but a stop word the recognizer got
     * wrong, so it's worth checking the audio itself ([PhoneListener] does that).
     */
    fun maybeStopWord(text: String, wakePhrases: List<WakePhrase>): Boolean {
        val words = words(text, wakePhrases)
        return words.size == 1 || (words.size in 2..3 && words.count { it !in FILLER } <= 1)
    }

    /** Short stop phrases for the keyword spotter to listen for in a short request's audio. */
    val SPOKEN_STOP_PHRASES = listOf("cancel", "cancel that", "never mind", "stop", "exit", "forget it")

    private fun words(text: String, wakePhrases: List<WakePhrase>): List<String> {
        val wakeWords = wakePhrases.flatMap { it.text.lowercase().split(' ') }.toSet()
        return text.lowercase()
            .replace("never mind", "nevermind")
            .replace("can sell", "cancel")
            .split(Regex("[^a-z']+"))
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() && it !in wakeWords }
    }

    private fun soundsLikeCancelWord(word: String): Boolean =
        listOf("cancel", "nevermind", "exit").any { target ->
            editDistance(word, target) <= (if (target.length >= 5) 2 else 1)
        } || word.endsWith("ansel") || word.endsWith("ancel")

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

    // The agent marks a reply that ends the conversation with "[end]" (see the README's prompt).
    private val END_TAG = Regex("\\s*\\[\\s*end\\s*\\]\\s*", RegexOption.IGNORE_CASE)

    /** The reply without the agent's end tag, and whether it had one. */
    fun stripEndTag(reply: String): Pair<String, Boolean> =
        if (END_TAG.containsMatchIn(reply)) END_TAG.replace(reply, " ").trim() to true else reply to false
}
