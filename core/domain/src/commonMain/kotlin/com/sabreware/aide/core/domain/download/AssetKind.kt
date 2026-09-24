package com.sabreware.aide.core.domain.download

/**
 * Namespaces a download id by asset family so an LLM "foo" and a speech "foo" never collide on the flat
 * scheduler/work key. Open strings rather than an enum, for the same reason
 * [com.sabreware.aide.core.domain.model.ProviderId] is: a new downloadable capability should not require
 * editing a central type.
 */
object AssetKind {
    const val MODEL = "model"
    const val SPEECH = "speech"
}
