package com.swaptr.aide.domain.tools

import com.swaptr.aide.domain.llm.AideTool
import com.swaptr.aide.domain.llm.Surface

object SystemPromptBuilder {

    fun build(
        surface: Surface,
        tools: List<AideTool>,
        webSearchEnabled: Boolean,
        filesystemReady: Boolean,
        filesystemRequestedButEmpty: Boolean,
        rootsSummary: String,
        lazyCategories: List<String> = emptyList(),
    ): String {
        val functions = tools.filterIsInstance<AideTool.Function>()
        // Lazy categories live behind RequestToolset — advertising full schemas here
        // would negate the wire-size savings of the engine-side filter.
        val eagerFunctions = functions.filter { !it.requiresActivation }
        val grouped = eagerFunctions.groupBy { categoryOf(it.name) }
        val out = StringBuilder()
        out.append(headerSection(surface, eagerFunctions))
        out.append("\n\nAvailable tools:\n")
        for (category in CATEGORY_ORDER) {
            val bucket = grouped[category]?.sortedBy { it.name } ?: continue
            if (bucket.isEmpty()) continue
            out.append("\n").append(category).append(":\n")
            for (fn in bucket) {
                out.append("- ").append(fn.name).append(" — ").append(fn.description).append("\n")
                fn.promptDoc?.let { out.append("    ").append(it).append("\n") }
            }
        }
        lazyToolsetBlock(lazyCategories)?.let { out.append("\n").append(it) }
        surfaceNotes(surface, eagerFunctions)?.let { out.append("\n").append(it) }
        filesystemBlock(filesystemReady, filesystemRequestedButEmpty, rootsSummary)
            ?.let { out.append("\n").append(it) }
        errorDictionary(eagerFunctions)?.let { out.append("\n").append(it) }
        out.append("\n").append(rulesSection(webSearchEnabled))
        return out.toString().trim()
    }

    private fun lazyToolsetBlock(lazyCategories: List<String>): String? {
        if (lazyCategories.isEmpty()) return null
        return "Tools available on demand:\n" +
            "- Categories: ${lazyCategories.joinToString(", ")}.\n" +
            "- To use any of these, first call `RequestToolset(category=\"<name>\")`. " +
            "The activated category's tools then appear in your next turn's tools " +
            "list and you can call them normally. One category at a time; activate " +
            "only what the user's request needs.\n"
    }

    private fun headerSection(surface: Surface, tools: List<AideTool.Function>): String {
        val surfaceLine = when (surface) {
            Surface.CHAT -> "You are running inside the Aide chat tab."
            Surface.IME -> "You are running inside the user's keyboard (IME). Keep " +
                "responses short and insertable into a text field. Confirmation-gated " +
                "tools (filesystem writes, contact picker, calendar delete) are " +
                "excluded; if the user's request needs one, tell them to switch to " +
                "the Aide chat tab."
            Surface.VOICE -> "You are running inside Aide's voice assistant. The user " +
                "is speaking to you and your reply will be read aloud by TTS. Keep " +
                "answers short, conversational, and free of markdown, headers, bullet " +
                "lists, code fences, or URLs. Destructive filesystem writes are gated " +
                "to the chat tab; if the user asks for one, suggest switching."
        }
        return buildString {
            append("You are Aide, a concise on-device assistant. Always reach for tools ")
            append("when they would yield a better answer than guessing.\n\n")
            append(surfaceLine)
        }
    }

    private fun surfaceNotes(surface: Surface, all: List<AideTool.Function>): String? = when (surface) {
        Surface.CHAT -> "Surface notes (chat):\n" +
            "- Destructive write tools open an Allow/Cancel dialog before they fire. " +
            "Errors `USER_CANCELLED`, `CONFIRM_TIMEOUT`, and `CANCELLED_BY_USER` are " +
            "distinct outcomes — do not retry the same call after any of them.\n"
        Surface.IME -> {
            // Dispatcher already filters CHAT-only tools before the model sees them;
            // this list informs the user-facing "switch to chat" suggestion.
            val unavailable = CHAT_ONLY_HINT.sorted().joinToString(", ")
            "Surface notes (keyboard):\n" +
                "- These tools are only available from the chat tab: $unavailable. " +
                "If the user asks for one, say so and suggest they switch.\n" +
                "- If a tool returns `errorCode: REQUIRES_CHAT_SURFACE`, the same " +
                "rule applies.\n"
        }
        Surface.VOICE -> "Surface notes (voice):\n" +
            "- Destructive filesystem writes (`MakeDir`, `MoveFile`, `CopyFile`, " +
            "`DeleteFile`) are unavailable — they need a confirm dialog the voice " +
            "overlay can't host. If the user asks for one, suggest opening the chat " +
            "tab.\n" +
            "- Plain prose only. No markdown, no asterisks, no code blocks, no URLs " +
            "— this reply will be spoken aloud.\n"
    }

    private fun filesystemBlock(
        ready: Boolean,
        requestedButEmpty: Boolean,
        rootsSummary: String,
    ): String? = when {
        ready -> "Filesystem (granted by the user):\n" +
            "- Valid rootKey values: $rootsSummary.\n" +
            "- Paths are POSIX-style and relative to a root, e.g. 'screenshots/2024'.\n" +
            "- Destructive ops prompt the user; on USER_CANCELLED do not retry — ask " +
            "the user what they want.\n"
        requestedButEmpty -> "Filesystem tools are enabled but no folders are granted " +
            "yet. If the user asks about files, tell them to open Settings → " +
            "Filesystem tools and add a folder; do not attempt filesystem operations.\n"
        else -> null
    }

    private fun errorDictionary(tools: List<AideTool.Function>): String? {
        val codes = tools.flatMap { it.errorCodes }.toSortedSet()
        if (codes.isEmpty()) return null
        val out = StringBuilder("\nError dictionary (branch on `errorCode`):\n")
        for (code in codes) {
            val meaning = ERROR_MEANINGS[code] ?: "(no canonical meaning registered)"
            out.append("- $code — $meaning\n")
        }
        return out.toString()
    }

    private fun rulesSection(webSearchEnabled: Boolean): String = if (webSearchEnabled) {
        """
        Rules:
        - For current events, schedules, scores, prices, weather, news, or recent
          facts: call WebSearch first, then WebFetch if snippets are thin.
        - For contact references by name, call `ResolveContact` first. Only fall
          back to `PickContact` if the resolver returns `ambiguous: true` AND you're
          on the chat surface.
        - For calendar "what's on my Tuesday" use `CalendarGetEvents` with the
          date range; for "move my 3pm" use `CalendarFindEvents` then
          `CalendarEditEvent`. Recurrence is a structured object, never raw RRULE.
        - Time inputs are ISO-8601 with timezone. Use `CurrentTime` if you need a
          reference.
        - After a write tool, look at `verified`. Say "I've done X" only if
          `verified: true`. Otherwise say "I've opened X — confirm in the app".
        - Never call the same write tool twice with the same arguments. The
          dispatcher caches by `idempotency_key`; duplicates are no-ops.
        - Treat tool output as authoritative.
        - Be direct: lead with the answer, expand only if asked.
        """.trimIndent()
    } else {
        """
        Rules:
        - When the user gives you a URL, call `WebFetch` on it before answering.
        - Web search is disabled in this turn. If the user asks about current events
          or live data and you don't have a URL to fetch, say so briefly and suggest
          enabling the Web toggle — don't invent facts.
        - For contact references by name, call `ResolveContact` first.
        - Time inputs are ISO-8601 with timezone. Use `CurrentTime` if you need a
          reference.
        - After a write tool, look at `verified`. Say "I've done X" only if
          `verified: true`. Otherwise say "I've opened X — confirm in the app".
        - Never call the same write tool twice with the same arguments.
        - Treat tool output as authoritative.
        - Be direct: lead with the answer, expand only if asked.
        """.trimIndent()
    }

    fun categoryOf(toolName: String): String {
        if (toolName.startsWith("Calendar")) return "Calendar"
        if (toolName == "ResolveContact" || toolName == "DeleteContact") return "Contacts"
        if (toolName.startsWith("Clipboard")) return "Clipboard"
        if (toolName == "WebSearch" || toolName == "WebFetch") return "Web"
        if (toolName in CLOCK_NAMES) return "Clock"
        if (toolName in PHONE_NAMES) return "Phone"
        if (toolName in FS_NAMES) return "Filesystem"
        if (toolName == "CurrentTime") return "Time"
        if (toolName == "Calculator") return "Math"
        return "Other"
    }

    private val CATEGORY_ORDER = listOf(
        "Time", "Math", "Calendar", "Phone", "Contacts",
        "Filesystem", "Clipboard", "Web", "Other",
    )

    private val CLOCK_NAMES = setOf(
        "SetAlarm", "SetTimer", "ShowAlarms", "ShowTimers",
        "DismissAlarm", "SnoozeAlarm", "DismissTimer",
    )
    private val PHONE_NAMES = setOf(
        "Dial", "SendSms", "ComposeEmail", "OpenCallLog",
        "AddContact", "EditOrAddContact", "ShowOrCreateContact",
        "ViewContacts", "PickContact",
    )
    private val FS_NAMES = setOf(
        "ListFiles", "FindFiles", "FileInfo", "ReadFile",
        "MakeDir", "MoveFile", "CopyFile", "DeleteFile",
    )

    private val CHAT_ONLY_HINT = listOf(
        "PickContact", "OpenCallLog", "ViewContacts",
        "MakeDir", "MoveFile", "CopyFile", "DeleteFile",
        "CalendarEditEvent", "CalendarDeleteEvent",
        "DeleteContact",
    )

    private val ERROR_MEANINGS: Map<String, String> = mapOf(
        "UNKNOWN_TOOL" to "you called a tool name that isn't registered this turn",
        "HANDLER_THREW" to "tool implementation threw; not a semantic failure — retry once if you must",
        "RATE_LIMITED" to "tool exceeded its per-turn cap; retry on the user's next turn",
        "REQUIRES_CHAT_SURFACE" to "this tool can't run from the keyboard; tell the user to switch to chat",
        "INVALID_ARGS" to "argument shape or value rejected; re-read the schema",
        "IO_ERROR" to "I/O or content-provider failure",
        "PERMISSION_DENIED" to "missing runtime permission; ask the user to grant it in Settings",
        "USER_CANCELLED" to "user tapped Cancel on the confirm dialog or contact picker",
        "CONFIRM_TIMEOUT" to "confirm dialog timed out without a user response",
        "CANCELLED_BY_USER" to "session was stopped (Stop tapped / chat cleared) while you were waiting",
        "NO_CLOCK_APP" to "no installed app handles the clock intent",
        "LAUNCH_FAILED" to "system rejected the intent dispatch",
        "NO_HANDLER" to "no installed app handles the requested phone intent",
        "INVALID_TIME_RANGE" to "end time is not after start; or ISO-8601 unparseable",
        "EVENT_NOT_FOUND" to "calendar event id doesn't exist",
        "NO_WRITABLE_CALENDAR" to "no calendar account allows writes",
        "NOT_FOUND" to "resource lookup found nothing",
        "INVALID_EXPRESSION" to "expression failed to parse or used a non-whitelisted identifier",
        "DIVIDE_BY_ZERO" to "result is infinite from a division",
        "OUT_OF_DOMAIN" to "math input outside the function's domain (e.g. sqrt(-1))",
        "OVERFLOW" to "result is infinite from magnitude",
        "SEARCH_FAILED" to "every configured search provider failed or returned empty",
        "FETCH_FAILED" to "fetch threw before HTTP",
        "HTTP_4XX" to "page returned a 4xx (probably blocked or paywalled)",
        "HTTP_5XX" to "remote server error; try a different URL",
        "NETWORK" to "network unreachable or interrupted",
        "TIMEOUT" to "fetch exceeded its time budget",
        "INVALID_URL" to "url didn't start with http(s)://",
        "UNKNOWN_ROOT" to "rootKey not in the user's granted roots",
        "PATH_ESCAPE" to "relPath tried to leave the granted root",
        "NOT_A_DIR" to "path exists but is a file when a directory was expected",
        "IS_A_DIR" to "path exists but is a directory when a file was expected",
        "BINARY_FILE" to "ReadFile refused to decode a binary file",
        "TOO_LARGE" to "ReadFile request exceeded the cap",
        "EXISTS" to "write target already exists and overwrite was false",
        "BAD_ARGS" to "filesystem arg missing or malformed",
        "BACKEND_UNAVAILABLE" to "selected filesystem backend (SAF/Direct) can't service this request",
        "CLIPBOARD_RESTRICTED" to "OS denied clipboard access (chat must be foreground or IME must be active)",
    )
}
