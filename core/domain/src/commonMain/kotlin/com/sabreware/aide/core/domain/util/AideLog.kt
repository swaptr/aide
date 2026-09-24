package com.sabreware.aide.core.domain.util

/**
 * Domain-pure logging facade so domain code never imports `android.util.Log`. The app installs an
 * android-backed [Backend] at startup ([com.sabreware.aide.app.AideApp.onCreate]); before that (e.g. JVM unit
 * tests) calls are a no-op.
 */
object AideLog {

    fun interface Backend {
        fun log(priority: Int, tag: String, message: String, throwable: Throwable?)
    }

    // Match android.util.Log priority ints so the android backend maps 1:1.
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @Volatile
    var backend: Backend? = null

    fun d(tag: String, message: String) {
        backend?.log(DEBUG, tag, message, null)
    }

    fun i(tag: String, message: String) {
        backend?.log(INFO, tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        backend?.log(WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        backend?.log(ERROR, tag, message, throwable)
    }
}
