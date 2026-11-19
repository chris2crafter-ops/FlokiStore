package io.github.chris2crafter.thorstore

import android.app.Activity
import android.app.AlertDialog
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.Sources

/**
 * Floki Store updates itself from its own GitHub releases (Store.SELF_REPO). It is an entry in
 * the catalog like any other app, so install, signature check and notifications work the same;
 * this only adds the check at start and the dialog.
 */
object SelfUpdate {
    private var checkedThisStart = false

    fun app(): CatalogApp? = Store.app(Store.SELF_ID)

    /** Once per start of the app: ask GitHub for a newer Floki Store and offer it. */
    fun checkOnStart(a: Activity) {
        if (checkedThisStart) return
        checkedThisStart = true
        check(a, quiet = true)
    }

    /** [quiet]: say nothing when there is no update (start); otherwise report the result. */
    fun check(a: Activity, quiet: Boolean = false) {
        val self = app() ?: return
        if (!quiet) a.toast("Suche nach einer neuen Floki-Store-Version…")
        Store.io.execute {
            Store.fetch(self, maxAge = 0)
            val now = app() ?: return@execute
            val update = Store.hasUpdate(now)
            val notes = if (update) Store.release(now)?.tag?.let { tag -> runCatching { Sources.notes(Store.client, now, tag) }.getOrNull() } else null
            Store.main.post {
                if (a.isFinishing || a.isDestroyed) return@post
                when {
                    update -> offer(a, now, notes)
                    quiet -> {}
                    Store.errors[now.id] != null -> a.toast("Konnte GitHub nicht erreichen: ${Store.errors[now.id]}")
                    else -> a.toast("Floki Store ist aktuell (${Store.installed(now)?.versionName})")
                }
            }
        }
    }

    private fun offer(a: Activity, self: CatalogApp, notes: String?) {
        val tag = Store.release(self)?.tag ?: return
        val installed = Store.installed(self)?.versionName ?: "?"
        AlertDialog.Builder(a)
            .setTitle("Floki Store $tag ist da")
            .setMessage("Installiert ist $installed.\n\n" + (notes?.trim()?.take(1500) ?: "Neue Version auf GitHub."))
            .setPositiveButton("Jetzt aktualisieren") { _, _ ->
                if (!a.ensureInstallPermission()) return@setPositiveButton
                DownloadService.install(a, self)
                a.toast("Update wird geladen. Der Store startet danach neu.")
            }
            .setNegativeButton("Später", null)
            .setNeutralButton("Überspringen") { _, _ -> Store.ignoreCurrent(self) }
            .show()
    }
}
