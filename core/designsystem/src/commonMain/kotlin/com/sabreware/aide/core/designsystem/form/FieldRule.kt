package com.sabreware.aide.core.designsystem.form

/**
 * A single-field validation rule. Returns an error message when [value] is invalid, or null when it
 * passes. Pure Kotlin (no Compose/Android deps) so rules are trivially unit-testable and reusable
 * across forms — see [Rules] for the common ones, including the custom `{text}` placeholder check.
 */
fun interface FieldRule {
    fun check(value: String): String?
}

/** First failing rule's message, or null if every rule passes. */
fun List<FieldRule>.validate(value: String): String? = firstNotNullOfOrNull { it.check(value) }

object Rules {
    /** Value must not be blank. */
    fun required(message: String) = FieldRule { if (it.isBlank()) message else null }

    /** Value must contain [token] (e.g. the `{text}` placeholder in a prompt template). */
    fun mustContain(token: String, message: String) =
        FieldRule { if (!it.contains(token)) message else null }
}
