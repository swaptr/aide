package com.sabreware.aide.aisdk.providers.media

import com.sabreware.aide.aisdk.BinaryData
import com.sabreware.aide.aisdk.ImageFile
import com.sabreware.aide.aisdk.VideoFile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An input file as most media vendors want it: a URL untouched, inline bytes as a `data:` URI.
 *
 * The shape is the same at fal, Replicate, ByteDance, MiniMax, Alibaba and every OpenAI-compatible
 * image endpoint, which is why it is written once here rather than per vendor — eight copies of four
 * lines is eight places for the one mistake that matters to hide. That mistake is sending a BARE
 * base64 string: several of these APIs accept it silently as a *prompt*, so an edit call runs as a
 * text-to-image generation of a base64 blob and returns a plausible, entirely wrong picture.
 *
 * A vendor that genuinely wants bare base64 (Kling, Black Forest Labs) must not use this — the claim
 * about what a given vendor accepts belongs at the call site, not here.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun ImageFile.toDataUri(): String = when (this) {
    is ImageFile.Url -> url
    is ImageFile.Data -> data.asDataUri(mediaType)
}

/** @see toDataUri — the video-input twin, identical rule. */
@OptIn(ExperimentalEncodingApi::class)
internal fun VideoFile.toDataUri(): String = when (this) {
    is VideoFile.Url -> url
    is VideoFile.Data -> data.asDataUri(mediaType)
}

/** Already-encoded payloads keep their string; raw bytes are encoded once, here. */
@OptIn(ExperimentalEncodingApi::class)
private fun BinaryData.asDataUri(mediaType: String): String {
    val encoded = when (this) {
        is BinaryData.Base64 -> value
        is BinaryData.Bytes -> Base64.encode(value)
    }
    return "data:$mediaType;base64,$encoded"
}
