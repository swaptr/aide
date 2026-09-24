package com.sabreware.aide.feature.tasks.data

// Templates end with `Text:\n{text}\n\nResult:` so the model emits only the transformed
// output (no restated input or "Sure, here's..." preamble).
object BuiltInTasks {

    private fun template(instructions: String): String =
        "$instructions Keep the response short and to the point. Unless the " +
            "instruction above explicitly asks for a list or multiple items, " +
            "return a single answer — do not offer alternatives, variants, or " +
            "\"option A / option B\" choices. Output only the result, with no " +
            "preamble, no quotation marks, no commentary, and no follow-up " +
            "questions.\n\nText:\n{text}\n\nResult:"

    val seeds: List<TaskEntity> = listOf(
        seed(
            id = "builtin.rephrase",
            name = "Rephrase",
            description = "Reword the text while keeping the meaning.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rephrase the following text in a natural, fluent way.",
        ),
        seed(
            id = "builtin.shorter",
            name = "Shorter",
            description = "Tighten the text without losing meaning.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text to be significantly shorter while keeping the core meaning.",
        ),
        seed(
            id = "builtin.longer",
            name = "Longer",
            description = "Expand the text with relevant detail.",
            groupId = BuiltInGroups.TONE,
            instructions = "Expand the following text with helpful context and supporting detail, keeping the original intent.",
        ),
        seed(
            id = "builtin.simplify",
            name = "Simplify",
            description = "Plain English. No jargon.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text in plain English. Remove jargon. Use short sentences a 12-year-old could understand.",
        ),
        seed(
            id = "builtin.humanize",
            name = "Humanize",
            description = "Make AI-sounding text feel natural.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text so it reads as if a human wrote it. Vary sentence length, drop filler, and use natural phrasing.",
        ),
        seed(
            id = "builtin.tone.formal",
            name = "Formal",
            description = "Shift to a formal, professional tone.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text in a formal, professional tone.",
        ),
        seed(
            id = "builtin.tone.casual",
            name = "Casual",
            description = "Shift to a relaxed, casual tone.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text in a relaxed, casual, conversational tone.",
        ),
        seed(
            id = "builtin.tone.friendly",
            name = "Friendly",
            description = "Warm and friendly tone.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text in a warm, friendly tone.",
        ),
        seed(
            id = "builtin.tone.assertive",
            name = "Assertive",
            description = "Direct and confident tone.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text in a direct, confident, assertive tone without sounding aggressive.",
        ),
        seed(
            id = "builtin.tone.apologetic",
            name = "Apologetic",
            description = "Soft, apologetic tone.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text in a sincere, apologetic tone.",
        ),
        seed(
            id = "builtin.tone.persuasive",
            name = "Persuasive",
            description = "Make the message more convincing.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text to be more persuasive and convincing. Lead with the strongest point and tighten weak phrasing, but stay honest — do not invent facts.",
        ),
        seed(
            id = "builtin.tone.empathetic",
            name = "Empathetic",
            description = "Acknowledge feelings; soften the delivery.",
            groupId = BuiltInGroups.TONE,
            instructions = "Rewrite the following text in an empathetic, understanding tone that acknowledges the reader's feelings and context.",
        ),

        seed(
            id = "builtin.fix_grammar",
            name = "Fix Grammar",
            description = "Correct grammar, spelling, punctuation.",
            groupId = BuiltInGroups.CORRECTION,
            instructions = "Correct any grammar, spelling, and punctuation errors in the following text. Preserve the original meaning and style.",
        ),
        seed(
            id = "builtin.polish",
            name = "Polish",
            description = "Light edit: grammar plus small flow tweaks.",
            groupId = BuiltInGroups.CORRECTION,
            instructions = "Polish the following text. Fix grammar, spelling, and punctuation, and smooth out awkward phrasing. Keep the author's voice and every idea — do not rewrite, do not add or remove content.",
        ),

        seed(
            id = "builtin.summarize",
            name = "Summarize",
            description = "Short summary capturing key points.",
            groupId = BuiltInGroups.COMPREHENSION,
            instructions = "Write a concise summary of the following text in 1-2 sentences.",
        ),
        seed(
            id = "builtin.bullets",
            name = "To Bullets",
            description = "Convert prose into bullet points.",
            groupId = BuiltInGroups.COMPREHENSION,
            instructions = "Convert the following text into a short bulleted list. Each bullet captures one distinct point.",
        ),
        seed(
            id = "builtin.key_points",
            name = "Key Points",
            description = "Extract action items / key takeaways.",
            groupId = BuiltInGroups.COMPREHENSION,
            instructions = "Extract the key points and action items from the following text as a numbered list.",
        ),
        seed(
            id = "builtin.to_table",
            name = "To Table",
            description = "Pull structured rows out of prose.",
            groupId = BuiltInGroups.COMPREHENSION,
            instructions = "Extract structured information from the following text and present it as a Markdown table. Pick column headers that match the data; if the text doesn't have tabular content, say so in one line instead of forcing a table.",
        ),
        seed(
            id = "builtin.continue",
            name = "Continue",
            description = "Continue writing from where the text ends.",
            groupId = BuiltInGroups.COMPREHENSION,
            instructions = "Continue writing the following text in the same voice and style. Pick up where it leaves off.",
        ),
        seed(
            id = "builtin.reply_3",
            name = "3 Replies",
            description = "Suggest three short replies.",
            groupId = BuiltInGroups.COMPREHENSION,
            instructions = "Suggest three short, distinct replies to the following message. Number them 1, 2, 3.",
        ),

        // Curated pretrained-tier roster aligned with Gemini's documented language support.
    ) + translationSeeds(
        // Alphabetical by English name. Ids are ISO 639-1 (with region for zh).
        "ar" to "Arabic",
        "bn" to "Bengali",
        "bg" to "Bulgarian",
        "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
        "hr" to "Croatian",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "en" to "English",
        "et" to "Estonian",
        "fi" to "Finnish",
        "fr" to "French",
        "de" to "German",
        "el" to "Greek",
        "gu" to "Gujarati",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "kn" to "Kannada",
        "ko" to "Korean",
        "lv" to "Latvian",
        "lt" to "Lithuanian",
        "ml" to "Malayalam",
        "mr" to "Marathi",
        "no" to "Norwegian",
        "fa" to "Persian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sr" to "Serbian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "es" to "Spanish",
        "sw" to "Swahili",
        "sv" to "Swedish",
        "ta" to "Tamil",
        "te" to "Telugu",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "vi" to "Vietnamese",
    )

    private fun translationSeeds(vararg langs: Pair<String, String>): List<TaskEntity> =
        langs.map { (code, name) ->
            seed(
                id = "builtin.translate.$code",
                name = name,
                description = "Translate into $name.",
                groupId = BuiltInGroups.TRANSLATE,
                instructions = "Translate the following text into natural, fluent $name. Preserve tone.",
            )
        }

    private fun seed(
        id: String,
        name: String,
        description: String,
        groupId: String,
        instructions: String,
    ): TaskEntity {
        val now = 0L  // sentinel: actual createdAt/updatedAt stamped at insertion time
        return TaskEntity(
            id = id,
            name = name,
            description = description,
            promptTemplate = template(instructions),
            groupId = groupId,
            isBuiltIn = true,
            isHidden = false,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
    }
}
