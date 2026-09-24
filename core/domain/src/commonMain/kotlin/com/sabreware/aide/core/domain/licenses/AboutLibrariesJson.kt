package com.sabreware.aide.core.domain.licenses

fun interface AboutLibrariesJson {
    suspend fun load(): String
}
