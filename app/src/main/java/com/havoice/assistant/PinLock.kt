package com.havoice.assistant

import android.app.KeyguardManager
import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A spoken PIN that guards Assist while the phone is locked. It is separate from the phone's
 * own lock PIN (which apps can't see or use) and only gates this app; the phone stays locked.
 * Only a salted hash is stored.
 */
object PinLock {
    private const val MAX_TRIES = 3
    private const val LOCKOUT_MILLIS = 5 * 60_000L

    sealed interface Result {
        data object Correct : Result
        data class Wrong(val triesLeft: Int) : Result
        data class LockedOut(val minutes: Int) : Result
        data object NoDigits : Result
    }

    fun isSet(settings: HaSettings) = settings.pinHash.isNotEmpty()

    /** Whether the next request needs the PIN: the feature is on, a PIN is set, and the phone is locked. */
    fun required(context: Context, settings: HaSettings): Boolean =
        settings.pinRequiredWhenLocked && isSet(settings) &&
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun setPin(settings: HaSettings, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        settings.pinSalt = salt
        settings.pinHash = hash(salt, pin)
        settings.pinFailures = 0
        settings.pinLockedUntil = 0
    }

    fun clear(settings: HaSettings) {
        settings.pinHash = ""
        settings.pinSalt = ""
    }

    /** Checks a typed or spoken attempt ("one two three four", "1 2 3 4", "for to won ate"…). */
    fun check(settings: HaSettings, attempt: String): Result {
        val now = System.currentTimeMillis()
        if (settings.pinLockedUntil > now) {
            return Result.LockedOut(((settings.pinLockedUntil - now) / 60_000L + 1).toInt())
        }
        val digits = digitsOf(attempt)
        if (digits.isEmpty()) return Result.NoDigits
        if (MessageDigest.isEqual(hash(settings.pinSalt, digits).toByteArray(), settings.pinHash.toByteArray())) {
            settings.pinFailures = 0
            return Result.Correct
        }
        val failures = settings.pinFailures + 1
        if (failures >= MAX_TRIES) {
            settings.pinFailures = 0
            settings.pinLockedUntil = now + LOCKOUT_MILLIS
            AppLog.w("Spoken PIN: $MAX_TRIES wrong attempts, locked for ${LOCKOUT_MILLIS / 60_000} minutes")
            return Result.LockedOut((LOCKOUT_MILLIS / 60_000L).toInt())
        }
        settings.pinFailures = failures
        AppLog.w("Spoken PIN: wrong attempt ($failures of $MAX_TRIES)")
        return Result.Wrong(MAX_TRIES - failures)
    }

    /** The digits in what was heard; number words and common mishearings count as digits. */
    fun digitsOf(text: String): String {
        val words = mapOf(
            "zero" to '0', "oh" to '0', "o" to '0',
            "one" to '1', "won" to '1',
            "two" to '2', "to" to '2', "too" to '2',
            "three" to '3', "tree" to '3',
            "four" to '4', "for" to '4', "fore" to '4',
            "five" to '5', "six" to '6', "seven" to '7',
            "eight" to '8', "ate" to '8', "nine" to '9', "niner" to '9',
        )
        val out = StringBuilder()
        for (token in text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }) {
            when {
                token.all { it.isDigit() } -> out.append(token)
                token in words -> out.append(words.getValue(token))
            }
        }
        return out.toString()
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
