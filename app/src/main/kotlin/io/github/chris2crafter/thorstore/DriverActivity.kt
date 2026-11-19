package io.github.chris2crafter.thorstore

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.chris2crafter.thorstore.core.ApkPicker
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.ReleaseSummary
import io.github.chris2crafter.thorstore.core.Sources
import io.github.chris2crafter.thorstore.core.t

/** All releases of one driver source; any version can be saved into /Driver. */
class DriverActivity : Activity() {

    private lateinit var app: CatalogApp
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var access: TextView
    private lateinit var folder: TextView
    private lateinit var more: TextView
    private lateinit var icon: ImageView
    private val releases = ArrayList<ReleaseSummary>()
    private var page = 0
    private var loading = false
    private val adapter = ReleaseAdapter()
    private val listener = { bind() }

    /** On the Thor the lower screen shows description and release notes. */
    private lateinit var dual: DualScreenHost
    private var panel: AppPanel? = null
    private val panelHost = object : AppPanel.Host {
        override fun primary(app: CatalogApp) = primaryAction(app)
        override fun open(app: CatalogApp) {}
        override fun edit(app: CatalogApp) = EditActivity.edit(this@DriverActivity, app)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = Store.app(intent.getStringExtra(DetailActivity.EXTRA_APP)) ?: run { finish(); return }
        dual = DualScreenHost(this) { ctx -> AppPanel(ctx, full = false, host = panelHost).also { panel = it; it.show(app) }.view }
        val split = dual.create()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        icon = ImageView(this)
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(app.name, 24f, bold = true))
                addView(text(t("${app.repo} · Ziel: /Driver", "${app.repo} · target: /Driver"), 14f, C.SUB))
            }, lp(0, WRAP, 1f).apply { marginStart = dp(16) })
        })
        if (!split) head.addView(text(app.shownDescription, 15f), lp().apply { topMargin = dp(12) })

        access = button(t("Speicherzugriff erlauben", "Allow storage access"), primary = true) { openStorageSettings() }
        folder = button(t("Im Ordner", "In folder")) { showFolder() }
        val github = button(t("Auf GitHub", "On GitHub")) { app.sourceUrl?.let { openUrl(it) } }
        val edit = button(t("Bearbeiten", "Edit")) { EditActivity.edit(this, app) }
        head.addView(row(access, folder, github, edit, spacing = 10), lp().apply { topMargin = dp(14) })
        status = text("", 14f, C.ACCENT)
        head.addView(status, lp().apply { topMargin = dp(8) })
        head.addView(text(if (split) t("Versionen (neueste zuerst) – A wählt die Datei, unten stehen die Release-Notes", "Versions (newest first) – A picks the file, release notes are below") else t("Versionen (neueste zuerst) – A wählt die Datei", "Versions (newest first) – A picks the file"), 16f, bold = true), lp().apply { topMargin = dp(10) })

        more = button(t("Mehr laden", "Load more")) { loadPage() }
        list = ListView(this).apply {
            divider = null
            // lets the D-pad reach the buttons in the header and footer
            itemsCanFocus = true
            dividerHeight = dp(8)
            selector = rounded(0x1A4EA8FF, dp(12).toFloat(), dp(3), C.ACCENT)
            setDrawSelectorOnTop(true)
            setPadding(dp(24), 0, dp(24), dp(20))
            clipToPadding = false
            addHeaderView(head, null, false)
            addFooterView(LinearLayout(context).apply { setPadding(0, dp(8), 0, 0); addView(more) }, null, false)
            adapter = this@DriverActivity.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                releases.getOrNull(pos - headerViewsCount)?.let(::choose)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    releases.getOrNull(pos - headerViewsCount)?.let { panel?.show(app, it.tag) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        setContentView(LinearLayout(this).apply {
            setBackgroundColor(C.BG)
            addView(list, lp(MATCH, MATCH))
        })
        loadPage()
    }

    override fun onResume() {
        super.onResume()
        val now = Store.app(app.id) ?: run { finish(); return }
        if (now.name != app.name || now.shownDescription != app.shownDescription) { recreate(); return }
        IconLoader.load(icon, app, dp(72))
        dual.resume()
        Store.foreground = this
        Store.addListener(listener)
        bind()
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
        access.visibility = if (Store.hasStorageAccess()) View.GONE else View.VISIBLE
        val files = Store.driverFiles(app)
        folder.text = t("Im Ordner (${files.size})", "In folder (${files.size})")
        val p = Store.progress[app.id]
        val err = Store.errors[app.id]
        status.text = p?.text ?: err?.let { "⚠ $it" } ?: ""
        status.setTextColor(if (p == null && err != null) C.DANGER else C.ACCENT)
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun loadPage() {
        if (loading) return
        loading = true
        more.text = t("Lade…", "Loading…")
        val next = page + 1
        Store.io.execute {
            val r = runCatching { Sources.list(Store.client, app, next) }
            runOnUiThread {
                loading = false
                r.onSuccess { l ->
                    page = next
                    releases.addAll(l.filter { n -> releases.none { it.tag == n.tag } })
                    more.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
                    more.text = t("Mehr laden", "Load more")
                    adapter.notifyDataSetChanged()
                    if (next == 1) list.post { list.setSelection(list.headerViewsCount) }
                }.onFailure {
                    more.text = t("Erneut versuchen", "Try again")
                    toast(t("Konnte Versionen nicht laden", "Could not load versions"))
                }
            }
        }
    }

    private fun choose(r: ReleaseSummary) {
        status.text = t("Lade Dateien von ${r.tag}…", "Loading files of ${r.tag}…")
        status.visibility = View.VISIBLE
        Store.io.execute {
            val rel = runCatching { Sources.release(Store.client, app, r.tag) }.getOrNull()
            runOnUiThread {
                bind()
                val files = rel?.let { ApkPicker.files(it.assets, app) }.orEmpty()
                if (files.isEmpty()) { toast(t("Keine passenden Treiber-ZIPs in ${r.tag}", "No matching driver ZIPs in ${r.tag}")); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle(t("${r.title} – nach /Driver laden", "${r.title} – download to /Driver"))
                    .setItems(files.map { f -> f.name + (f.sizeText?.let { " · $it" } ?: "") }.toTypedArray()) { _, i ->
                        if (!ensureStorageAccess()) return@setItems
                        DownloadService.driver(this, app, files[i], r.tag)
                        toast(t("Wird nach /Driver geladen", "Downloading to /Driver"))
                    }
                    .setNegativeButton(t("Abbrechen", "Cancel"), null)
                    .show()
            }
        }
    }

    private fun showFolder() {
        val files = Store.driverFiles(app)
        if (files.isEmpty()) { toast(t("Noch keine Treiber aus dieser Quelle in /Driver", "No drivers from this source in /Driver yet")); return }
        AlertDialog.Builder(this)
            .setTitle(t("/Driver – zum Löschen antippen", "/Driver – tap to delete"))
            .setItems(files.map { "${it.name} · ${it.length() / 1024} KB" }.toTypedArray()) { _, i ->
                val f = files[i]
                AlertDialog.Builder(this)
                    .setTitle(t("${f.name} löschen?", "Delete ${f.name}?"))
                    .setPositiveButton(t("Löschen", "Delete")) { _, _ -> Store.forgetDriver(app, f); toast(t("Gelöscht", "Deleted")) }
                    .setNegativeButton(t("Abbrechen", "Cancel"), null)
                    .show()
            }
            .setNegativeButton(t("Schließen", "Close"), null)
            .show()
    }

    private inner class ReleaseAdapter : BaseAdapter() {
        override fun getCount() = releases.size
        override fun getItem(position: Int) = releases[position]
        override fun getItemId(position: Int) = releases[position].tag.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = releases[position]
            val loaded = r.tag in Store.driverTags(app)
            val chips = LinearLayout(this@DriverActivity).apply { orientation = LinearLayout.HORIZONTAL }
            fun add(v: View) = chips.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
            if (position == 0) add(chip(t("Neueste", "Latest"), C.ACCENT))
            if (r.prerelease) add(chip(t("Pre-Release", "Pre-release"), C.WARN))
            if (loaded) add(chip("✓ in /Driver", C.OK))
            return LinearLayout(this@DriverActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C.CARD, dp(12).toFloat())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(text(r.title, 16f, bold = true, lines = 1))
                addView(text(listOfNotNull(r.tag.takeIf { it != r.title }, formatDate(r.date)).joinToString(" · "), 13f, C.SUB))
                if (chips.childCount > 0) addView(chips, lp().apply { topMargin = dp(6) })
            }
        }
    }
}
