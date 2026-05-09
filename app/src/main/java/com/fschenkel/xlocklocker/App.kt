package com.fschenkel.xlocklocker

import android.app.Application
import android.content.Context

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val next = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrash(throwable)
            next?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CRASH, sw.toString()).commit()
        } catch (_: Exception) {}
    }

    companion object {
        const val CRASH_PREFS = "crash_report"
        const val KEY_CRASH = "last_crash"
    }
}
