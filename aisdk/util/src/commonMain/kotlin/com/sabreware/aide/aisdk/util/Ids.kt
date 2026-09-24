package com.sabreware.aide.aisdk.util

import kotlin.random.Random

/**
 * Generates the ids that correlate streaming blocks.
 *
 * Deliberately not a UUID: this needs no cryptographic strength and no dependency, it only has to be
 * unique within one response. A prefix keeps a log readable — `msg_`, `txt_`, `reason_` — which is worth
 * more here than global uniqueness.
 *
 * [random] is injectable so tests can pin the output; production takes the default.
 */
public class IdGenerator(
    private val prefix: String = "",
    private val length: Int = DEFAULT_LENGTH,
    private val random: Random = Random.Default,
) {

    /** A fresh id: the prefix plus [length] random characters. */
    public fun next(): String {
        // Deliberately not buildString: inside its lambda the receiver is a StringBuilder, whose own
        // `length` member shadows this class's constructor property. `repeat(length)` there silently
        // means "repeat by however many characters have been appended so far" — which produced ids of
        // prefix.length * 2 and looked plausible enough to ship.
        val body = CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return prefix + body.concatToString()
    }

    private companion object {
        // No look-alike characters: an id read aloud off a bug report should not be ambiguous.
        const val ALPHABET = "abcdefghijkmnopqrstuvwxyz0123456789"
        const val DEFAULT_LENGTH = 16
    }
}
