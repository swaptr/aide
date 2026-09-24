package com.sabreware.aide.aisdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why generation stopped — both normalized and verbatim.
 *
 * Keeping [raw] alongside [unified] is deliberate. A normalized reason is what logic should branch on;
 * the vendor's own string is what a bug report needs, and collapsing to the enum alone throws away the
 * only evidence of an unmapped value.
 */
@Serializable
public data class FinishReason(
    val unified: Unified,
    val raw: String? = null,
) {

    @Serializable
    public enum class Unified {
        /** The model emitted a stop sequence or ended its turn. */
        @SerialName("stop")
        Stop,

        /** The output token limit was reached. */
        @SerialName("length")
        Length,

        /** A content filter stopped generation. */
        @SerialName("content-filter")
        ContentFilter,

        /** The model requested tool calls. */
        @SerialName("tool-calls")
        ToolCalls,

        /** Generation stopped because of an error. */
        @SerialName("error")
        Error,

        /** Anything else, including a vendor reason with no mapping. */
        @SerialName("other")
        Other,
    }
}
