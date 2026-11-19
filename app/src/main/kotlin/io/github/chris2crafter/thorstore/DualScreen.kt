package io.github.chris2crafter.thorstore

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.Sources
import io.github.chris2crafter.thorstore.core.Tags
import io.github.chris2crafter.thorstore.core.t
import java.util.concurrent.ConcurrentHashMap

/**
 * The AYN Thor's lower screen. The store runs on the upper one; the lower one shows what is
 * selected up there. It is drawn as a [Presentation] that never takes key focus, so the
 * controller keeps driving the upper screen even after a tap on the lower one.
 */
object DualScreen {
    /** Set when the lower screen refused our window; single-screen until the app restarts. */
    @Volatile var broken = false

    var enabled: Boolean
        get() = Store.prefs.getBoolean("dual", true)
        set(v) { Store.prefs.edit().putBoolean("dual", v).apply() }

    /** The other screen, or null on single-screen devices (or when switched off). */
    fun target(activity: Activity): Display? {
        if (!enabled || broken) return null
        val dm = activity.getSystemService(DisplayManager::class.java)
        val own = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != own && it.isValid }
    }

    fun available(activity: Activity) = target(activity) != null
}

/**
 * Owns the lower-screen window of one activity: shown while the activity is in front, hidden
 * when it is not, rebuilt when the screen comes or goes.
 */
class DualScreenHost(private val activity: Activity, private val build: (Context) -> View) : DisplayManager.DisplayListener {
    private val dm = activity.getSystemService(DisplayManager::class.java)
    private var presentation: Presentation? = null

    val active get() = presentation != null

    /** Call in onCreate before laying out the upper screen; true if there is a lower one. */
    fun create(): Boolean {
        dm.registerDisplayListener(this, Store.main)
        val d = DualScreen.target(activity) ?: return false
        presentation = Presentation(activity, d).apply {
            // touch works, keys stay on the upper screen
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            setContentView(build(context))
        }
        return true
    }

    fun resume() {
        val p = presentation ?: return
        if (p.isShowing) return
        try {
            p.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            // this screen does not take windows from apps: fall back to one screen
            DualScreen.broken = true
            presentation = null
            activity.recreate()
        }
    }

    fun stop() {
        presentation?.takeIf { it.isShowing }?.hide()
    }

    fun destroy() {
        dm.unregisterDisplayListener(this)
        presentation?.dismiss()
        presentation = null
    }

    override fun onDisplayAdded(displayId: Int) {
        if (presentation == null && DualScreen.target(activity)?.displayId == displayId) activity.recreate()
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (presentation?.display?.displayId == displayId) {
            presentation = null
            activity.recreate()
        }
    }

    override fun onDisplayChanged(displayId: Int) {}
}

/**
 * What the lower screen shows for one app. [full] adds header, buttons and version info (store
 * overview); without it only description and release notes (the detail page keeps the rest up top).
 */
class AppPanel(private val ctx: Context, private val full: Boolean, private val host: Host) {

    interface Host {
        fun primary(app: CatalogApp)
        fun open(app: CatalogApp)
        fun edit(app: CatalogApp)
    }

    var app: CatalogApp? = null
        private set
    /** Release whose notes are shown; null means the newest. */
    private var tag: String? = null

    private val scroll = ScrollView(ctx).apply { setBackgroundColor(C.BG); isFillViewport = true }
    private val empty: LinearLayout
    private val content: LinearLayout
    private val icon = ImageView(ctx)
    private val name = ctx.text("", 24f, bold = true, lines = 2)
    private val sub = ctx.text("", 14f, C.SUB, lines = 1)
    private val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val primary = ctx.button("", primary = true) { app?.let(host::primary) }
    private val status = ctx.text("", 14f, C.SUB)
    private val note = ctx.text("", 14f, C.WARN).apply {
        background = rounded(0x22F0883E, ctx.dp(10).toFloat())
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
    }
    private val desc = ctx.text("", 16f).apply { setLineSpacing(ctx.dp(3).toFloat(), 1f) }
    private val infoTitle = section(t("Version & Datei", "Version & file"))
    private val info = ctx.text("", 14f, C.SUB).apply { setLineSpacing(ctx.dp(3).toFloat(), 1f) }
    private val notesTitle = section(t("Release-Notes", "Release notes"))
    private val notes = ctx.text("", 14f, C.SUB).apply {
        setLineSpacing(ctx.dp(2).toFloat(), 1f)
        background = rounded(C.CARD, ctx.dp(10).toFloat())
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
    }
    private var notesKey: String? = null

    /** Network only once the selection rests, so scrolling through the grid stays cheap. */
    private var settled = false
    private var fetchedFor: String? = null
    private val settle = Runnable {
        settled = true
        bind()
    }

    val view: View get() = scroll

    init {
        val pad = ctx.dp(20)
        empty = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96)))
            addView(ctx.text("Floki Store", 24f, bold = true).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = ctx.dp(12) })
            addView(ctx.text(t("Wähle oben eine App aus. Hier unten stehen dann Beschreibung, Version und Release-Notes.\n\nA öffnet die ganze Seite · Tippen geht hier unten auch", "Pick an app on the upper screen. Its description, version and release notes then appear down here.\n\nA opens the full page · tapping works down here too"), 15f, C.SUB).apply { gravity = Gravity.CENTER }, lp().apply { topMargin = ctx.dp(8) })
        }
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        if (full) {
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LinearLayout.LayoutParams(ctx.dp(80), ctx.dp(80)))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name)
                    addView(sub)
                    addView(chips, lp().apply { topMargin = ctx.dp(6) })
                }, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(16) })
            })
            // big touch targets for the small screen
            val more = ctx.button(t("Alle Details", "All details")) { app?.let(host::open) }
            val edit = ctx.button(t("Bearbeiten", "Edit")) { app?.let(host::edit) }
            content.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(primary, lp(0, ctx.dp(56), 1.3f))
                addView(more, lp(0, ctx.dp(56), 1f).apply { marginStart = ctx.dp(10) })
                addView(edit, lp(0, ctx.dp(56), 1f).apply { marginStart = ctx.dp(10) })
            }, lp().apply { topMargin = ctx.dp(16) })
            content.addView(status, lp().apply { topMargin = ctx.dp(8) })
        }
        content.addView(note, lp().apply { topMargin = ctx.dp(12) })
        content.addView(section(t("Beschreibung", "Description")), lp().apply { topMargin = ctx.dp(if (full) 14 else 0) })
        content.addView(desc, lp().apply { topMargin = ctx.dp(6) })
        if (full) {
            content.addView(infoTitle, lp().apply { topMargin = ctx.dp(18) })
            content.addView(info, lp().apply { topMargin = ctx.dp(6) })
        }
        content.addView(notesTitle, lp().apply { topMargin = ctx.dp(18) })
        content.addView(notes, lp().apply { topMargin = ctx.dp(6) })
        scroll.addView(empty)
    }

    private fun section(s: String) = ctx.text(s, 18f, bold = true)

    /** Shows [a] (null: the welcome page) and scrolls to the top. */
    fun show(a: CatalogApp?, releaseTag: String? = null) {
        val changed = a?.id != app?.id || releaseTag != tag
        app = a
        tag = releaseTag
        val page = if (a == null) empty else content
        if (scroll.getChildAt(0) !== page) {
            scroll.removeAllViews()
            scroll.addView(page)
        }
        if (a == null) return
        if (changed) {
            if (full) IconLoader.load(icon, a, ctx.dp(80))
            scroll.scrollTo(0, 0)
            settled = false
            fetchedFor = null
            Store.main.removeCallbacks(settle)
            Store.main.postDelayed(settle, 450)
        }
        bind()
    }

    /** Refreshes install state, progress and notes of the shown app. */
    fun bind() {
        val a = app?.let { Store.app(it.id) } ?: return
        app = a
        note.text = a.note?.let { t("Hinweis: $it", "Note: $it") } ?: ""
        note.visibility = if (a.note != null) View.VISIBLE else View.GONE
        desc.text = a.description.ifBlank { t("Keine Beschreibung.", "No description.") }

        if (full) {
            name.text = a.name
            sub.text = listOfNotNull(a.repo ?: a.homepage?.substringAfter("://")?.substringBefore('/'), Store.categoryTitle(a.category)).joinToString(" · ")
            chips.removeAllViews()
            fun chip(label: String, color: Int) = chips.addView(ctx.chip(label, color), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
            val inst = Store.installed(a)
            when {
                a.isDriver -> Store.driverFiles(a).size.let { if (it > 0) chip("$it in /Driver", C.OK) }
                inst == null -> {}
                Store.isForeign(a) -> chip(t("Andere Quelle", "Other source"), C.WARN)
                Store.hasUpdate(a) -> chip(t("Update verfügbar", "Update available"), C.WARN)
                else -> chip(t("Installiert", "Installed"), C.OK)
            }
            if (a.user) chip(t("Eigene", "Own"), C.PURPLE)
            a.tags.filter { it in setOf("thor", "dual-screen", "recommended", "needs-rom") }.take(2).forEach { chip(Tags.label(it), C.ACCENT) }

            primary.text = primaryLabel(a)
            primary.alpha = if (Store.progress[a.id] != null) 0.7f else 1f
            val err = Store.errors[a.id]
            val rel = Store.release(a)
            status.text = when {
                Store.progress[a.id] != null -> ""
                err != null -> "⚠ $err"
                Store.isForeign(a) -> t("Installiert aus einer anderen Quelle. Updates gehen erst nach „Ersetzen“ (Alle Details).", "Installed from another source. Updates only work after “Replace” (All details).")
                Store.hasUpdate(a) -> t("Neue Version: ${rel?.tag}", "New version: ${rel?.tag}")
                else -> ""
            }
            status.setTextColor(if (err != null) C.DANGER else C.WARN)
            status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE

            info.text = when {
                a.isLinkOnly -> t("Keine APKs auf GitHub. Download über die offizielle Seite: ${a.homepage}", "No APKs on GitHub. Download from the official page: ${a.homepage}")
                else -> buildString {
                    append(t("Installiert: ", "Installed: "))
                    append(inst?.let { it.versionName ?: "ja" } ?: "nein")
                    append(t("\nNeuestes Release: ", "\nLatest release: "))
                    append(rel?.let { r -> r.tag + (formatDate(r.date)?.let { " · $it" } ?: "") } ?: if (Store.isFetching(a)) t("wird geladen…", "loading…") else t("noch nicht geladen", "not loaded yet"))
                    if (!a.isDriver) Store.asset(a)?.let { f -> append(t("\nDatei: ${f.name}", "\nFile: ${f.name}")); f.sizeText?.let { append(" · $it") } }
                }
            }
        }
        loadNotes(a)
    }

    private fun loadNotes(a: CatalogApp) {
        val visible = !a.isLinkOnly
        notesTitle.visibility = if (visible) View.VISIBLE else View.GONE
        notes.visibility = notesTitle.visibility
        if (!visible) return
        val t = tag ?: Store.release(a)?.tag
        if (t == null) {
            notes.text = Store.errors[a.id]?.let { "⚠ $it" } ?: t("Wird geladen…", "Loading…")
            // once per selection; drivers are not fetched by fetchAsync, so ask directly
            if (settled && fetchedFor != a.id && !Store.isFetching(a)) {
                fetchedFor = a.id
                Store.io.execute { Store.fetch(a) }
            }
            return
        }
        notesTitle.text = if (tag != null) t("Release-Notes $t", "Release notes $t") else t("Release-Notes", "Release notes")
        val key = "${a.id}@$t"
        if (key == notesKey) return
        cache[key]?.let { notesKey = key; notes.text = it; return }
        notes.text = t("Wird geladen…", "Loading…")
        if (!settled) return
        notesKey = key
        Store.io.execute {
            val n = runCatching { Sources.notes(Store.client, a, t) }.getOrNull()?.take(8000) ?: t("Keine Release-Notes vorhanden.", "No release notes.")
            cache[key] = n
            Store.main.post { if (notesKey == key) notes.text = n }
        }
    }

    companion object {
        private val cache = ConcurrentHashMap<String, String>()
    }
}
