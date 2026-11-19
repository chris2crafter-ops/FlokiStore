package io.github.chris2crafter.thorstore

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import io.github.chris2crafter.thorstore.core.Categories
import io.github.chris2crafter.thorstore.core.Category

/** One-line text input in a dialog. */
fun Activity.askText(title: String, initial: String, hint: String, ok: String, onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial)
        setSelection(initial.length)
        this.hint = hint
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setTextColor(C.TEXT)
        setHintTextColor(C.SUB)
    }
    val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, lp()) }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .setPositiveButton(ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
        .setNegativeButton("Abbrechen", null)
        .show()
    input.requestFocus()
}

fun Activity.newCategoryDialog(onCreated: (Category) -> Unit = {}) =
    askText("Neue Kategorie", "", "z. B. Shooter oder Favoriten", "Anlegen") { title ->
        val existing = Store.categories.firstOrNull { it.title.equals(title, ignoreCase = true) }
        if (existing != null) {
            toast("„${existing.title}“ gibt es schon")
            onCreated(existing)
        } else {
            onCreated(Store.addCategory(title))
        }
    }

/** List of all tabs with their app counts; each opens [categoryMenu]. */
fun Activity.manageCategories() {
    val cats = Store.categories
    val labels = cats.map { "${it.title}  (${Store.appsIn(it.id).size})" } +
        listOf("＋ Neue Kategorie", "Standard-Kategorien wiederherstellen")
    AlertDialog.Builder(this)
        .setTitle("Kategorien verwalten")
        .setItems(labels.toTypedArray()) { _, i ->
            when {
                i < cats.size -> categoryMenu(cats[i]) { manageCategories() }
                i == cats.size -> newCategoryDialog { manageCategories() }
                else -> AlertDialog.Builder(this)
                    .setTitle("Standard-Kategorien wiederherstellen?")
                    .setMessage("Die mitgelieferten Kategorien kommen mit ihren ursprünglichen Namen und in der ursprünglichen Reihenfolge zurück. Eigene Kategorien verschwinden, ihre Apps landen unter Tools.")
                    .setPositiveButton("Wiederherstellen") { _, _ -> Store.resetCategories(); toast("Kategorien zurückgesetzt") }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
        .setNegativeButton("Schließen", null)
        .show()
}

/** Rename, move or delete one tab. [back] reopens the screen this came from. */
fun Activity.categoryMenu(c: Category, back: (() -> Unit)? = null) {
    val items = arrayOf("Umbenennen", "Nach links verschieben", "Nach rechts verschieben", "Löschen")
    AlertDialog.Builder(this)
        .setTitle(c.title)
        .setItems(items) { _, i ->
            when (i) {
                0 -> askText("Kategorie umbenennen", c.title, "Name", "Speichern") { t ->
                    Store.renameCategory(c.id, t)
                    back?.invoke()
                }
                1, 2 -> {
                    Store.moveCategory(c.id, if (i == 1) -1 else 1)
                    // stay in the menu so it can be moved several places in a row
                    Store.categories.firstOrNull { it.id == c.id }?.let { categoryMenu(it, back) }
                }
                3 -> deleteCategory(c, back)
            }
        }
        .setNegativeButton(if (back != null) "Zurück" else "Schließen") { _, _ -> back?.invoke() }
        .show()
}

private fun Activity.deleteCategory(c: Category, back: (() -> Unit)?) {
    val others = Store.categories.filter { it.id != c.id }
    if (others.isEmpty()) { toast("Die letzte Kategorie kann nicht gelöscht werden"); return }
    val n = Store.appsIn(c.id).size
    if (n == 0) {
        val target = others.firstOrNull { it.id == Categories.TOOLS } ?: others.first()
        AlertDialog.Builder(this)
            .setTitle("„${c.title}“ löschen?")
            .setMessage("Die Kategorie ist leer. Kommen mit einem Store-Update neue Apps dafür, landen sie unter „${target.title}“.")
            .setPositiveButton("Löschen") { _, _ -> Store.deleteCategory(c.id, target.id); back?.invoke() }
            .setNegativeButton("Abbrechen", null)
            .show()
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Wohin mit den $n Apps aus „${c.title}“?")
        .setItems(others.map { it.title }.toTypedArray()) { _, i ->
            Store.deleteCategory(c.id, others[i].id)
            toast("„${c.title}“ gelöscht, Apps sind jetzt unter „${others[i].title}“")
            back?.invoke()
        }
        .setNegativeButton("Abbrechen", null)
        .show()
}
