package com.sabreware.aide.core.domain.speech

import com.sabreware.aide.core.domain.model.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeechResolutionPolicyTest {

    @Test
    fun `android ladder tries sherpa then terminates on the system engines`() {
        val policy = SpeechResolutionPolicy(listOf(ProviderId.SHERPA, ProviderId.ANDROID_SYSTEM))

        assertEquals(listOf(ProviderId.SHERPA), policy.preferred)
        assertEquals(ProviderId.ANDROID_SYSTEM, policy.terminal)
    }

    @Test
    fun `a single-engine ladder has no preferred step — that engine is the terminal`() {
        val policy = SpeechResolutionPolicy(listOf(ProviderId.SHERPA))

        assertEquals(emptyList(), policy.preferred)
        assertEquals(ProviderId.SHERPA, policy.terminal)
    }

    @Test
    fun `an empty ladder is rejected — resolve must always have something to return`() {
        assertFailsWith<IllegalArgumentException> { SpeechResolutionPolicy(emptyList()) }
    }
}
