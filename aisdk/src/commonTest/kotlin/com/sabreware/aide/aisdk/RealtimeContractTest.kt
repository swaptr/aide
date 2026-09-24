package com.sabreware.aide.aisdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The realtime contract's optional members default to "absent", so every model written against the
 * turn-based, client-secret shape still describes exactly that shape without saying anything.
 */
class RealtimeContractTest {

    /** The minimum a model had to implement before the extension — and still does. */
    private class LegacyRealtimeModel : RealtimeModel {
        override val provider: String = "vendor"
        override val modelId: String = "rt-1"
        override suspend fun doCreateClientSecret(options: RealtimeClientSecretOptions): RealtimeClientSecret =
            RealtimeClientSecret(token = "t", url = "wss://vendor.example/rt")

        override fun webSocketConfig(token: String, url: String): RealtimeConnection = RealtimeConnection(url)
        override fun parseServerEvent(raw: JsonElement): List<RealtimeServerEvent> = emptyList()
        override fun serializeClientEvent(event: RealtimeClientEvent): JsonElement = JsonNull
        override fun buildSessionConfig(config: RealtimeSessionConfig): JsonElement = JsonObject(emptyMap())
    }

    @Test
    fun `a legacy model declares nothing and offers no server socket or per-connection parser`() = runTest {
        val model = LegacyRealtimeModel()

        assertNull(model.capabilities)
        assertNull(model.serverWebSocketConfig())
        assertNull(model.createServerEventParser())
        assertNull(model.healthCheckResponse(JsonNull))
    }

    @Test
    fun `client event ids are optional and errors correlate to them`() {
        val update = RealtimeClientEvent.SessionUpdate(RealtimeSessionConfig())
        val append = RealtimeClientEvent.InputAudioAppend("AAAA", eventId = "evt-2")
        val error = RealtimeServerEvent.Error(raw = JsonNull, message = "rejected", clientEventId = "evt-2")

        assertNull(update.eventId)
        assertEquals("evt-2", append.eventId)
        assertEquals("evt-2", error.clientEventId)
        assertNull(RealtimeServerEvent.Error(raw = JsonNull, message = "rejected").clientEventId)
    }

    @Test
    fun `a capability declaration only ever adds to the legacy shape`() {
        val capabilities = RealtimeCapabilities(
            conversation = RealtimeCapabilities.Conversation.Continuous,
            transports = listOf(RealtimeCapabilities.Transport.WebSocket),
        )

        assertNull(capabilities.connections)
        assertNull(capabilities.startup)
        assertNull(capabilities.finalization)
    }
}
