package com.sabreware.aide.core.domain.tools

import com.sabreware.aide.core.common.prefs.PreferenceStore
import com.sabreware.aide.core.common.prefs.stringKey
import com.sabreware.aide.core.common.prefs.stringSetKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Tool-gating preferences. Key names and defaults match what `UserPreferencesRepository` stored. */
object ToolPrefs {
    /** Empty by default: every category is opt-in (and may still need a runtime permission). */
    val EnabledCategories = stringSetKey("enabled_tool_categories")

    /** The IME bypasses this gate — it has no dialog host. */
    val AskBeforeEachTool = com.sabreware.aide.core.common.prefs.boolKey("ask_before_each_tool", default = false)

    /** Tool names the user marked "always allow", so the confirm gate stops asking. */
    val AlwaysAllowed = stringSetKey("always_allowed_tools")

    /** Tool names the user marked "never allow" — the dispatcher blocks them outright. */
    val Denied = stringSetKey("denied_tools")

    /** Registered filesystem roots, as JSON so the schema can evolve without a DataStore migration. */
    val FsRootsJson = stringKey("fs_roots_json", default = "[]")
}

/**
 * The enabled categories. Stored as the open [ToolCategory.id], so a category whose toolset is not installed
 * on this target simply never matches anything — no constant to fall off an enum, nothing to throw.
 */
fun PreferenceStore.enabledToolCategories(): Flow<Set<ToolCategory>> =
    flow(ToolPrefs.EnabledCategories).map { ids -> ids.mapTo(mutableSetOf(), ::ToolCategory) }

/** Toggle one category. Read-modify-write in a single edit, so two toggles cannot lose each other. */
suspend fun PreferenceStore.setToolCategoryEnabled(category: ToolCategory, enabled: Boolean) {
    update(ToolPrefs.EnabledCategories) { current ->
        if (enabled) current + category.id else current - category.id
    }
}
