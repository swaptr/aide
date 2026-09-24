package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.AideTool
import com.sabreware.aide.core.domain.llm.ToolActivationState

/** Assembled set of tools for one chat session, produced by [ToolBundleFactory]. */
data class ToolBundle(
    val tools: List<AideTool>,
    val systemPrompt: String?,
    val activationState: ToolActivationState,
)
