package com.sabreware.aide.feature.tasks.data

// Keep small and general; voice flavours go in user-added groups, not here.
object BuiltInGroups {
    const val TONE = "builtin.tone"
    const val CORRECTION = "builtin.correction"
    const val COMPREHENSION = "builtin.comprehension"
    const val TRANSLATE = "builtin.translate"

    val seeds: List<TaskGroupEntity> = listOf(
        seed(
            id = TONE,
            name = "Tone",
            description = "Rewrite the same idea in a different voice: formal, casual, friendly, " +
                "persuasive, shorter, longer.",
            sortOrder = 10,
            iconName = "ic_lc_mic_vocal",
        ),
        seed(
            id = CORRECTION,
            name = "Correction",
            description = "Fix grammar, spelling, and awkward phrasing without rewriting or changing " +
                "the meaning.",
            sortOrder = 20,
            iconName = "ic_lc_spell_check",
        ),
        seed(
            id = COMPREHENSION,
            name = "Comprehension",
            description = "Make sense of text you're reading: summarize, bullet, extract key points, " +
                "suggest replies.",
            sortOrder = 30,
            iconName = "ic_lc_book_open",
        ),
        seed(
            id = TRANSLATE,
            name = "Translate",
            description = "Translate into 45+ languages with natural phrasing and tone preserved.",
            sortOrder = 40,
            iconName = "ic_lc_languages",
        ),
    )

    private fun seed(
        id: String,
        name: String,
        description: String?,
        sortOrder: Int,
        iconName: String? = null,
    ): TaskGroupEntity {
        val now = 0L  // sentinel: actual createdAt/updatedAt stamped at insertion time
        return TaskGroupEntity(
            id = id,
            name = name,
            description = description,
            iconName = iconName,
            isBuiltIn = true,
            sortOrder = sortOrder,
            isHidden = false,
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
    }
}
