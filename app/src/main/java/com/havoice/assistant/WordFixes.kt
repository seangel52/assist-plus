package com.havoice.assistant

/**
 * The owner's own corrections for words the speech model keeps getting wrong, written one per
 * line as "heard => meant" (for example "nest cafe => Nescafe"). Whole words only,
 * ignoring capitals.
 */
object WordFixes {
    private val SEPARATOR = Regex("\\s*(=>|->)\\s*")

    fun apply(text: String, rules: String): String =
        rules.lines().fold(text) { current, line ->
            val parts = line.split(SEPARATOR, limit = 2)
            val heard = parts.getOrNull(0)?.trim().orEmpty()
            val meant = parts.getOrNull(1)?.trim().orEmpty()
            if (parts.size < 2 || heard.isEmpty()) {
                current
            } else {
                Regex("(?<![\\w'])" + Regex.escape(heard) + "(?![\\w'])", RegexOption.IGNORE_CASE)
                    .replace(current, Regex.escapeReplacement(meant))
            }
        }
}
