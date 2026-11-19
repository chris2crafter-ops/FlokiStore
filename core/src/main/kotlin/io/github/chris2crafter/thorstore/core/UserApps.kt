package io.github.chris2crafter.thorstore.core

import java.io.IOException

/** Turns whatever the user pasted or shared into "owner/name". */
object Links {
    private val reserved = setOf(
        "topics", "orgs", "settings", "marketplace", "explore", "features", "sponsors",
        "collections", "trending", "login", "about", "apps", "search", "notifications",
    )

    fun repo(input: String): String? {
        val s = input.trim()
        // shared texts often carry a page title before the link
        Regex("github\\.com[/:]([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)").find(s)?.let { return clean(it.groupValues[1], it.groupValues[2]) }
        Regex("^([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)/?$").find(s)?.let { return clean(it.groupValues[1], it.groupValues[2]) }
        return null
    }

    private fun clean(owner: String, name: String): String? {
        val n = name.removeSuffix(".git")
        if (owner.lowercase() in reserved || n.isEmpty() || n == "." || n == "..") return null
        return "$owner/$n"
    }
}

/** Guesses the store tab for a new app from its name, description and topics. */
object Classifier {
    private val rules: Map<String, List<Pair<String, Int>>> = mapOf(
        Categories.RECOMPS to listOf("recomp" to 5, "decomp" to 5, "recompilation" to 2, "decompilation" to 2),
        Categories.PORTS to listOf(
            "\\bport\\b" to 3, "\\bports\\b" to 2, "\\bported\\b" to 2, "source port" to 2, "reimplementation" to 3,
            "re-implementation" to 3, "game engine" to 2, "\\bengine\\b" to 1,
        ),
        Categories.FANGAMES to listOf(
            "fan game" to 5, "fangame" to 5, "fan-made" to 4, "fan made" to 4, "fan remake" to 5, "fan project" to 4, "\\bfan\\b" to 1,
        ),
        Categories.EMULATORS to listOf(
            "emulator" to 5, "emulation" to 3, "\\bemu\\b" to 2, "libretro" to 3, "\\b3ds\\b" to 1, "\\bswitch\\b" to 1,
            "\\bps[123]\\b" to 1, "\\bpsp\\b" to 1, "\\bvita\\b" to 1, "gamecube" to 1, "\\bwii\\b" to 1, "dreamcast" to 1,
            "\\bgba\\b" to 1, "\\bnds\\b" to 1, "\\bxbox\\b" to 1,
        ),
        Categories.FRONTENDS to listOf("launcher" to 5, "frontend" to 5, "front-end" to 5, "home screen" to 3, "game library" to 2),
        Categories.COMPANIONS to listOf(
            "pok[eé]mon" to 4, "pok[eé]dex" to 5, "companion" to 4, "second screen" to 3, "dual.?screen" to 2,
            "save editor" to 3, "pkhex" to 5,
        ),
        Categories.PC to listOf(
            "windows" to 2, "\\bwine\\b" to 4, "box64" to 4, "box86" to 4, "proton" to 2, "\\bsteam\\b" to 3, "pc games" to 4,
            "streaming" to 3, "moonlight" to 4, "sunshine" to 3, "remote play" to 4,
        ),
        Categories.TOOLS to listOf("\\btools?\\b" to 2, "utility" to 2, "updater" to 2, "shizuku" to 3, "manager" to 1),
    )

    private val compiled = rules.mapValues { (_, l) -> l.map { (p, w) -> Regex(p, RegexOption.IGNORE_CASE) to w } }

    /**
     * Best fitting id out of [categories]. Words in the name count double. Categories the user
     * made up score with the words of their title.
     */
    fun guess(name: String, repo: String, description: String?, topics: List<String>, categories: List<Category>): String {
        val primary = "$name ${repo.replace('/', ' ')}".replace(Regex("[-_]"), " ")
        val secondary = "${description.orEmpty()} ${topics.joinToString(" ") { it.replace('-', ' ') }}"
        fun score(rx: List<Pair<Regex, Int>>) = rx.sumOf { (r, w) ->
            (if (r.containsMatchIn(primary)) 2 * w else 0) + (if (r.containsMatchIn(secondary)) w else 0)
        }
        val scores = categories.associate { c ->
            c.id to (compiled[c.id]?.let(::score) ?: score(
                c.title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
                    .map { Regex("\\b${Regex.escape(it)}", RegexOption.IGNORE_CASE) to 3 },
            ))
        }.filterKeys { it != Categories.DRIVERS }
        val best = scores.maxByOrNull { it.value }
        if (best != null && best.value > 0) return best.key
        return categories.firstOrNull { it.id == Categories.TOOLS }?.id ?: categories.first().id
    }
}

/** Everything the store found out about a repository before the user saves it. */
class AppDraft(
    val app: CatalogApp,
    val tag: String,
    /** What would be installed: the APK name, or a count of driver zips. */
    val file: String,
    val versionName: String?,
    val icon: IconData?,
    val hints: List<String>,
    val topics: List<String>,
)

class AlreadyInStore(val app: CatalogApp) : IOException(t("${app.name} ist schon im Store", "${app.name} is already in the store"))

object AppAdder {
    private val driverWords = Regex("turnip|adreno|driver|mesa|qualcomm|vulkan", RegexOption.IGNORE_CASE)

    /** Blocking: resolves the newest release, reads name, package and icon out of the APK. */
    fun inspect(c: GithubClient, input: String, existing: List<CatalogApp>, categories: List<Category>): AppDraft {
        var repo = Links.repo(input) ?: throw IOException(t("Kein GitHub-Link erkannt. Beispiel: https://github.com/owner/projekt", "No GitHub link recognized. Example: https://github.com/owner/project"))
        val info = try {
            c.repoInfo(repo)
        } catch (e: RepoMissing) {
            throw e
        } catch (e: IOException) {
            null
        }
        info?.repo?.let { repo = it }
        existing.firstOrNull { it.repo.equals(repo, ignoreCase = true) }?.let { throw AlreadyInStore(it) }

        var app = CatalogApp(
            id = idFor(repo, existing), name = prettyName(repo.substringAfter('/')), category = Categories.TOOLS,
            description = info?.description ?: "", repo = repo, homepage = info?.website, user = true,
        )
        val hints = ArrayList<String>()

        var release = runCatching { Sources.latest(c, app) }.getOrNull()
        var asset = release?.let { ApkPicker.best(it.assets, app) }
        if (asset == null) {
            // nightly-only projects publish everything as pre-release
            val pre = runCatching { Sources.latest(c, app.copy(prerelease = true)) }.getOrNull()
            val preAsset = pre?.let { ApkPicker.best(it.assets, app) }
            if (preAsset != null) {
                app = app.copy(prerelease = true)
                release = pre
                asset = preAsset
                hints += t("Nur Pre-Releases haben ein APK, der Store folgt deshalb den Pre-Releases.", "Only pre-releases have an APK, so the store follows the pre-releases.")
            } else if (release == null) {
                release = pre
            }
        }
        val rel = release ?: throw IOException(t("$repo hat keine Releases mit Dateien.", "$repo has no releases with files."))

        if (asset == null) {
            val zips = ApkPicker.files(rel.assets, app.copy(driver = true))
            val text = zips.joinToString(" ") { it.name } + " $repo ${info?.description.orEmpty()}"
            if (zips.isNotEmpty() && driverWords.containsMatchIn(text)) {
                app = app.copy(driver = true, category = Categories.DRIVERS)
                hints += t("Erkannt als Treiber-Quelle: Die ZIP-Dateien landen in /Driver.", "Recognized as a driver source: the ZIP files go to /Driver.")
                return AppDraft(app, rel.tag, t("${zips.size} ZIP-Datei(en)", "${zips.size} ZIP file(s)"), null, null, hints, info?.topics.orEmpty())
            }
            val names = rel.assets.joinToString(", ") { it.name }.ifEmpty { t("keine", "none") }
            throw IOException(t("Im Release ${rel.tag} gibt es kein APK für Android. Dateien: ${names.take(240)}", "Release ${rel.tag} has no APK for Android. Files: ${names.take(240)}"))
        }
        if (ApkPicker.score(asset.name) < 0) hints += t("Die Datei ${asset.name} ist eventuell nicht für ARM64 gebaut.", "The file ${asset.name} may not be built for ARM64.")

        var label: String? = null
        var icon: IconData? = null
        var versionName: String? = null
        runCatching {
            val zip = RemoteZip(HttpRangeSource(c, asset.url))
            val meta = ApkProbe.meta(zip) ?: return@runCatching
            app = app.copy(packageName = meta.packageName)
            versionName = meta.versionName
            val arsc = ApkProbe.arsc(zip)
            label = ApkProbe.label(zip, arsc)
            icon = runCatching { ApkProbe.icon(zip, meta, arsc) }.getOrNull()
        }
        app.packageName?.let { pkg ->
            existing.firstOrNull { it.packageName == pkg }?.let {
                hints += t("Gleiche App ($pkg) wie „${it.name}“ im Store. Installiert sein kann nur eine davon.", "Same app ($pkg) as “${it.name}” in the store. Only one of them can be installed.")
            }
        }
        val name = label?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("@") && it.length <= 40 } ?: app.name
        val category = Classifier.guess(name, repo, info?.description, info?.topics.orEmpty(), categories)
        return AppDraft(app.copy(name = name, category = category), rel.tag, asset.name, versionName, icon, hints, info?.topics.orEmpty())
    }

    fun idFor(repo: String, existing: List<CatalogApp>): String {
        val base = "u-" + repo.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        var id = base
        var n = 2
        while (existing.any { it.id == id }) id = "$base-${n++}"
        return id
    }

    /** "AdrenoToolsDrivers" -> "Adreno Tools Drivers", "lichii-android" -> "Lichii android". */
    fun prettyName(name: String): String =
        name.replace(Regex("[-_.]+"), " ").replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").trim()
            .replaceFirstChar { it.uppercase() }
}

/** Fields of a catalog app the user changed; null means unchanged. */
class AppEdit(val name: String? = null, val description: String? = null, val category: String? = null) {
    val isEmpty get() = name == null && description == null && category == null

    fun toMap(): Map<String, Any?> =
        linkedMapOf<String, Any?>("name" to name, "description" to description, "category" to category).filterValues { it != null }

    companion object {
        fun fromMap(m: Map<String, Any?>) = AppEdit(m.str("name"), m.str("description"), m.str("category"))
    }
}

/**
 * The user's own layer on top of the shipped catalog: own apps, changes to catalog apps and
 * the tab list. Deleted categories remember where their apps went ([redirects]), so apps that
 * a newer catalog puts into them still show up.
 */
class UserData(
    private var categoryList: List<Category>? = null,
    val redirects: MutableMap<String, String> = linkedMapOf(),
    val apps: MutableList<CatalogApp> = mutableListOf(),
    val edits: MutableMap<String, AppEdit> = linkedMapOf(),
) {
    /** Tabs in order: the user's list plus built-in tabs a newer store version brought along. */
    fun categories(): List<Category> {
        val own = categoryList ?: return Categories.builtIn
        return own + Categories.builtIn.filter { b -> own.none { it.id == b.id } && b.id !in redirects }
    }

    fun categoryOf(id: String, cats: List<Category> = categories()): String {
        var c = id
        for (i in 0 until 8) {
            if (cats.any { it.id == c }) return c
            c = redirects[c] ?: break
        }
        return cats.firstOrNull { it.id == Categories.TOOLS }?.id ?: cats.first().id
    }

    /** Shipped catalog plus own apps, with the user's changes applied. */
    fun apply(base: List<CatalogApp>): List<CatalogApp> {
        val cats = categories()
        // an own app wins over a catalog entry for the same repository (the catalog may add it later)
        val own = apps.mapNotNull { it.repo?.lowercase() }.toSet()
        return (base.filter { b -> apps.none { it.id == b.id } && b.repo?.lowercase() !in own } + apps).map { a ->
            val e = edits[a.id]
            a.copy(
                name = e?.name ?: a.name,
                description = e?.description ?: a.description,
                category = categoryOf(e?.category ?: a.category, cats),
            )
        }
    }

    fun addApp(app: CatalogApp) {
        apps.removeAll { it.id == app.id }
        apps += app.copy(user = true)
    }

    fun removeApp(id: String) {
        apps.removeAll { it.id == id }
        edits.remove(id)
    }

    /** Stores the new values; for catalog apps only what differs from [base]. */
    fun edit(base: CatalogApp, name: String, description: String, category: String) {
        val i = apps.indexOfFirst { it.id == base.id }
        if (i >= 0) {
            apps[i] = apps[i].copy(name = name, description = description, category = category)
            return
        }
        val e = AppEdit(
            name.takeIf { it != base.name },
            description.takeIf { it != base.description },
            category.takeIf { it != base.category },
        )
        if (e.isEmpty) edits.remove(base.id) else edits[base.id] = e
    }

    fun resetApp(id: String) {
        edits.remove(id)
    }

    fun addCategory(title: String): Category {
        val cats = categories()
        val base = "c-" + title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifEmpty { "neu" }
        var id = base
        var n = 2
        while (cats.any { it.id == id } || id in redirects) id = "$base-${n++}"
        val c = Category(id, title.trim())
        categoryList = cats + c
        return c
    }

    fun renameCategory(id: String, title: String) {
        categoryList = categories().map { if (it.id == id) it.copy(title = title.trim()) else it }
    }

    fun moveCategory(id: String, delta: Int) {
        val cats = categories().toMutableList()
        val i = cats.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(cats.indices)
        cats.add(j, cats.removeAt(i))
        categoryList = cats
    }

    /** Removes a tab; its apps move to [target]. The last tab cannot be removed. */
    fun deleteCategory(id: String, target: String) {
        val cats = categories()
        if (cats.size <= 1 || id == target || cats.none { it.id == target }) return
        categoryList = cats.filter { it.id != id }
        redirects[id] = target
        redirects.replaceAll { _, v -> if (v == id) target else v }
        apps.replaceAll { if (it.category == id) it.copy(category = target) else it }
        edits.replaceAll { _, e -> if (e.category == id) AppEdit(e.name, e.description, target) else e }
    }

    fun resetCategories() {
        categoryList = null
        redirects.clear()
    }

    fun toJson(): String = Json.write(
        linkedMapOf(
            "categories" to categoryList?.map { it.toMap() },
            "redirects" to redirects.toMap(),
            "apps" to apps.map { it.toMap() },
            "edits" to edits.mapValues { it.value.toMap() },
        ).filterValues { it != null },
        pretty = true,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): UserData {
            val m = Json.obj(text)
            return UserData(
                categoryList = (m["categories"] as? List<*>)?.mapNotNull { (it as? Map<String, Any?>)?.let(Category::fromMap) },
                redirects = ((m["redirects"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap(LinkedHashMap())),
                apps = m.objList("apps").map { CatalogApp.fromMap(it).copy(user = true) }.toMutableList(),
                edits = ((m["edits"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Map<String, Any?>)?.let { k to AppEdit.fromMap(it) } }.toMap(LinkedHashMap())),
            )
        }
    }
}
