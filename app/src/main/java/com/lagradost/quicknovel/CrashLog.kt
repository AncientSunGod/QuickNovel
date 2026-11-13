package com.lagradost.quicknovel

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash diagnostics. Reports never leave the device unless the user
 * explicitly shares the generated text file.
 */
internal object CrashLog {
    private const val DIRECTORY = "crash-logs"
    private const val LATEST_FILE = "latest-crash.txt"
    private const val MAX_LOGS = 5

    @Volatile
    private var installed = false

    @Volatile
    private var runtimeContext = "No active reader context"

    fun updateContext(description: String) {
        runtimeContext = description.take(500)
    }

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(appContext, "FATAL CRASH", thread, error, replace = true)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun recordNonFatal(context: Context, label: String, error: Throwable) {
        runCatching {
            write(context.applicationContext, label, Thread.currentThread(), error, replace = false)
        }
    }

    fun latestReport(context: Context): File? =
        File(File(context.applicationContext.filesDir, DIRECTORY), LATEST_FILE)
            .takeIf { it.isFile && it.length() > 0L }

    private fun write(
        context: Context,
        label: String,
        thread: Thread,
        error: Throwable,
        replace: Boolean,
    ) {
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return

        val target = File(directory, LATEST_FILE)
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val report = buildString {
            appendLine("QuickNovel diagnostic report")
            appendLine("Type: $label")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("App ID: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine("Reader context: $runtimeContext")
            appendLine()
            append(writer.toString())
            appendLine()
        }

        synchronized(this) {
            if (replace && target.exists()) {
                for (index in MAX_LOGS - 1 downTo 1) {
                    val from = File(directory, "crash-$index.txt")
                    val to = File(directory, "crash-${index + 1}.txt")
                    if (from.exists()) from.renameTo(to)
                }
                target.renameTo(File(directory, "crash-1.txt"))
            }
            target.appendText(report)
            directory.listFiles()
                ?.filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_LOGS - 1)
                ?.forEach { it.delete() }
        }
    }
}
