package dz.island.a79

import android.util.Log

/**
 * Logcat only. LSPosed's own log file is harder to tail while measuring.
 *
 * Debug builds log unconditionally: waiting for the verbose switch is the slowest possible way
 * to find out that a scene never fired.
 */
object Logx {
    private const val TAG = "A79Island"

    private fun loud(): Boolean = BuildConfig.DEBUG || Config.debugEnabled()

    fun d(msg: String) {
        if (loud()) Log.d(TAG, msg)
    }

    fun w(msg: String) = Log.w(TAG, msg)

    fun e(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
}
