package com.sabreware.aide.core.domain.model

/**
 * A provider's runtime identity: the routing key every registry, catalog, engine and model card is indexed
 * by. Open, string-backed, never a closed enum.
 *
 * Two kinds of id share it. The constants below are the providers that ship inside the app — the on-device
 * engines, one of each. Every cloud provider is a user CONNECTION, and its id is the connection's id
 * ([com.sabreware.aide.core.domain.connection.Connection.id], e.g. `"openai-k3f9x2"`): two accounts of one
 * vendor are two providers. The vendor itself is a [com.sabreware.aide.core.domain.connection.VendorId].
 * Branch on per-provider traits via [ProviderDescriptor], never on identity beyond an equality check.
 */
@JvmInline
value class ProviderId(val value: String) {
    companion object {
        const val LOCAL_KEY = "local"
        const val SHERPA_KEY = "sherpa"
        const val ANDROID_SYSTEM_KEY = "android-system"

        /** On-device LiteRT-LM (chat). */
        val LOCAL = ProviderId(LOCAL_KEY)

        /** Sherpa-ONNX on-device speech (STT/TTS/VAD). */
        val SHERPA = ProviderId(SHERPA_KEY)

        /** Android system speech engines (no downloadable weights). */
        val ANDROID_SYSTEM = ProviderId(ANDROID_SYSTEM_KEY)
    }
}
