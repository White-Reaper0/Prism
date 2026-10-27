package com.nevcortex.prism.core.util

import android.util.Log

/**
 * Thin wrapper around android.util.Log so the rest of the app uses one tag and can later be
 * redirected to a file sink for session diagnostics. Not used for high-rate sensor paths —
 * those must not allocate per sample.
 */
object Logger {
    private const val TAG = "Prism"

    fun v(tag: String = TAG, msg: String) = Log.v(tag, msg)
    fun d(tag: String = TAG, msg: String) = Log.d(tag, msg)
    fun i(tag: String = TAG, msg: String) = Log.i(tag, msg)
    fun w(tag: String = TAG, msg: String, t: Throwable? = null) =
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    fun e(tag: String = TAG, msg: String, t: Throwable? = null) =
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
}
