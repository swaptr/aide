package com.sabreware.aide.aisdk.util

import com.sabreware.aide.aisdk.ReasoningEffort
import com.sabreware.aide.aisdk.Warning
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reasoning depth, and the promise `CallOptions.reasoning` makes about it: a provider that cannot honour
 * a level says so with a warning rather than silently substituting one.
 *
 * Every provider hand-rolled a clamp instead and emitted nothing, so `XHigh` became `high`, `Minimal`
 * became `low`, and a model that takes no effort at all dropped it — three different silent lies, each
 * of which presents to the user as a depth control that does nothing.
 */
class ReasoningTest {

    private val fullMap = mapOf(
        ReasoningEffort.Minimal to "minimal",
        ReasoningEffort.Low to "low",
        ReasoningEffort.Medium to "medium",
        ReasoningEffort.High to "high",
    )

    @Test
    fun `a level the model has is passed through with no warning`() {
        val warnings = mutableListOf<Warning>()

        assertEquals("high", mapReasoningToEffort(ReasoningEffort.High, fullMap, warnings))

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a substituted level is reported as a compatibility warning`() {
        val warnings = mutableListOf<Warning>()

        // Substituting a neighbouring level is legitimate. Substituting it in silence is not.
        assertEquals("high", mapReasoningToEffort(ReasoningEffort.XHigh, fullMap + (ReasoningEffort.XHigh to "high"), warnings))

        val warning = warnings.single() as Warning.Compatibility
        assertEquals("reasoning", warning.feature)
        assertContains(warning.details.orEmpty(), "xhigh")
        assertContains(warning.details.orEmpty(), "high")
    }

    @Test
    fun `a level the model does not have is unsupported, not silently dropped`() {
        val warnings = mutableListOf<Warning>()

        // Sonnet 4.5 takes no effort at all on an API where Opus 4.5 does. Dropping it left the caller
        // believing the depth had been applied.
        assertNull(mapReasoningToEffort(ReasoningEffort.XHigh, fullMap, warnings))

        val warning = warnings.single() as Warning.Unsupported
        assertEquals("reasoning", warning.feature)
        assertContains(warning.details.orEmpty(), "xhigh")
    }

    @Test
    fun `every level has a wire name of its own`() {
        // Derived from `name.lowercase()` these collide with spellings we would have invented; the two
        // hyphenated ones are the reason they are written out.
        assertEquals("provider-default", ReasoningEffort.ProviderDefault.wireName)
        assertEquals("xhigh", ReasoningEffort.XHigh.wireName)
        assertEquals("none", ReasoningEffort.None.wireName)
        assertEquals(
            ReasoningEffort.entries.size,
            ReasoningEffort.entries.map { it.wireName }.toSet().size,
        )
    }

    @Test
    fun `only the provider default means send nothing`() {
        // "Do not think" is a request a provider transmits, not the absence of one.
        assertTrue(ReasoningEffort.None.isExplicit)
        assertTrue(!ReasoningEffort.ProviderDefault.isExplicit)
    }

    @Test
    fun `the budget scales with the level rather than being a constant half`() {
        val warnings = mutableListOf<Warning>()
        fun budget(level: ReasoningEffort) =
            mapReasoningToBudget(level, maxOutputTokens = 100_000, maxReasoningBudget = 64_000, warnings = warnings)

        // The defect this replaces: every level produced `maxTokens / 2`, making the five-level control
        // a boolean on every extended-thinking model.
        assertEquals(10_000, budget(ReasoningEffort.Low))
        assertEquals(30_000, budget(ReasoningEffort.Medium))
        assertEquals(60_000, budget(ReasoningEffort.High))
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `the model's ceiling and the vendor's floor both bind`() {
        val warnings = mutableListOf<Warning>()

        // The ceiling is the model's; a request above it is rejected rather than truncated.
        assertEquals(
            8_000,
            mapReasoningToBudget(ReasoningEffort.High, maxOutputTokens = 100_000, maxReasoningBudget = 8_000, warnings = warnings),
        )
        // The floor wins a conflict, because a budget under it is refused by the API rather than small.
        assertEquals(
            DEFAULT_MIN_REASONING_BUDGET,
            mapReasoningToBudget(ReasoningEffort.Minimal, maxOutputTokens = 4_000, maxReasoningBudget = 64_000, warnings = warnings),
        )
    }

    @Test
    fun `a level with no budget percentage is unsupported`() {
        val warnings = mutableListOf<Warning>()

        // `None` is deliberately absent: "do not think" is the vendor's own off switch, never a budget
        // of zero, which several APIs reject outright.
        assertNull(
            mapReasoningToBudget(ReasoningEffort.None, maxOutputTokens = 100_000, maxReasoningBudget = 64_000, warnings = warnings),
        )

        assertTrue(warnings.single() is Warning.Unsupported)
    }

    @Test
    fun `a provider may bring its own curve`() {
        val warnings = mutableListOf<Warning>()

        assertEquals(
            50_000,
            mapReasoningToBudget(
                reasoning = ReasoningEffort.Low,
                maxOutputTokens = 100_000,
                maxReasoningBudget = 64_000,
                warnings = warnings,
                budgetPercentages = mapOf(ReasoningEffort.Low to 0.5),
            ),
        )
    }
}
