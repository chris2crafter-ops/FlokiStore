package io.github.chris2crafter.thorstore

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.Categories
import io.github.chris2crafter.thorstore.core.Category
import io.github.chris2crafter.thorstore.core.Tags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private class Tab(val id: String, val title: String, val category: Category? = null, val filter: (CatalogApp) -> Boolean)

    private var tabs: List<Tab> = emptyList()
    private var tab = 0
    /** Categories the tab row was built from; rebuilt when the user changes them. */
    private var shownCategories: List<Category>? = null

    private lateinit var tabViews: List<TextView>
    private lateinit var tabRow: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var subtitle: TextView
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var banner: TextView
    private lateinit var updatesBar: LinearLayout
    private lateinit var updatesStatus: TextView
    private val adapter = AppAdapter()
    private val listener = { refresh() }

    /** Lower screen of the Thor: details of the app selected up here. */
    private lateinit var dual: DualScreenHost
    private var panel: AppPanel? = null
    private val panelHost = object : AppPanel.Host {
        override fun primary(app: CatalogApp) = primaryAction(app)
        override fun open(app: CatalogApp) = openPage(app)
        override fun edit(app: CatalogApp) = EditActivity.edit(this@MainActivity, app)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dual = DualScreenHost(this) { ctx -> AppPanel(ctx, full = true, host = panelHost).also { panel = it }.view }
        dual.create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(20), dp(14), dp(20), 0)
        }

        // header: logo, title, search, settings
        val logo = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Floki Store", 24f, bold = true))
            subtitle = text("", 12f, C.SUB)
            addView(subtitle)
        }
        search = EditText(this).apply {
            hint = "Suchen (Y)"
            setHintTextColor(C.SUB)
            setTextColor(C.TEXT)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = focusBg(C.CARD, C.CARD_HI, 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // searching looks through everything
                    if (!s.isNullOrBlank() && tabs[tab].id != "all") selectTab(0) else refresh()
                }
            })
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); grid.requestFocus(); true }
        }
        val add = button("＋ App") { EditActivity.add(this) }
        val settings = button("Einstellungen") { settings() }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(titles, lp(0, WRAP, 1f).apply { marginStart = dp(12) })
            addView(search, LinearLayout.LayoutParams(dp(280), WRAP))
            addView(add, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
            addView(settings, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        }
        root.addView(header)

        // tabs (L1/R1)
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buildTabs()
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }
        root.addView(tabScroll, lp().apply { topMargin = dp(14) })
        root.addView(text("L1/R1 Tab wechseln · A öffnen · B zurück · X nach Updates suchen · Y suchen · Start Einstellungen · Tab lange drücken: Kategorie bearbeiten", 11f, C.SUB), lp().apply { topMargin = dp(6) })

        // updates tab actions
        updatesStatus = text("", 13f, C.SUB)
        updatesBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("Jetzt prüfen", primary = true) { checkNow() })
            addView(button("Alle aktualisieren") { updateAll() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
            addView(updatesStatus, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
            visibility = View.GONE
        }
        root.addView(updatesBar, lp().apply { topMargin = dp(10) })

        banner = text("", 13f, C.SUB).apply {
            background = rounded(C.CARD, dp(10).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        root.addView(banner, lp().apply { topMargin = dp(10) })

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(if (dual.active) 290 else 330)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(12)
            verticalSpacing = dp(12)
            setPadding(0, dp(12), 0, dp(20))
            clipToPadding = false
            selector = rounded(0x1A4EA8FF, dp(14).toFloat(), dp(3), C.ACCENT)
            setDrawSelectorOnTop(true)
            adapter = this@MainActivity.adapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
                val app = this@MainActivity.adapter.items[pos]
                val p = panel
                // touch: the first tap shows the app below, the second opens it; A always opens
                if (p != null && isInTouchMode && p.app?.id != app.id) {
                    showBelow(app)
                    this@MainActivity.adapter.notifyDataSetChanged()
                } else {
                    open(app)
                }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    showBelow(this@MainActivity.adapter.items.getOrNull(pos))
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
                EditActivity.edit(this@MainActivity, this@MainActivity.adapter.items[pos])
                true
            }
        }
        empty = text("", 15f, C.SUB).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(empty, lp().apply { topMargin = dp(40) })
        root.addView(grid, lp(MATCH, 0, 1f))

        setContentView(root)
        handleIntent(intent)
        selectTab(tab)
        grid.requestFocus()
        askNotificationPermission()
        CrashLog.showIfAny(this)
        SelfUpdate.checkOnStart(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Tab row: "Alle", one tab per category, "Updates". Keeps the current tab if it still exists. */
    private fun buildTabs() {
        val current = tabs.getOrNull(tab)?.id
        val cats = Store.categories
        shownCategories = cats
        tabs = buildList {
            add(Tab("all", "Alle") { !it.isDriver })
            cats.forEach { c -> add(Tab(c.id, c.title, c) { it.category == c.id }) }
            add(Tab("updates", "Updates") { Store.hasUpdate(it) })
        }
        tabRow.removeAllViews()
        tabViews = tabs.mapIndexed { i, t ->
            text(t.title, 15f, bold = true).apply {
                setPadding(dp(16), dp(9), dp(16), dp(9))
                isFocusable = true
                isClickable = true
                setOnClickListener { selectTab(i) }
                t.category?.let { c -> setOnLongClickListener { categoryMenu(c); true } }
                tabRow.addView(this, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            }
        }
        tab = tabs.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: tab.coerceAtMost(tabs.lastIndex)
    }

    private fun handleIntent(i: Intent?) {
        val t = i?.getStringExtra(EXTRA_TAB) ?: return
        tabs.indexOfFirst { it.id == t }.takeIf { it >= 0 }?.let { tab = it; if (::grid.isInitialized) selectTab(it) }
    }

    override fun onResume() {
        super.onResume()
        Store.foreground = this
        Store.addListener(listener)
        refresh()
        Store.refreshInstalledAsync()
        dual.resume()
    }

    override fun onStop() {
        super.onStop()
        dual.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        dual.destroy()
    }

    override fun onPause() {
        super.onPause()
        if (Store.foreground === this) Store.foreground = null
        Store.removeListener(listener)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.allowed(this) && !Store.prefs.getBoolean("askedNotif", false)) {
            Store.prefs.edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> { selectTab((tab - 1 + tabs.size) % tabs.size); return true }
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> { selectTab((tab + 1) % tabs.size); return true }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    search.requestFocus()
                    getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_X -> { selectTab(tabs.lastIndex); checkNow(); return true }
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> { settings(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun hideKeyboard() = getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)

    private fun styleTabs() {
        tabViews.forEachIndexed { j, v ->
            val sel = j == tab
            v.setTextColor(if (sel) C.BG else C.TEXT)
            v.background = if (sel) focusBg(C.ACCENT, C.ACCENT_HI, 10, C.TEXT) else focusBg(C.CARD, C.CARD_HI, 10)
        }
    }

    private fun selectTab(i: Int) {
        tab = i
        styleTabs()
        tabViews[i].let { v -> tabScroll.post { tabScroll.smoothScrollTo((v.left - dp(40)).coerceAtLeast(0), 0) } }
        refresh()
        grid.setSelection(0)
        showBelow(adapter.items.firstOrNull())
    }

    private fun showBelow(app: CatalogApp?) {
        try {
            panel?.show(app)
        } catch (e: Exception) {
            CrashLog.record(this, "App unten anzeigen", e)
        }
    }

    private fun refresh() {
        try {
            refreshNow()
        } catch (e: Exception) {
            // show what went wrong instead of closing the store
            CrashLog.record(this, "Liste aktualisieren (Tab ${tabs.getOrNull(tab)?.id})", e)
            banner.setTextColor(C.DANGER)
            banner.text = "⚠ Fehler: ${e.javaClass.simpleName}: ${e.message}"
            banner.visibility = View.VISIBLE
        }
    }

    private fun refreshNow() {
        banner.setTextColor(C.SUB)
        if (Store.categories != shownCategories) {
            buildTabs()
            styleTabs()
        }
        subtitle.text = "${Store.catalog.size} Einträge · Katalog vom ${formatDate(Store.catalogDate) ?: Store.catalogDate ?: "?"}" +
            Store.catalog.count { it.user }.let { if (it > 0) " · $it eigene" else "" }
        val ups = Store.updates()
        tabViews.last().text = if (ups.isEmpty()) "Updates" else "Updates (${ups.size})"

        val t = tabs[tab]
        val q = search.text.toString().trim().lowercase()
        var items = Store.catalog.filter(t.filter)
        if (q.isNotEmpty()) {
            items = Store.catalog.filter { a ->
                listOfNotNull(a.name, a.description, a.repo, Store.categoryTitle(a.category)).any { it.lowercase().contains(q) } ||
                    a.tags.any { Tags.label(it).lowercase().contains(q) }
            }
        }
        if (t.id == "all") items = items.sortedBy { it.name.lowercase() }
        adapter.items = items
        adapter.notifyDataSetChanged()
        try {
            panel?.bind()
        } catch (e: Exception) {
            CrashLog.record(this, "App unten aktualisieren", e)
        }

        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            q.isNotEmpty() -> "Nichts gefunden für „$q“"
            t.id == "updates" -> "Alles aktuell. Mit „Jetzt prüfen“ suchst du sofort nach neuen Versionen."
            t.category != null -> "Noch keine Apps in „${t.title}“. Mit „＋ App“ fügst du eine per GitHub-Link hinzu, vorhandene Apps verschiebst du in ihrer Detailansicht mit „Bearbeiten“."
            else -> "Keine Einträge"
        }

        updatesBar.visibility = if (t.id == "updates") View.VISIBLE else View.GONE
        val last = Store.prefs.getLong("lastCheck", 0)
        if (t.id == "updates" && updatesStatus.tag != "busy") {
            updatesStatus.text = if (last == 0L) "Noch nicht geprüft" else "Zuletzt geprüft: " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(last))
        }

        val info = when (t.id) {
            Categories.FANGAMES -> "Fan-Projekte und Fan-Remakes. Manche brauchen Daten aus dem Original (z. B. Sonic 3 A.I.R., AM2R, Cave Story) – das steht dann beim Eintrag."
            Categories.COMPANIONS -> "Pokémon-Tools und Begleit-Apps, die den zweiten Bildschirm nutzen. Spiele und ROMs sind nicht enthalten."
            Categories.RECOMPS, Categories.PORTS -> "Recomps und Ports enthalten keine Spieldaten. Du brauchst deine eigenen, legal erworbenen Dateien (ROM, Disc-Image oder PC-Installation)."
            Categories.EMULATORS -> "Einige Emulatoren brauchen BIOS- bzw. Firmware-Dateien aus deiner eigenen Konsole. Einträge mit „Webseite“ öffnen die offizielle Download-Seite."
            Categories.DRIVERS -> buildString {
                append("Treiber landen als ZIP im Ordner /Driver im Hauptverzeichnis. In Eden, Citron, Winlator, GameNative & Co. wählst du sie dort aus. ")
                append("Für den Thor (Adreno 740): aktuellen Turnip in der Standard-Variante nehmen. Wenn es Grafikfehler gibt, eine ältere Version testen, z. B. Turnip v26.0.0 R7 von K11MCH1.")
                if (!Store.hasStorageAccess()) append("\n⚠ Speicherzugriff fehlt noch: Einstellungen → Zugriff auf /Driver erlauben.")
            }
            else -> null
        }
        banner.text = info ?: ""
        banner.visibility = if (info != null && q.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(app: CatalogApp) {
        val cls = if (app.isDriver) DriverActivity::class.java else DetailActivity::class.java
        startActivity(Intent(this, cls).putExtra(DetailActivity.EXTRA_APP, app.id))
    }

    private fun checkNow() {
        updatesStatus.tag = "busy"
        updatesStatus.text = "Suche nach Updates…"
        Store.checkUpdatesAsync { ups ->
            updatesStatus.tag = null
            refresh()
            updatesStatus.text = if (ups.isEmpty()) "Alles aktuell" else "${ups.size} Update(s) gefunden"
        }
    }

    private fun updateAll() {
        val ups = Store.updates()
        if (ups.isEmpty()) { toast("Keine Updates vorhanden"); return }
        if (!ensureInstallPermission()) return
        ups.forEach { DownloadService.install(this, it) }
        toast("${ups.size} Update(s) in der Warteschlange")
    }

    private fun settings() {
        val hours = Store.intervalHours
        val entries = listOf<Pair<String, () -> Unit>>(
            "App über GitHub-Link hinzufügen" to { EditActivity.add(this) },
            "Kategorien verwalten" to { manageCategories() },
            "Zweiter Bildschirm: " + dualState() to { toggleDual() },
            "Automatische Update-Prüfung: " + (if (hours == 0) "aus" else "alle $hours Std.") to { chooseInterval() },
            "Jetzt nach Updates suchen" to { selectTab(tabs.lastIndex); checkNow() },
            (if (Notifications.allowed(this)) "Benachrichtigungen: erlaubt" else "Benachrichtigungen erlauben") to {
                if (Build.VERSION.SDK_INT >= 33) {
                    if (Notifications.allowed(this)) toast("Bereits erlaubt") else requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            },
            (if (Store.hasStorageAccess()) "Zugriff auf /Driver: erlaubt" else "Zugriff auf /Driver erlauben") to {
                if (Store.hasStorageAccess()) toast("Bereits erlaubt") else openStorageSettings()
            },
            "GitHub-Token (optional)" to { tokenDialog() },
            "Cache leeren" to { Store.clearCache(); toast("Cache geleert") },
            "Nach neuer Floki-Store-Version suchen" to { SelfUpdate.check(this) },
            "Über Floki Store" to { about() },
        )
        AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    private fun dualState() = when {
        !DualScreen.enabled -> "aus"
        dual.active -> "an (Details unten)"
        DualScreen.broken -> "nicht nutzbar"
        else -> "an (kein zweiter Bildschirm gefunden)"
    }

    private fun toggleDual() {
        DualScreen.enabled = !DualScreen.enabled
        toast(if (DualScreen.enabled) "Details erscheinen auf dem unteren Bildschirm" else "Nur ein Bildschirm")
        recreate()
    }

    private fun chooseInterval() {
        val values = intArrayOf(0, 3, 6, 12, 24)
        val labels = arrayOf("Aus", "Alle 3 Stunden", "Alle 6 Stunden", "Alle 12 Stunden", "Einmal am Tag")
        AlertDialog.Builder(this)
            .setTitle("Update-Prüfung im Hintergrund")
            .setSingleChoiceItems(labels, values.indexOf(Store.intervalHours).coerceAtLeast(0)) { d, which ->
                Store.intervalHours = values[which]
                UpdateJobService.schedule(this)
                d.dismiss()
            }
            .show()
    }

    private fun tokenDialog() {
        val input = EditText(this).apply {
            setText(Store.prefs.getString("token", "") ?: "")
            hint = "ghp_…"
            isSingleLine = true
            setTextColor(C.TEXT)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, lp()) }
        AlertDialog.Builder(this)
            .setTitle("GitHub-Token")
            .setMessage("Nur nötig, falls GitHub die Seiten mal drosselt. Der Store nutzt dann die API mit deinem Token (Leserechte reichen).")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ -> Store.setToken(input.text.toString().trim()) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("Floki Store")
            .setMessage(
                "Ein App-Store für den AYN Thor, benannt nach Hrafna-Flóki, dem Wikinger, der mit Raben den Weg nach Island fand. Apps kommen direkt aus den GitHub-Releases der jeweiligen Projekte, werden per SHA-256 geprüft und mit dem Android-Installer installiert.\n\n" +
                    "Katalog: ${Store.catalog.size} Einträge, Stand ${formatDate(Store.catalogDate) ?: "?"}.\n\n" +
                    "Der Store lädt keine Spiele, ROMs, BIOS- oder Firmware-Dateien. Dafür brauchst du deine eigenen, legal erworbenen Kopien.\n\n" +
                    "Version ${Store.installed(SelfUpdate.app() ?: return)?.versionName ?: "?"} · github.com/${Store.SELF_REPO}",
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Auf GitHub") { _, _ -> openUrl("https://github.com/${Store.SELF_REPO}") }
            .show()
    }

    // ---- grid ------------------------------------------------------------------------------

    private class Holder(val root: View, val icon: ImageView, val name: TextView, val desc: TextView, val chips: LinearLayout)

    private inner class AppAdapter : BaseAdapter() {
        var items: List<CatalogApp> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: card()
            val h = view.tag as Holder
            val app = items[position]
            try {
                bindApp(h, app)
            } catch (e: Exception) {
                CrashLog.record(this@MainActivity, "App-Karte ${app.id}", e)
                h.name.text = app.name
                h.desc.text = "⚠ ${e.javaClass.simpleName}: ${e.message}"
            }
            return view
        }

        private fun bindApp(h: Holder, app: CatalogApp) {
            IconLoader.load(h.icon, app, dp(64))
            h.name.text = app.name
            h.desc.text = app.description
            // the app shown on the lower screen, also visible when using touch
            h.root.background = rounded(C.CARD, dp(14).toFloat(), if (grid.isInTouchMode && panel?.app?.id == app.id) dp(2) else 0, C.ACCENT)
            h.chips.removeAllViews()
            statusChip(app)?.let { h.chips.addView(it, chipLp()) }
            if (app.user) h.chips.addView(chip("Eigene", C.PURPLE), chipLp())
            app.tags.filter { it in setOf("thor", "dual-screen", "recommended", "beta") }.take(2)
                .forEach { tag -> h.chips.addView(chip(Tags.label(tag), tagColor(tag)), chipLp()) }
        }
    }

    private fun card(): View {
        val icon = ImageView(this@MainActivity)
        val name = text("", 17f, bold = true, lines = 1)
        val desc = text("", 13f, C.SUB, lines = if (dual.active) 1 else 2)
        val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val col = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(desc, lp().apply { topMargin = dp(2) })
            addView(chips, lp().apply { topMargin = dp(8) })
        }
        return LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.CARD, dp(14).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minimumHeight = dp(if (dual.active) 100 else 118)
            addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(col, lp(0, WRAP, 1f).apply { marginStart = dp(14) })
            tag = Holder(this, icon, name, desc, chips)
        }
    }

    private fun chipLp() = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }

    private fun tagColor(tag: String) = when (tag) {
        "thor" -> C.ACCENT
        "dual-screen" -> C.PURPLE
        "recommended" -> C.OK
        else -> C.SUB
    }

    private fun statusChip(app: CatalogApp): TextView? {
        Store.progress[app.id]?.let { return chip(it.text, C.ACCENT) }
        if (app.isDriver) {
            val n = Store.driverFiles(app).size
            return if (n > 0) chip("$n in /Driver", C.OK) else chip("Treiber-ZIP", C.SUB)
        }
        if (app.isLinkOnly) return chip("Webseite", C.SUB)
        val inst = Store.installed(app) ?: return null
        if (Store.isForeign(app)) return chip("Andere Quelle", C.WARN)
        if (Store.hasUpdate(app)) return chip("Update verfügbar", C.WARN)
        return chip("Installiert" + (inst.versionName?.let { " · ${it.take(14)}" } ?: ""), C.OK)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
