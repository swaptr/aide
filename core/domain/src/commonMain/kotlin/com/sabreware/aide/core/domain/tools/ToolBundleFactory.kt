package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.domain.llm.Surface
import com.sabreware.aide.core.domain.model.ProviderId

/**
 * Builds the per-session [ToolBundle] from the toolsets the running application contributed. The
 * implementation ([com.sabreware.aide.data.tools.ToolBundleFactoryImpl]) is shared: which tools exist on a
 * target is a property of that target's module graph, not of a per-platform factory.
 */
interface ToolBundleFactory {
    fun build(
        providerId: ProviderId,
        supportsTools: Boolean,
        enabledGated: Set<ToolGate>,
        enabledCategories: Set<ToolCategory>,
        surface: Surface = Surface.CHAT,
    ): ToolBundle
}
