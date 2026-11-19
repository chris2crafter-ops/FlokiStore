package io.github.chris2crafter.thorstore

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.chris2crafter.thorstore.core.CatalogApp
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Installs APKs through a PackageInstaller session and waits for the outcome. */
object Installer {
    class Result(val ok: Boolean, val message: String?)

    private val waiting = ConcurrentHashMap<Int, ArrayBlockingQueue<Result>>()

    fun installBlocking(ctx: Context, apk: File, app: CatalogApp): Result {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) {
            // lets Android skip the prompt for updates of apps this store installed
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sid = pi.createSession(params)
        val q = ArrayBlockingQueue<Result>(1)
        waiting[sid] = q
        try {
            pi.openSession(sid).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    s.fsync(out)
                }
                val intent = Intent(ctx, InstallReceiver::class.java)
                    .setAction(ACTION)
                    .putExtra(EXTRA_APP, app.id)
                    .putExtra(EXTRA_SESSION, sid)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, sid, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            waiting.remove(sid)
            runCatching { pi.abandonSession(sid) }
            return Result(false, e.message)
        }
        return q.poll(15, TimeUnit.MINUTES) ?: Result(false, "Keine Bestätigung erhalten").also { waiting.remove(sid) }
    }

    internal fun deliver(sid: Int, r: Result) {
        waiting.remove(sid)?.offer(r)
    }

    const val ACTION = "io.github.chris2crafter.thorstore.INSTALL_STATUS"
    const val EXTRA_APP = "app"
    const val EXTRA_SESSION = "session"

    /**
     * Turns the installer's status and its raw message ("INSTALL_FAILED_…") into a German
     * explanation and records conflicts the detail page can offer a fix for.
     */
    fun explain(app: CatalogApp?, status: Int, msg: String?): String {
        val m = msg ?: ""
        fun other(): String {
            val pkg = Regex("(?:already used by|declared by|defined by|by package) ([\\w.]+)").find(m)?.groupValues?.get(1)
            if (app != null && pkg != null) Store.blockers[app.id] = pkg
            return pkg?.let { p -> Store.catalog.firstOrNull { it.packageName == p }?.name?.let { "$it ($p)" } ?: p } ?: "einer anderen App"
        }
        val text = when {
            "UPDATE_INCOMPATIBLE" in m || "signatures do not match" in m.lowercase() || "INCONSISTENT_CERTIFICATES" in m -> {
                app?.let { Store.conflicts[it.id] = Store.Conflict.SIGNATURE }
                "Andere Signatur: Die installierte Version stammt aus einer anderen Quelle (z. B. Play Store oder ein anderer Build). Android kann sie nur ersetzen, wenn sie vorher deinstalliert wird."
            }
            "VERSION_DOWNGRADE" in m -> {
                app?.let { Store.conflicts[it.id] = Store.Conflict.DOWNGRADE }
                "Installiert ist schon eine neuere Version. Für ein Downgrade muss die App erst deinstalliert werden."
            }
            "CONFLICTING_PROVIDER" in m || "DUPLICATE_PERMISSION" in m -> {
                app?.let { Store.conflicts[it.id] = Store.Conflict.PROVIDER }
                "Kollidiert mit ${other()}: beide Apps belegen denselben Systemnamen (Provider oder Berechtigung). Eine von beiden muss weg – oder nimm eine Variante, die parallel laufen darf."
            }
            "NO_MATCHING_ABIS" in m -> "Dieses APK ist nicht für den 64-Bit-Prozessor des Thor gebaut."
            "OLDER_SDK" in m || "DEPRECATED_SDK" in m -> "Die App ist für eine Android-Version gebaut, die der Thor nicht unterstützt."
            "INSUFFICIENT_STORAGE" in m -> "Zu wenig Speicherplatz."
            else -> statusText(status, null) + (msg?.takeIf { it.isNotBlank() }?.let { "\n($it)" } ?: "")
        }
        return text
    }

    fun statusText(status: Int, msg: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Abgebrochen"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blockiert"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Konflikt mit einer installierten App"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Nicht kompatibel mit diesem Gerät"
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK ungültig"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Zu wenig Speicherplatz"
        else -> msg ?: "Installation fehlgeschlagen"
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sid = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, intent.getIntExtra(Installer.EXTRA_SESSION, -1))
        val app = Store.app(intent.getStringExtra(Installer.EXTRA_APP))
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val front = Store.foreground
                if (front != null) {
                    runCatching { front.startActivity(confirm) }.onFailure { Notifications.confirmInstall(ctx, app, confirm) }
                } else {
                    // apps in the background may not open activities: ask via notification
                    Notifications.confirmInstall(ctx, app, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.deliver(sid, Installer.Result(true, null))
            else -> Installer.deliver(sid, Installer.Result(false, Installer.explain(app, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))))
        }
    }
}
