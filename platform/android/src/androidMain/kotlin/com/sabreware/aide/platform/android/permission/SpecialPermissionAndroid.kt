package com.sabreware.aide.platform.android.permission

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri
import com.sabreware.aide.core.domain.permission.SpecialPermission

/** Android granted-check for a [SpecialPermission] (app-op / Settings-page grant). */
fun SpecialPermission.androidIsGranted(): Boolean = when (this) {
    SpecialPermission.ALL_FILES_ACCESS -> Environment.isExternalStorageManager()
}

/** Per-app Settings page where the user toggles this access on. */
fun SpecialPermission.settingsIntent(context: Context): Intent = when (this) {
    SpecialPermission.ALL_FILES_ACCESS -> Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${context.packageName}".toUri(),
    )
}
