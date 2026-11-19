package io.github.chris2crafter.thorstore.core

/** The store speaks German and English; the app sets [english] from the setting or the system language. */
object I18n {
    @Volatile var english = false
}

/** [de], or [en] when the store runs in English. */
fun t(de: String, en: String): String = if (I18n.english) en else de
