package io.github.chris2crafter.thorstore

import io.github.chris2crafter.thorstore.core.t
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the stack trace of the last crash (and of errors the store caught itself), so it can be
 * shown and copied on the next start. There is no other way to see it without a computer.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, "Absturz im Thread ${thread.name}", e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** For errors that were caught but should not have happened. */
    fun record(ctx: Context, where: String, e: Throwable) {
        runCatching { write(ctx.applicationContext, where, e) }
    }

    private fun write(ctx: Context, where: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        file(ctx).writeText("Floki Store $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n$where\n\n$sw")
    }

    /** Shows the saved report once, with a button to copy it. */
    fun showIfAny(a: Activity) {
        val f = file(a)
        if (!f.exists()) return
        val report = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (report.isBlank()) return
        AlertDialog.Builder(a)
            .setTitle(t("Fehlerbericht", "Error report"))
            .setMessage(t("Beim letzten Mal ist im Floki Store ein Fehler aufgetreten. Kopiere den Bericht und schick ihn weiter, dann lässt sich die Ursache genau finden.\n\n", "Last time, an error occurred in Floki Store. Copy the report and pass it on so the cause can be found.\n\n") + report.take(1800))
            .setPositiveButton(t("Kopieren", "Copy")) { _, _ ->
                a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Floki Store Fehlerbericht", report))
                a.toast(t("Bericht kopiert", "Report copied"))
            }
            .setNegativeButton(t("Schließen", "Close"), null)
            .show()
    }
}
