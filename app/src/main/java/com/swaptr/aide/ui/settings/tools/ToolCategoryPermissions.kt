package com.swaptr.aide.ui.settings.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.swaptr.aide.data.prefs.ToolCategory

sealed class CategoryRequirement {
    data object None : CategoryRequirement()
    data class Runtime(val perms: List<String>) : CategoryRequirement()
}

fun ToolCategory.requirement(): CategoryRequirement = when (this) {
    ToolCategory.CALENDAR -> CategoryRequirement.Runtime(
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
    )
    ToolCategory.CONTACTS -> CategoryRequirement.Runtime(
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
    )
    ToolCategory.TIME,
    ToolCategory.MATH,
    ToolCategory.CLOCK,
    ToolCategory.PHONE,
    ToolCategory.CLIPBOARD,
    ToolCategory.WEB,
    ToolCategory.FILESYSTEM,
    -> CategoryRequirement.None
}

fun Context.isCategoryGranted(req: CategoryRequirement): Boolean = when (req) {
    CategoryRequirement.None -> true
    is CategoryRequirement.Runtime -> req.perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
