package com.sabreware.aide.app.platform

import android.content.Context
import com.sabreware.aide.app.R
import com.sabreware.aide.core.domain.licenses.AboutLibrariesJson

class AndroidAboutLibrariesJson(private val context: Context) : AboutLibrariesJson {
    override suspend fun load(): String =
        context.resources.openRawResource(R.raw.aboutlibraries)
            .bufferedReader()
            .use { it.readText() }
}
