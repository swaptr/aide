package com.sabreware.aide.feature.tasks.domain

// One group → one chip; multi-member chips show `▾` and open the side panel on tap.
data class GroupChipItem(
    val group: TaskGroup,
    val members: List<Task>,
    val mostRecentlyUsedAt: Long?,
)
