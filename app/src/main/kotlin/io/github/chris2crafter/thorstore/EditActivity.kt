package io.github.chris2crafter.thorstore

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.chris2crafter.thorstore.core.AlreadyInStore
import io.github.chris2crafter.thorstore.core.AppAdder
import io.github.chris2crafter.thorstore.core.AppDraft
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.Links

/**
 * Adds an app from a GitHub link (typed, pasted or shared from the browser) or edits name,
 * description, tab and icon of an app that is already in the store.
 */
class EditActivity : Activity() {

    private var app: CatalogApp? = null
    private var draft: AppDraft? = null
    private var category = ""

    /** Icon the user picked; applied on save. */
    private var pendingIcon: Bitmap? = null
    private var dropCustomIcon = false

    private lateinit var link: EditText
    private lateinit var checkBtn: TextView
    private lateinit var status: TextView
    private lateinit var form: LinearLayout
    private lateinit var iconView: ImageView
    private lateinit var name: EditText
    private lateinit var categoryBtn: TextView
    private lateinit var desc: EditText
    private lateinit var info: TextView
    private lateinit var save: TextView
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = intent.getStringExtra(EXTRA_APP)?.let { Store.app(it) ?: run { finish(); return } }
        val editing = app

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
        }
        col.addView(text(if (editing == null) "App hinzufügen" else "App bearbeiten", 26f, bold = true))

        // ---- link (only when adding) ----
        link = field("https://github.com/owner/projekt").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> check(); true }
        }
        checkBtn = button("Prüfen", primary = true) { check() }
        val paste = button("Einfügen") { pasteLink() }
        status = text("", 14f, C.SUB)
        if (editing == null) {
            col.addView(text("Link zum GitHub-Projekt. Der Store sucht das passende APK im neuesten Release, liest Name und Icon aus und schlägt eine Kategorie vor. Tipp: Im Browser auf GitHub „Teilen → Floki Store“ wählen.", 14f, C.SUB), lp().apply { topMargin = dp(8) })
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(link, lp(0, WRAP, 1f))
                addView(paste, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
                addView(checkBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
            }, lp().apply { topMargin = dp(14) })
            col.addView(status, lp().apply { topMargin = dp(8) })
        }

        // ---- form ----
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        iconView = ImageView(this)
        val iconButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button("Bild wählen…") { pickImage() })
            addView(row(
                button("GitHub-Avatar") { loadAvatar() },
                button("Standard-Icon") { dropCustomIcon = true; pendingIcon = null; showIcon() },
                spacing = 10,
            ), lp().apply { topMargin = dp(10) })
        }
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView, LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(iconButtons, lp(0, WRAP, 1f).apply { marginStart = dp(18) })
        }, lp().apply { topMargin = dp(18) })

        name = field("Name")
        form.addView(label("Name"), lp().apply { topMargin = dp(18) })
        form.addView(name, lp().apply { topMargin = dp(6) })

        categoryBtn = button("") { chooseCategory() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        form.addView(label("Kategorie"), lp().apply { topMargin = dp(14) })
        form.addView(categoryBtn, lp().apply { topMargin = dp(6) })

        desc = field("Beschreibung").apply {
            isSingleLine = false
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        form.addView(label("Beschreibung"), lp().apply { topMargin = dp(14) })
        form.addView(desc, lp().apply { topMargin = dp(6) })

        info = text("", 14f, C.SUB).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        form.addView(info, lp().apply { topMargin = dp(14) })

        save = button(if (editing == null) "Hinzufügen" else "Speichern", primary = true) { save() }
        val actions = mutableListOf<View>(save, button("Abbrechen") { finish() })
        if (editing != null && editing.user) actions += button("Aus dem Store entfernen") { confirmRemove(editing) }
        if (editing != null && (Store.isEdited(editing) || IconLoader.hasCustomIcon(this, editing))) {
            actions += button("Original wiederherstellen") { confirmReset(editing) }
        }
        form.addView(row(*actions.toTypedArray(), spacing = 10), lp().apply { topMargin = dp(20) })
        col.addView(form)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            addView(col)
        })

        if (editing != null) {
            fill(editing)
            info.text = listOfNotNull(
                editing.repo?.let { "GitHub: $it" },
                if (editing.user) "Selbst hinzugefügt" else "Aus dem Katalog des Stores",
            ).joinToString("\n")
            name.requestFocus()
        } else {
            form.visibility = View.GONE
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(EXTRA_LINK) ?: intent.dataString
            if (shared != null) {
                link.setText(shared.trim())
                check()
            } else {
                link.requestFocus()
            }
        }
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(C.SUB)
        setTextColor(C.TEXT)
        textSize = 16f
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = focusBg(C.CARD, C.CARD_HI, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun label(s: String) = text(s, 15f, C.SUB, bold = true)

    private fun fill(a: CatalogApp) {
        name.setText(a.name)
        desc.setText(a.description)
        category = a.category
        showCategory()
        showIcon()
    }

    private fun showCategory() {
        categoryBtn.text = "${Store.categoryTitle(category)}  ▾"
    }

    private fun showIcon() {
        val a = draft?.app ?: app ?: return
        pendingIcon?.let { iconView.setImageBitmap(it); return }
        val d = draft
        if (d != null) {
            iconView.setImageBitmap(d.icon?.let { IconLoader.fromIconData(it, dp(96)) } ?: IconLoader.letterTile(a, dp(96)))
            return
        }
        if (!dropCustomIcon) { IconLoader.load(iconView, a, dp(96)); return }
        Store.io.execute {
            val bmp = IconLoader.withoutCustom(this, a, dp(96))
            runOnUiThread { if (dropCustomIcon && pendingIcon == null) iconView.setImageBitmap(bmp) }
        }
    }

    // ---- adding ----------------------------------------------------------------------------

    private fun pasteLink() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val t = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) { toast("Zwischenablage ist leer"); return }
        link.setText(t.trim())
        if (Links.repo(t) != null) check()
    }

    private fun check() {
        if (checking) return
        val input = link.text.toString().trim()
        if (Links.repo(input) == null) {
            status.setTextColor(C.DANGER)
            status.text = "⚠ Kein GitHub-Link erkannt. Beispiel: https://github.com/azahar-emu/azahar"
            return
        }
        checking = true
        checkBtn.text = "Prüfe…"
        status.setTextColor(C.ACCENT)
        status.text = "Suche Release und APK, lese Name und Icon aus…"
        Store.io.execute {
            val r = runCatching { AppAdder.inspect(Store.client, input, Store.catalog, Store.categories) }
            runOnUiThread {
                checking = false
                checkBtn.text = "Prüfen"
                r.onSuccess(::showDraft).onFailure { e ->
                    if (e is AlreadyInStore) {
                        status.text = ""
                        AlertDialog.Builder(this)
                            .setTitle("Schon im Store")
                            .setMessage("${e.app.name} ist bereits unter „${Store.categoryTitle(e.app.category)}“ eingetragen.")
                            .setPositiveButton("Öffnen") { _, _ -> openApp(e.app); finish() }
                            .setNegativeButton("Schließen", null)
                            .show()
                    } else {
                        status.setTextColor(C.DANGER)
                        status.text = "⚠ " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun showDraft(d: AppDraft) {
        draft = d
        pendingIcon = null
        dropCustomIcon = false
        fill(d.app)
        status.setTextColor(C.OK)
        status.text = "✓ Gefunden: ${d.app.repo}"
        info.text = buildString {
            append("Release: ${d.tag}\n")
            append(if (d.app.isDriver) "Dateien: ${d.file}" else "Datei: ${d.file}")
            d.versionName?.let { append("\nVersion: $it") }
            d.app.packageName?.let { append("\nPaket: $it") }
            if (d.topics.isNotEmpty()) append("\nThemen: ${d.topics.take(8).joinToString(", ")}")
            d.hints.forEach { append("\n• $it") }
            append("\n\nKategorie automatisch gewählt, du kannst sie oben ändern.")
        }
        form.visibility = View.VISIBLE
        save.requestFocus()
    }

    // ---- category --------------------------------------------------------------------------

    private fun chooseCategory() {
        val cats = Store.categories
        val labels = cats.map { it.title } + "＋ Neue Kategorie…"
        AlertDialog.Builder(this)
            .setTitle("Kategorie")
            .setSingleChoiceItems(labels.toTypedArray(), cats.indexOfFirst { it.id == category }) { d, i ->
                d.dismiss()
                if (i < cats.size) {
                    category = cats[i].id
                    showCategory()
                } else {
                    newCategoryDialog { c -> category = c.id; showCategory() }
                }
            }
            .show()
    }

    // ---- icon ------------------------------------------------------------------------------

    private fun pickImage() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_IMAGE)
        } catch (e: ActivityNotFoundException) {
            toast("Keine App zur Bildauswahl gefunden")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || uri == null) return
        Store.io.execute {
            val bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    // a photo does not need more than ~512 px for a 256 px icon
                    val side = minOf(info.size.width, info.size.height)
                    dec.setTargetSampleSize(maxOf(1, side / 512))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            runOnUiThread {
                if (bmp == null) { toast("Bild konnte nicht gelesen werden"); return@runOnUiThread }
                setPending(IconLoader.square(bmp, 256))
            }
        }
    }

    private fun loadAvatar() {
        val owner = (draft?.app ?: app)?.owner ?: run { toast("Kein GitHub-Projekt hinterlegt"); return }
        toast("Lade Avatar von $owner…")
        Store.io.execute {
            val bmp = runCatching {
                val r = Store.client.get("https://github.com/$owner.png?size=256", maxBytes = 4 shl 20)
                if (r.code == 200) BitmapFactory.decodeByteArray(r.body, 0, r.body.size) else null
            }.getOrNull()
            runOnUiThread {
                if (bmp == null) toast("Avatar konnte nicht geladen werden") else setPending(IconLoader.square(bmp, 256))
            }
        }
    }

    private fun setPending(bmp: Bitmap) {
        pendingIcon = bmp
        dropCustomIcon = false
        iconView.setImageBitmap(bmp)
    }

    private fun applyIcon(a: CatalogApp) {
        val p = pendingIcon
        when {
            p != null -> IconLoader.saveCustom(this, a, p)
            dropCustomIcon -> { IconLoader.customIconFile(this, a).delete(); IconLoader.invalidate(a) }
        }
    }

    // ---- saving ----------------------------------------------------------------------------

    private fun save() {
        val n = name.text.toString().trim()
        if (n.isEmpty()) { name.error = "Bitte einen Namen eingeben"; name.requestFocus(); return }
        val d = desc.text.toString().trim()
        val dr = draft
        if (dr != null) {
            val a = dr.app.copy(name = n, description = d, category = category)
            dr.icon?.let { IconLoader.saveFromApk(this, a, it) }
            applyIcon(a)
            Store.addUserApp(a)
            toast("„$n“ ist jetzt unter „${Store.categoryTitle(category)}“")
            Store.app(a.id)?.let { added ->
                Store.fetchAsync(added, force = true)
                openApp(added)
            }
            finish()
            return
        }
        val a = app ?: return
        Store.editApp(a, n, d, category)
        applyIcon(a)
        toast("Gespeichert")
        finish()
    }

    private fun confirmRemove(a: CatalogApp) {
        AlertDialog.Builder(this)
            .setTitle("${a.name} aus dem Store entfernen?")
            .setMessage("Der Eintrag verschwindet aus dem Floki Store. Ist die App installiert, bleibt sie installiert, wird aber nicht mehr auf Updates geprüft.")
            .setPositiveButton("Entfernen") { _, _ ->
                Store.removeUserApp(a)
                toast("Entfernt")
                finish()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmReset(a: CatalogApp) {
        AlertDialog.Builder(this)
            .setTitle("Original wiederherstellen?")
            .setMessage("Name, Beschreibung, Kategorie und Icon von ${a.name} werden auf den Stand des Katalogs zurückgesetzt.")
            .setPositiveButton("Zurücksetzen") { _, _ ->
                Store.resetApp(a)
                toast("Zurückgesetzt")
                finish()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun openApp(a: CatalogApp) {
        val cls = if (a.isDriver) DriverActivity::class.java else DetailActivity::class.java
        startActivity(Intent(this, cls).putExtra(DetailActivity.EXTRA_APP, a.id))
    }

    companion object {
        const val EXTRA_APP = "app"
        const val EXTRA_LINK = "link"
        private const val PICK_IMAGE = 7

        fun add(a: Activity) = a.startActivity(Intent(a, EditActivity::class.java))

        fun edit(a: Activity, app: CatalogApp) = a.startActivity(Intent(a, EditActivity::class.java).putExtra(EXTRA_APP, app.id))
    }
}
