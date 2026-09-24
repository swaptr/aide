package com.sabreware.aide.core.designsystem.form

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Behavior lock for the form validation primitive: [Rules], [FieldRule], and [validate]. */
class FieldRuleTest {

    @Test
    fun required_failsOnBlank() {
        val rule = Rules.required("Name can't be empty.")
        assertEquals("Name can't be empty.", rule.check(""))
        assertEquals("Name can't be empty.", rule.check("   "))
        assertNull(rule.check("ok"))
    }

    @Test
    fun mustContain_checksToken() {
        val rule = Rules.mustContain("{text}", "Must contain {text}.")
        assertEquals("Must contain {text}.", rule.check("no placeholder"))
        assertNull(rule.check("Translate {text} please"))
    }

    @Test
    fun validate_returnsFirstFailure() {
        val rules = listOf(
            Rules.required("Required."),
            Rules.mustContain("{text}", "Needs {text}."),
        )
        // Blank fails the required rule first.
        assertEquals("Required.", rules.validate(""))
        // Non-blank but missing token fails the second rule.
        assertEquals("Needs {text}.", rules.validate("hello"))
        // All pass.
        assertNull(rules.validate("hello {text}"))
    }
}
