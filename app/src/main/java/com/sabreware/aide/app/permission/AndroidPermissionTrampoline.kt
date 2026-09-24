package com.sabreware.aide.app.permission

import android.content.Context
import android.content.Intent
import com.sabreware.aide.platform.android.permission.PermissionTrampoline

/** Points the [PermissionTrampoline] port at the activity that actually draws the dialogs. */
class AndroidPermissionTrampoline(context: Context) : PermissionTrampoline {

    private val appContext = context.applicationContext

    override fun intent(): Intent = Intent(appContext, RuntimePermissionActivity::class.java)
}
