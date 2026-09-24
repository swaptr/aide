package com.sabreware.aide.core.domain.tools

/**
 * What a tool is *about* — the heading it appears under in the prompt, the row it gets in Settings, and the
 * key `RequestToolset` activates.
 *
 * An open string id, not a closed enum, for the reason [com.sabreware.aide.core.domain.model.ProviderId] is:
 * a new toolset should be new files plus one binding, and a closed enum makes it an edit to a central type
 * that every `when` over it then has to follow. The constants below are the categories that ship today; a
 * [Toolset] is free to declare its own.
 *
 * The id is also the persisted token (see [enabledToolCategories]) and the label the model reads, so it is
 * human-readable rather than SCREAMING_CASE.
 */
@JvmInline
value class ToolCategory(val id: String) {
    override fun toString(): String = id

    companion object {
        val Time = ToolCategory("Time")
        val Math = ToolCategory("Math")
        val Clock = ToolCategory("Clock")
        val Calendar = ToolCategory("Calendar")
        val Phone = ToolCategory("Phone")
        val Contacts = ToolCategory("Contacts")
        val Clipboard = ToolCategory("Clipboard")
        val Device = ToolCategory("Device")
        val Web = ToolCategory("Web")
        val Filesystem = ToolCategory("Filesystem")
        val Image = ToolCategory("Image")

        /**
         * Cross-cutting tools that belong to no category (the `RequestToolset` meta-tool, MCP tools). Never
         * hidden by the category filter — a user who has not enabled "Other" has not made a choice about it.
         */
        val Other = ToolCategory("Other")
    }
}
