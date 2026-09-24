package com.sabreware.aide.core.domain.model

import com.sabreware.aide.core.common.prefs.boolKey

/**
 * Model preferences that are settings rather than choices. Which model the user picked lives in the
 * [ModelSelection] document ([ModelSelectionStore]); sampler overrides and imported models are documents
 * too ([ModelDocuments]).
 */
object ModelPrefs {
    /** Master reasoning/thinking switch. Default on; when off, capable models skip the thinking request. */
    val ReasoningEnabled = boolKey("reasoning_enabled", default = true)
}
