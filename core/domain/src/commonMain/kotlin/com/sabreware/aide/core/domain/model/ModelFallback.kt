package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.common.prefs.enumKey

/**
 * What to do when the model the user chose cannot be used right now (weights deleted, provider removed or
 * offline, model delisted). The user's CHOICE is never rewritten by any of these — a reroute answers one
 * request with another model and the chosen one is used again the moment it is back.
 */
enum class ModelFallback {
    /** Never substitute: say which model is unavailable and let the user choose. The private default. */
    Never,

    /** Substitute a model of the same kind only: on-device for on-device, cloud for cloud. */
    SameKind,

    /** Substitute any usable model, on-device or cloud. */
    AnyModel,
}

/** User intent, so [com.sabreware.aide.core.common.prefs.Tier.Settings]. Default [ModelFallback.Never]. */
object ModelFallbackPrefs {
    val Policy = enumKey("model_fallback_policy", default = ModelFallback.Never)
}
