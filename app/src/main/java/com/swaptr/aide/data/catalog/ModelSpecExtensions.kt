package com.swaptr.aide.data.catalog

// Deprecated flat-flag shims; delete once all call sites migrate to capabilities.

@Deprecated("Use capabilities.visionIn", ReplaceWith("capabilities.visionIn"))
val ModelSpec.supportsVision: Boolean get() = capabilities.visionIn

@Deprecated("Use capabilities.audioIn", ReplaceWith("capabilities.audioIn"))
val ModelSpec.supportsAudio: Boolean get() = capabilities.audioIn

@Deprecated("Use capabilities.toolsLocal", ReplaceWith("capabilities.toolsLocal"))
val ModelSpec.supportsTools: Boolean get() = capabilities.toolsLocal

@Deprecated("Use capabilities.maxOutput", ReplaceWith("capabilities.maxOutput"))
val ModelSpec.maxTokens: Int get() = capabilities.maxOutput
