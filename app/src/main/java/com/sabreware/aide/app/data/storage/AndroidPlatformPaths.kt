package com.sabreware.aide.app.data.storage

import com.sabreware.aide.core.common.storage.PlatformPaths

import android.content.Context
import okio.Path
import okio.Path.Companion.toPath

/** Android [PlatformPaths] — the app-private `filesDir` / `cacheDir` from the `Context`, as okio paths. */
class AndroidPlatformPaths(context: Context) : PlatformPaths {
    private val app = context.applicationContext
    override val filesDir: Path = app.filesDir.absolutePath.toPath()
    override val cacheDir: Path = app.cacheDir.absolutePath.toPath()
}
