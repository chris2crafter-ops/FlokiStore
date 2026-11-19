package io.github.chris2crafter.thorstore

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import io.github.chris2crafter.thorstore.core.CatalogApp

/** Asks once for "install unknown apps"; returns true if already allowed. */
fun Activity.ensureInstallPermission(): Boolean {
    if (packageManager.canRequestPackageInstalls()) return true
    AlertDialog.Builder(this)
        .setTitle("Installation erlauben")
        .setMessage("Damit der Floki Store Apps installieren kann, musst du ihm einmal erlauben, unbekannte Apps zu installieren. Danach einfach nochmal auf Installieren drücken.")
        .setPositiveButton("Einstellungen öffnen") { _, _ ->
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        .setNegativeButton("Abbrechen", null)
        .show()
    return false
}

/** Drivers go to /Driver in the storage root, which needs "all files access". */
fun Activity.ensureStorageAccess(): Boolean {
    if (Store.hasStorageAccess()) return true
    AlertDialog.Builder(this)
        .setTitle("Speicherzugriff erlauben")
        .setMessage("Treiber werden in den Ordner /Driver im Hauptverzeichnis gespeichert. Dafür braucht der Floki Store den Zugriff auf alle Dateien.")
        .setPositiveButton("Einstellungen öffnen") { _, _ -> openStorageSettings() }
        .setNegativeButton("Abbrechen", null)
        .show()
    return false
}

fun Activity.openStorageSettings() {
    try {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

fun Activity.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_SHORT).show()
    }
}

fun Activity.launch(app: CatalogApp) {
    val pkg = app.packageName ?: return
    val i = packageManager.getLaunchIntentForPackage(pkg) ?: packageManager.getLeanbackLaunchIntentForPackage(pkg)
    if (i == null) Toast.makeText(this, "${app.name} hat keine Startseite", Toast.LENGTH_SHORT).show() else startActivity(i)
}

fun Activity.uninstall(app: CatalogApp) {
    val pkg = app.packageName ?: return
    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
}

fun Activity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

/** "2026-10-08T23:07:15Z" -> "08.10.2026" */
fun formatDate(iso: String?): String? {
    if (iso == null || iso.length < 10) return null
    return "${iso.substring(8, 10)}.${iso.substring(5, 7)}.${iso.substring(0, 4)}"
}

/** Text of the main button for [app]: install, update, open, progress. */
fun primaryLabel(app: CatalogApp): String {
    val inst = Store.installed(app)
    val rel = Store.release(app)
    val p = Store.progress[app.id]
    return when {
        app.isDriver -> "Versionen anzeigen"
        app.isLinkOnly -> "Download-Seite öffnen"
        p != null -> p.text
        inst != null && Store.hasUpdate(app) -> "Aktualisieren"
        inst != null -> "Öffnen"
        rel == null && Store.isFetching(app) -> "Lade Infos…"
        rel != null && Store.asset(app) == null -> "Kein APK gefunden"
        else -> "Installieren"
    }
}

/**
 * Install, update or open [app]. Cases that need a choice (no matching APK, drivers) open
 * the full page instead.
 */
fun Activity.primaryAction(app: CatalogApp) {
    if (app.isLinkOnly) { openUrl(app.homepage!!); return }
    if (Store.progress[app.id] != null) return
    val inst = Store.installed(app)
    if (!app.isDriver && inst != null && !Store.hasUpdate(app)) { launch(app); return }
    if (app.isDriver || (Store.release(app) != null && Store.asset(app) == null)) { openPage(app); return }
    if (!ensureInstallPermission()) return
    DownloadService.install(this, app)
}

fun Activity.openPage(app: CatalogApp) {
    val cls = if (app.isDriver) DriverActivity::class.java else DetailActivity::class.java
    startActivity(Intent(this, cls).putExtra(DetailActivity.EXTRA_APP, app.id))
}
