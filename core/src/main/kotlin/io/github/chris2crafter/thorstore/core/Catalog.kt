package io.github.chris2crafter.thorstore.core

/** A store tab. The built-in ones can be renamed, reordered or deleted like the user's own. */
data class Category(val id: String, val title: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "title" to title)

    companion object {
        fun fromMap(m: Map<String, Any?>) = Category(m.str("id")!!, m.str("title") ?: m.str("id")!!)
    }
}

object Categories {
    const val RECOMPS = "recomps"
    const val PORTS = "ports"
    const val FANGAMES = "fangames"
    const val EMULATORS = "emulators"
    const val FRONTENDS = "frontends"
    const val COMPANIONS = "companions"
    const val PC = "pc"
    const val TOOLS = "tools"
    const val DRIVERS = "drivers"

    val builtIn = listOf(
        Category(RECOMPS, "Recomps & Decomps"),
        Category(PORTS, "Game Ports"),
        Category(FANGAMES, "Fan Games"),
        Category(EMULATORS, "Emulatoren"),
        Category(FRONTENDS, "Launcher"),
        Category(COMPANIONS, "Pokémon & Companions"),
        Category(PC, "PC & Streaming"),
        Category(TOOLS, "Tools"),
        Category(DRIVERS, "Treiber"),
    )

    private val english = mapOf(
        RECOMPS to "Recomps & Decomps", PORTS to "Game Ports", FANGAMES to "Fan Games", EMULATORS to "Emulators",
        FRONTENDS to "Launchers", COMPANIONS to "Pokémon & Companions", PC to "PC & Streaming", TOOLS to "Tools",
        DRIVERS to "Drivers",
    )

    /** Built-in tabs that still carry their shipped name are shown in the store's language. */
    fun localized(c: Category): Category {
        val shipped = builtIn.firstOrNull { it.id == c.id } ?: return c
        return if (I18n.english && c.title == shipped.title) c.copy(title = english.getValue(c.id)) else c
    }
}

/** Human readable labels for catalog tags. */
object Tags {
    // a getter, so the labels follow the language
    private val labels get() = mapOf(
        "dual-screen" to "Dual-Screen",
        "thor" to t("Für den Thor", "For the Thor"),
        "needs-rom" to t("Eigene Spieldatei nötig", "Own game file needed"),
        "needs-bios" to t("BIOS nötig", "BIOS needed"),
        "beta" to "Beta",
        "shizuku" to t("Nutzt Shizuku", "Uses Shizuku"),
        "recommended" to t("Empfohlen", "Recommended"),
    )

    fun label(tag: String) = labels[tag] ?: tag
}

/**
 * One store entry. [repo] is "owner/name" on GitHub; entries without a repo only link to [homepage].
 * For drivers [assetRegex] selects the zip files; for apps [apkRegex]/[excludeRegex] select the APK.
 * [apkInZip] means the release ships the APK inside a zip matched by [assetRegex].
 * [category] is only the tab it is shown in; whether it is a driver source is [driver].
 */
data class CatalogApp(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    /** English description; null shows [description] in English too. */
    val descriptionEn: String? = null,
    val repo: String? = null,
    val homepage: String? = null,
    val tags: List<String> = emptyList(),
    val apkRegex: String? = null,
    val excludeRegex: String? = null,
    val assetRegex: String? = null,
    val apkInZip: Boolean = false,
    val prerelease: Boolean = false,
    val packageName: String? = null,
    val icon: String? = null,
    val iconFg: String? = null,
    val iconBg: String? = null,
    val iconUrl: String? = null,
    val note: String? = null,
    val noteEn: String? = null,
    /** GitHub-style release JSON of the newest release (for projects hosted outside GitHub). */
    val releaseJson: String? = null,
    /** Same JSON for one tag, "{tag}" is replaced. */
    val releaseJsonTag: String? = null,
    /** Forgejo/Gitea repository URL, used to list older releases. */
    val forgejo: String? = null,
    /** Release files are driver zips saved into /Driver instead of an APK. */
    val driver: Boolean = category == Categories.DRIVERS,
    /** Added by the user on the device. */
    val user: Boolean = false,
) {
    val isDriver get() = driver
    val isLinkOnly get() = repo == null && releaseJson == null
    val owner get() = repo?.substringBefore('/')
    val githubUrl get() = repo?.let { "https://github.com/$it" }
    /** Where the project lives, for the "open source" button. */
    val sourceUrl get() = githubUrl ?: forgejo ?: homepage
    /** Description in the store's language, German when there is no English text. */
    val shownDescription get() = if (I18n.english) descriptionEn ?: description else description
    /** Note in the store's language, German when there is no English text. */
    val shownNote get() = if (I18n.english) noteEn ?: note else note

    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id, "name" to name, "category" to category, "description" to description,
        "descriptionEn" to descriptionEn, "repo" to repo, "homepage" to homepage, "tags" to tags.ifEmpty { null },
        "apkRegex" to apkRegex, "excludeRegex" to excludeRegex, "assetRegex" to assetRegex,
        "apkInZip" to apkInZip.takeIf { it }, "prerelease" to prerelease.takeIf { it },
        "packageName" to packageName, "icon" to icon, "iconFg" to iconFg, "iconBg" to iconBg,
        "iconUrl" to iconUrl, "note" to note, "noteEn" to noteEn,
        "releaseJson" to releaseJson, "releaseJsonTag" to releaseJsonTag, "forgejo" to forgejo,
        "driver" to driver.takeIf { it != (category == Categories.DRIVERS) }, "user" to user.takeIf { it },
    ).filterValues { it != null }

    companion object {
        fun fromMap(m: Map<String, Any?>) = CatalogApp(
            id = m.str("id")!!,
            name = m.str("name")!!,
            category = m.str("category") ?: Categories.TOOLS,
            description = m.str("description") ?: "",
            descriptionEn = m.str("descriptionEn"),
            repo = m.str("repo"),
            homepage = m.str("homepage"),
            tags = m.strList("tags"),
            apkRegex = m.str("apkRegex"),
            excludeRegex = m.str("excludeRegex"),
            assetRegex = m.str("assetRegex"),
            apkInZip = m.bool("apkInZip"),
            prerelease = m.bool("prerelease"),
            packageName = m.str("packageName"),
            icon = m.str("icon"),
            iconFg = m.str("iconFg"),
            iconBg = m.str("iconBg"),
            iconUrl = m.str("iconUrl"),
            note = m.str("note"),
            noteEn = m.str("noteEn"),
            releaseJson = m.str("releaseJson"),
            releaseJsonTag = m.str("releaseJsonTag"),
            forgejo = m.str("forgejo"),
            driver = m["driver"] as? Boolean ?: (m.str("category") == Categories.DRIVERS),
            user = m.bool("user"),
        )
    }
}

object Catalog {
    fun parse(text: String): List<CatalogApp> = Json.obj(text).objList("apps").map(CatalogApp::fromMap)

    fun write(apps: List<CatalogApp>, generated: String? = null): String =
        Json.write(linkedMapOf("generated" to generated, "apps" to apps.map { it.toMap() }).filterValues { it != null }, pretty = true)
}
