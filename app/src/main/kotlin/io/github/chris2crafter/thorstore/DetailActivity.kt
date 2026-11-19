package io.github.chris2crafter.thorstore

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.chris2crafter.thorstore.core.ApkPicker
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.ReleaseAsset
import io.github.chris2crafter.thorstore.core.Sources
import io.github.chris2crafter.thorstore.core.Tags
import io.github.chris2crafter.thorstore.core.t

class DetailActivity : Activity() {

    private lateinit var app: CatalogApp
    private lateinit var icon: ImageView
    private lateinit var primary: TextView
    private lateinit var btnFiles: TextView
    private lateinit var btnOlder: TextView
    private lateinit var btnUninstall: TextView
    private lateinit var btnIgnore: TextView
    private lateinit var btnReplace: TextView
    private lateinit var btnBlocker: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var notes: TextView
    private var notesTag: String? = null
    private var wasInstalled: Boolean? = null
    private val listener = { bind() }

    /** On the Thor the lower screen shows description and release notes. */
    private lateinit var dual: DualScreenHost
    private var panel: AppPanel? = null
    private val panelHost = object : AppPanel.Host {
        override fun primary(app: CatalogApp) = primaryAction(app)
        override fun open(app: CatalogApp) {}
        override fun edit(app: CatalogApp) = EditActivity.edit(this@DetailActivity, app)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Store.app(intent.getStringExtra(EXTRA_APP)) ?: run { finish(); return }
        dual = DualScreenHost(this) { ctx -> AppPanel(ctx, full = false, host = panelHost).also { panel = it; it.show(app) }.view }
        val split = dual.create()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
        }

        icon = ImageView(this)
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        app.tags.forEach { chips.addView(chip(Tags.label(it), if (it == "recommended") C.OK else if (it == "dual-screen") C.PURPLE else C.ACCENT), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(app.name, 26f, bold = true))
            addView(text(listOfNotNull(app.repo ?: app.homepage?.substringAfter("://")?.substringBefore('/'), Store.categoryTitle(app.category)).joinToString(" · "), 14f, C.SUB))
            addView(chips, lp().apply { topMargin = dp(8) })
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(head, lp(0, WRAP, 1f).apply { marginStart = dp(18) })
        })

        primary = button(t("Installieren", "Install"), primary = true) { primaryAction() }
        btnFiles = button(t("Andere Datei…", "Other file…")) { chooseFile() }
        btnOlder = button(t("Ältere Version…", "Older version…")) { chooseOlder() }
        btnUninstall = button(t("Deinstallieren", "Uninstall")) { uninstall(app) }
        btnIgnore = button(t("Version ignorieren", "Ignore version")) { Store.ignoreCurrent(app); toast(t("Diese Version wird nicht mehr gemeldet", "This version will no longer be reported")) }
        btnReplace = button(t("Ersetzen…", "Replace…")) { confirmReplace() }
        btnBlocker = button(t("Kollidierende App deinstallieren", "Uninstall conflicting app")) {
            Store.blockers[app.id]?.let { pkg -> startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))) }
        }
        val web = button(if (app.repo != null) t("Auf GitHub", "On GitHub") else t("Webseite", "Website")) { app.sourceUrl?.let { openUrl(it) } }
        val edit = button(t("Bearbeiten", "Edit")) { EditActivity.edit(this, app) }
        val buttons = row(primary, btnReplace, btnBlocker, btnFiles, btnOlder, btnUninstall, btnIgnore, web, edit, spacing = 10)
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(buttons) }, lp().apply { topMargin = dp(20) })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        col.addView(progressBar, lp().apply { topMargin = dp(14) })
        status = text("", 14f, C.SUB)
        col.addView(status, lp().apply { topMargin = dp(6) })

        if (!split) app.shownNote?.let { n ->
            col.addView(text(t("Hinweis: $n", "Note: $n"), 14f, C.WARN).apply {
                background = rounded(0x22F0883E, dp(10).toFloat())
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }, lp().apply { topMargin = dp(12) })
        }

        if (!split) {
            col.addView(text(t("Beschreibung", "Description"), 18f, bold = true), lp().apply { topMargin = dp(18) })
            col.addView(text(app.shownDescription, 15f), lp().apply { topMargin = dp(6) })
        }

        info = text("", 14f, C.SUB).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        col.addView(text(t("Version & Datei", "Version & file"), 18f, bold = true), lp().apply { topMargin = dp(18) })
        col.addView(info, lp().apply { topMargin = dp(6) })

        notes = text(t("Wird geladen…", "Loading…"), 14f, C.SUB).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        if (split) {
            col.addView(text(t("Beschreibung und Release-Notes stehen auf dem unteren Bildschirm.", "Description and release notes are on the lower screen."), 13f, C.SUB), lp().apply { topMargin = dp(18) })
        } else if (!app.isLinkOnly) {
            col.addView(text(t("Release-Notes", "Release notes"), 18f, bold = true), lp().apply { topMargin = dp(18) })
            col.addView(notes.apply {
                background = rounded(C.CARD, dp(10).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                // focusable so the D-pad can scroll through long notes
                isFocusable = true
            }, lp().apply { topMargin = dp(6) })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            addView(col)
        })
        IconLoader.load(icon, app, dp(96))
        primary.requestFocus()
        Store.fetchAsync(app, force = Store.release(app) == null)
    }

    override fun onResume() {
        super.onResume()
        // back from "Bearbeiten": removed, or name/text/tab changed
        val now = Store.app(app.id) ?: run { finish(); return }
        if (now.name != app.name || now.shownDescription != app.shownDescription || now.category != app.category) { recreate(); return }
        IconLoader.load(icon, app, dp(96))
        dual.resume()
        Store.foreground = this
        Store.addListener(listener)
        // back from the uninstall prompt of "Ersetzen": install the store's version now
        if (Store.prefs.getBoolean("replace_${app.id}", false)) {
            Store.prefs.edit().remove("replace_${app.id}").apply()
            if (Store.installed(app) == null) {
                Store.conflicts.remove(app.id)
                Store.errors.remove(app.id)
                install(replaceAsset)
            }
        }
        if (Store.blockers[app.id]?.let { pkg -> runCatching { packageManager.getPackageInfo(pkg, 0) }.isFailure } == true) {
            // the blocking app is gone
            Store.blockers.remove(app.id)
            Store.conflicts.remove(app.id)
            Store.errors.remove(app.id)
        }
        bind()
    }

    private var replaceAsset: ReleaseAsset? = null

    private fun confirmReplace() {
        AlertDialog.Builder(this)
            .setTitle(t("${app.name} ersetzen?", "Replace ${app.name}?"))
            .setMessage(t("Die installierte Version stammt aus einer anderen Quelle oder ist neuer als dieses Release. Android kann sie nur ersetzen, wenn sie vorher deinstalliert wird.\n\nDabei gehen die App-Daten verloren (Einstellungen, ggf. Spielstände im App-Ordner). Sichere sie vorher, falls nötig.\n\nWeiter: erst deinstallieren, danach installiert der Store seine Version.", "The installed version comes from another source or is newer than this release. Android can only replace it after uninstalling it.\n\nThis deletes the app's data (settings, possibly saves in the app folder). Back it up first if needed.\n\nNext: uninstall first, then the store installs its version."))
            .setPositiveButton(t("Deinstallieren & installieren", "Uninstall & install")) { _, _ ->
                if (!ensureInstallPermission()) return@setPositiveButton
                Store.prefs.edit().putBoolean("replace_${app.id}", true).apply()
                uninstall(app)
            }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        if (::dual.isInitialized) dual.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::dual.isInitialized) dual.destroy()
    }

    override fun onPause() {
        super.onPause()
        if (Store.foreground === this) Store.foreground = null
        Store.removeListener(listener)
    }

    private fun bind() {
        val inst = Store.installed(app)
        if (wasInstalled != null && wasInstalled != (inst != null)) {
            IconLoader.invalidate(app)
            IconLoader.load(icon, app, dp(96))
            // install state changed (e.g. uninstalled): fetch manifest data for update checks
            Store.fetchAsync(app)
        }
        wasInstalled = inst != null
        val rel = Store.release(app)
        val asset = Store.asset(app)
        val meta = Store.meta(app)
        val p = Store.progress[app.id]
        val update = Store.hasUpdate(app)
        val foreign = Store.isForeign(app)
        val conflict = Store.conflicts[app.id]

        primary.text = when {
            app.isLinkOnly -> t("Download-Seite öffnen", "Open download page")
            p != null -> p.text
            inst != null && update -> t("Aktualisieren", "Update")
            inst != null -> t("Öffnen", "Open")
            rel == null && Store.isFetching(app) -> t("Lade Infos…", "Loading info…")
            rel != null && asset == null -> t("Kein APK gefunden", "No APK found")
            else -> t("Installieren", "Install")
        }
        primary.alpha = if (p != null) 0.7f else 1f
        val github = !app.isLinkOnly
        btnFiles.visibility = if (github && rel != null) View.VISIBLE else View.GONE
        btnFiles.text = if (inst != null) t("Neu installieren / andere Datei…", "Reinstall / other file…") else t("Andere Datei…", "Other file…")
        btnOlder.visibility = if (github) View.VISIBLE else View.GONE
        btnUninstall.visibility = if (inst != null) View.VISIBLE else View.GONE
        btnIgnore.visibility = if (update) View.VISIBLE else View.GONE
        btnReplace.visibility = if (inst != null && (foreign || conflict == Store.Conflict.SIGNATURE || conflict == Store.Conflict.DOWNGRADE)) View.VISIBLE else View.GONE
        btnBlocker.visibility = if (conflict == Store.Conflict.PROVIDER && Store.blockers[app.id] != null) View.VISIBLE else View.GONE

        progressBar.visibility = if (p != null) View.VISIBLE else View.GONE
        progressBar.isIndeterminate = p != null && p.percent < 0
        if (p != null && p.percent >= 0) progressBar.progress = p.percent
        val err = Store.errors[app.id]
        status.text = when {
            err != null -> "⚠ $err"
            foreign -> t("Installiert aus einer anderen Quelle (andere Signatur). Updates über den Store gehen erst nach „Ersetzen“.", "Installed from another source (different signature). Updates through the store only work after “Replace”.")
            update -> t("Neue Version verfügbar: ${rel?.tag}", "New version available: ${rel?.tag}")
            else -> ""
        }
        status.setTextColor(if (err != null) C.DANGER else if (update || foreign) C.WARN else C.SUB)
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        info.text = buildString {
            if (app.isLinkOnly) {
                append(t("Dieses Projekt veröffentlicht keine APKs auf GitHub. Lade es von der offiziellen Seite:\n${app.homepage}", "This project does not publish APKs on GitHub. Get it from the official page:\n${app.homepage}"))
                return@buildString
            }
            append(t("Installiert: ", "Installed: "))
            append(inst?.let { "${it.versionName ?: "?"} (Code ${it.versionCode})" } ?: "nein")
            append(t("\nNeuestes Release: ", "\nLatest release: "))
            append(rel?.let { r -> r.tag + (formatDate(r.date)?.let { " · $it" } ?: "") } ?: if (Store.isFetching(app)) t("wird geladen…", "loading…") else "unbekannt")
            meta?.let { append(t("\nVersion im APK: ${it.versionName ?: "?"} (Code ${it.versionCode})", "\nVersion in the APK: ${it.versionName ?: "?"} (code ${it.versionCode})")) }
            asset?.let { a ->
                append(t("\nDatei: ${a.name}", "\nFile: ${a.name}"))
                a.sizeText?.let { append(" · $it") }
                if (a.sha256 != null) append(t("\nSHA-256 wird beim Download geprüft", "\nSHA-256 is checked on download"))
            }
            app.packageName?.let { append(t("\nPaket: $it", "\nPackage: $it")) }
        }

        panel?.bind()
        if (rel != null && notesTag != rel.tag && !app.isLinkOnly && !dual.active) {
            notesTag = rel.tag
            val tag = rel.tag
            Store.io.execute {
                val n = runCatching { Sources.notes(Store.client, app, tag) }.getOrNull()
                runOnUiThread { notes.text = n?.take(6000) ?: t("Keine Release-Notes vorhanden.", "No release notes.") }
            }
        }
    }

    private fun primaryAction() {
        if (app.isLinkOnly) { openUrl(app.homepage!!); return }
        if (Store.progress[app.id] != null) return
        val inst = Store.installed(app)
        if (inst != null && !Store.hasUpdate(app)) { launch(app); return }
        if (Store.release(app) != null && Store.asset(app) == null) { chooseFile(); return }
        install(null)
    }

    private fun install(asset: ReleaseAsset?) {
        if (!ensureInstallPermission()) return
        replaceAsset = asset
        DownloadService.install(this, app, asset)
    }

    private fun filesOf(assets: List<ReleaseAsset>) =
        if (app.apkInZip) ApkPicker.files(assets, app) else assets.filter { it.name.endsWith(".apk", true) }.sortedByDescending { ApkPicker.score(it.name) }

    private fun chooseFile() {
        val rel = Store.release(app) ?: return
        pickAsset(t("Datei aus ${rel.tag}", "File from ${rel.tag}"), filesOf(rel.assets))
    }

    private fun pickAsset(title: String, files: List<ReleaseAsset>) {
        if (files.isEmpty()) { toast(t("Dieses Release enthält kein APK", "This release contains no APK")); return }
        // the star marks the file the store would pick by itself
        val best = Store.asset(app)?.url
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(files.map { f -> f.name + (f.sizeText?.let { " · $it" } ?: "") + (if (f.url == best) "  ★" else "") }.toTypedArray()) { _, i -> install(files[i]) }
            .setNegativeButton(t("Abbrechen", "Cancel"), null)
            .show()
    }

    private fun chooseOlder() {
        if (app.isLinkOnly) return
        toast(t("Lade Versionen…", "Loading versions…"))
        Store.io.execute {
            val list = runCatching { Sources.list(Store.client, app, 1) + runCatching { Sources.list(Store.client, app, 2) }.getOrDefault(emptyList()) }.getOrElse {
                runOnUiThread { toast(t("Konnte Versionen nicht laden", "Could not load versions")) }
                return@execute
            }
            runOnUiThread {
                val downgrade = if (Store.installed(app) != null) t("\n(Für ein Downgrade erst deinstallieren)", "\n(Uninstall first to downgrade)") else ""
                AlertDialog.Builder(this)
                    .setTitle(t("Version wählen$downgrade", "Choose version$downgrade"))
                    .setItems(list.map { r -> r.tag + (formatDate(r.date)?.let { " · $it" } ?: "") + (if (r.prerelease) t(" · Pre-Release", " · pre-release") else "") }.toTypedArray()) { _, i ->
                        val tag = list[i].tag
                        Store.io.execute {
                            val rel = runCatching { Sources.release(Store.client, app, tag) }.getOrNull()
                            runOnUiThread { if (rel == null) toast(t("Release nicht lesbar", "Release not readable")) else pickAsset(t("Datei aus $tag", "File from $tag"), filesOf(rel.assets)) }
                        }
                    }
                    .setNegativeButton(t("Abbrechen", "Cancel"), null)
                    .show()
            }
        }
    }

    companion object {
        const val EXTRA_APP = "app"
    }
}
