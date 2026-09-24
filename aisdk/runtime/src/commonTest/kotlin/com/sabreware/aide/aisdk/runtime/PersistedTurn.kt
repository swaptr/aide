package com.sabreware.aide.aisdk.runtime

import com.sabreware.aide.aisdk.ModelMessage
import kotlinx.serialization.json.Json

/**
 * What a stored conversation goes through between two sessions.
 *
 * AIDE persists `RunResult.messages` and rebuilds the prompt from it on the next launch, so a signature
 * that survives in memory but not through the serializer fails at exactly the boundary the acceptance
 * gate names — the second turn of a rebound session — and nowhere earlier.
 */
internal object PersistedTurn {

    private val json = Json { ignoreUnknownKeys = true }

    fun roundTrip(messages: List<ModelMessage>): List<ModelMessage> =
        json.decodeFromString(json.encodeToString(messages))
}
