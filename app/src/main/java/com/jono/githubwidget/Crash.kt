package com.jono.githubwidget

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the last error so the setup screen can show it (and you can copy it to Claude). */
object Crash {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_error.txt")

    fun record(ctx: Context, e: Throwable, fatal: Boolean) {
        try {
            val time = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
            val build = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
            val kind = if (fatal) "Crash" else "Widget error (no crash)"
            file(ctx).writeText("$kind · build $build · $time\n${Log.getStackTraceString(e)}")
        } catch (ignored: Throwable) {
        }
    }

    fun last(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) { file(ctx).delete() }
}

/** Installs the crash recorder before anything else runs. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Crash.record(this, e, fatal = true)
            previous?.uncaughtException(t, e)
        }
    }
}
