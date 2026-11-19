package io.github.chris2crafter.thorstore

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import io.github.chris2crafter.thorstore.core.ApkMeta
import io.github.chris2crafter.thorstore.core.ApkPicker
import io.github.chris2crafter.thorstore.core.ApkProbe
import io.github.chris2crafter.thorstore.core.Catalog
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.Categories
import io.github.chris2crafter.thorstore.core.Category
import io.github.chris2crafter.thorstore.core.GithubClient
import io.github.chris2crafter.thorstore.core.HttpRangeSource
import io.github.chris2crafter.thorstore.core.Json
import io.github.chris2crafter.thorstore.core.Release
import io.github.chris2crafter.thorstore.core.ReleaseAsset
import io.github.chris2crafter.thorstore.core.RemoteZip
import io.github.chris2crafter.thorstore.core.Sources
import io.github.chris2crafter.thorstore.core.UpdateLogic
import io.github.chris2crafter.thorstore.core.UserData
import io.github.chris2crafter.thorstore.core.long
import io.github.chris2crafter.thorstore.core.objList
import io.github.chris2crafter.thorstore.core.str
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** App-wide state: catalog, cached releases, install state, progress, listeners. */
object Store {
    const val CACHE_MS = 60 * 60 * 1000L

    /** The store itself, updated from its own GitHub releases (see [SelfUpdate]). */
    const val SELF_ID = "flokistore"
    const val SELF_REPO = "chris2crafter-ops/FlokiStore"

    lateinit var ctx: Context
        private set
    /** Shipped catalog plus the user's own apps, with the user's changes applied. */
    @Volatile lateinit var catalog: List<CatalogApp>
        private set
    @Volatile lateinit var categories: List<Category>
        private set
    var catalogDate: String? = null
        private set

    /** The catalog as shipped in the APK. */
    private lateinit var base: List<CatalogApp>
    private lateinit var user: UserData

    val client = GithubClient()
    val io = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())
    val prefs: SharedPreferences by lazy { ctx.getSharedPreferences("store", Context.MODE_PRIVATE) }

    /** The activity in front, so the install prompt can be started directly. */
    @Volatile var foreground: Activity? = null

    data class Progress(val text: String, val percent: Int)

    data class Installed(val versionName: String?, val versionCode: Long)

    private val releases = ConcurrentHashMap<String, Pair<Long, Release>>()
    private val metas = ConcurrentHashMap<String, ApkMeta>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()
    val errors = ConcurrentHashMap<String, String>()

    /** Why the last install failed in a way the user can fix (see [Conflict]). */
    val conflicts = ConcurrentHashMap<String, Conflict>()

    enum class Conflict { SIGNATURE, DOWNGRADE, PROVIDER }

    /** Package of another app that blocks an install (provider/permission clash). */
    val blockers = ConcurrentHashMap<String, String>()
    val progress = ConcurrentHashMap<String, Progress>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        ctx = context.applicationContext
        val text = ctx.assets.open("catalog.json").bufferedReader().use { it.readText() }
        base = Catalog.parse(text).filter { it.id != SELF_ID } + CatalogApp(
            SELF_ID, "Floki Store", Categories.TOOLS,
            "Dieser Store selbst. Neue Versionen erscheinen als Release auf GitHub; beim Start prüft der Store, ob es eine neuere gibt.",
            repo = SELF_REPO, packageName = ctx.packageName, tags = listOf("thor"),
        )
        catalogDate = Json.obj(text).str("generated")
        user = runCatching { UserData.parse(userFile().readText()) }.getOrElse { UserData() }
        client.token = prefs.getString("token", null)
        rebuild(notify = false)
        loadCache()
        removeTrendData()
    }

    /** 1.8 had a Trends tab; its data is not needed any more. */
    private fun removeTrendData() {
        if (!prefs.contains("trend_sort") && !File(ctx.filesDir, "trends.json").exists()) return
        listOf("trends.json", "trend_snap.json").forEach { File(ctx.filesDir, it).delete() }
        File(ctx.cacheDir, "remote").deleteRecursively()
        prefs.edit().remove("trend_sort").remove("trend_in_store").remove("trend_hidden").apply()
    }

    fun app(id: String?): CatalogApp? = catalog.firstOrNull { it.id == id }

    // ---- user layer: own apps, edits, tabs ------------------------------------------------

    private fun userFile() = File(ctx.filesDir, "user.json")

    @Synchronized
    private fun rebuild(notify: Boolean = true) {
        categories = user.categories()
        // package names learned from installs override the catalog
        catalog = user.apply(base).map { a -> prefs.getString("pkg_${a.id}", null)?.let { a.copy(packageName = it) } ?: a }
        if (notify) changed()
    }

    @Synchronized
    private fun saveUser() {
        runCatching {
            val tmp = File(ctx.filesDir, "user.json.tmp")
            tmp.writeText(user.toJson())
            tmp.renameTo(userFile())
        }
        rebuild()
    }

    fun categoryTitle(id: String) = categories.firstOrNull { it.id == id }?.title ?: id

    fun appsIn(categoryId: String) = catalog.filter { it.category == categoryId }

    /** True when the user changed this catalog app (name, text or tab). */
    fun isEdited(app: CatalogApp) = !app.user && user.edits.containsKey(app.id)

    @Synchronized
    fun addUserApp(app: CatalogApp) {
        user.addApp(app)
        saveUser()
    }

    @Synchronized
    fun editApp(app: CatalogApp, name: String, description: String, category: String) {
        val original = base.firstOrNull { it.id == app.id } ?: user.apps.firstOrNull { it.id == app.id } ?: return
        user.edit(original, name, description, category)
        saveUser()
    }

    @Synchronized
    fun resetApp(app: CatalogApp) {
        user.resetApp(app.id)
        IconLoader.customIconFile(ctx, app).delete()
        IconLoader.invalidate(app)
        saveUser()
    }

    @Synchronized
    fun removeUserApp(app: CatalogApp) {
        if (!app.user) return
        user.removeApp(app.id)
        IconLoader.customIconFile(ctx, app).delete()
        IconLoader.savedIconFile(ctx, app).delete()
        releases.remove(app.id)
        prefs.edit().remove("pkg_${app.id}").remove("sha_${app.id}").remove("ignore_${app.id}").remove("notified_${app.id}").apply()
        saveUser()
    }

    @Synchronized
    fun addCategory(title: String): Category = user.addCategory(title).also { saveUser() }

    @Synchronized
    fun renameCategory(id: String, title: String) { user.renameCategory(id, title); saveUser() }

    @Synchronized
    fun moveCategory(id: String, delta: Int) { user.moveCategory(id, delta); saveUser() }

    @Synchronized
    fun deleteCategory(id: String, target: String) { user.deleteCategory(id, target); saveUser() }

    @Synchronized
    fun resetCategories() { user.resetCategories(); saveUser() }

    // ---- listeners -------------------------------------------------------------------------

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private var notifyPosted = false
    fun changed() {
        synchronized(this) {
            if (notifyPosted) return
            notifyPosted = true
        }
        // coalesce bursts (many parallel fetches) into one UI refresh
        main.postDelayed({
            synchronized(this) { notifyPosted = false }
            listeners.forEach { it() }
        }, 120)
    }

    fun setProgress(app: CatalogApp, text: String?, percent: Int = -1) {
        if (text == null) progress.remove(app.id) else progress[app.id] = Progress(text, percent)
        changed()
    }

    // ---- install state ---------------------------------------------------------------------

    fun installed(app: CatalogApp): Installed? {
        val pkg = app.packageName ?: return null
        return runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            ?.let { Installed(it.versionName, it.longVersionCode) }
    }

    fun release(app: CatalogApp): Release? = releases[app.id]?.second

    fun asset(app: CatalogApp): ReleaseAsset? = release(app)?.let { ApkPicker.best(it.assets, app) }

    fun meta(app: CatalogApp): ApkMeta? = asset(app)?.let { metas[key(it)] }

    fun isFetching(app: CatalogApp) = app.id in fetching

    fun key(a: ReleaseAsset) = a.sha256 ?: a.url

    private val signerCache = ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    /** SHA-256 of all certificates the installed app is allowed to be signed with. */
    fun installedSigners(app: CatalogApp): Set<String> {
        val pkg = app.packageName ?: return emptySet()
        val info = runCatching { ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull() ?: return emptySet()
        signerCache[pkg]?.let { (code, set) -> if (code == info.longVersionCode) return set }
        val set = signerHashes(info)
        signerCache[pkg] = info.longVersionCode to set
        return set
    }

    /** SHA-256 of the current signer plus its rotation history. */
    fun signerHashes(info: android.content.pm.PackageInfo): Set<String> {
        val si = info.signingInfo ?: return emptySet()
        val certs = ArrayList<android.content.pm.Signature>()
        si.apkContentsSigners?.let { certs.addAll(it) }
        if (!si.hasMultipleSigners()) si.signingCertificateHistory?.let { certs.addAll(it) }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return certs.map { c -> md.digest(c.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** The installed app comes from another source (other signing key): updating it would fail. */
    fun isForeign(app: CatalogApp): Boolean {
        if (app.isDriver || app.isLinkOnly || installed(app) == null) return false
        if (conflicts[app.id] == Conflict.SIGNATURE) return true
        return UpdateLogic.isForeign(meta(app), installedSigners(app))
    }

    fun hasUpdate(app: CatalogApp): Boolean {
        if (app.isDriver || app.isLinkOnly) return false
        val inst = installed(app) ?: return false
        val rel = release(app) ?: return false
        val a = asset(app) ?: return false
        if (isForeign(app)) return false
        if (prefs.getString("ignore_${app.id}", null) == key(a)) return false
        return UpdateLogic.hasUpdate(app.packageName!!, inst.versionCode, inst.versionName, meta(app), prefs.getString("sha_${app.id}", null), a.sha256, rel.tag)
    }

    fun updates(): List<CatalogApp> = catalog.filter { hasUpdate(it) }

    fun ignoreCurrent(app: CatalogApp) {
        asset(app)?.let { prefs.edit().putString("ignore_${app.id}", key(it)).apply() }
        changed()
    }

    fun recordInstall(app: CatalogApp, sha: String?, packageName: String?) {
        prefs.edit().putString("sha_${app.id}", sha).remove("ignore_${app.id}").apply()
        conflicts.remove(app.id)
        blockers.remove(app.id)
        if (packageName != null && packageName != app.packageName) {
            // the APK had another package name than the catalog expected; remember it
            prefs.edit().putString("pkg_${app.id}", packageName).apply()
            rebuild(notify = false)
        }
        changed()
    }

    /** True the first time this release is reported for [app]. */
    fun markNotified(app: CatalogApp): Boolean {
        val k = asset(app)?.let(::key) ?: return false
        if (prefs.getString("notified_${app.id}", null) == k) return false
        prefs.edit().putString("notified_${app.id}", k).apply()
        return true
    }

    // ---- fetching --------------------------------------------------------------------------

    /** Blocking: newest release for [app], from cache if younger than [maxAge]. */
    fun fetch(app: CatalogApp, maxAge: Long = CACHE_MS): Release? {
        if (app.isLinkOnly) return null
        releases[app.id]?.let { (t, r) -> if (System.currentTimeMillis() - t < maxAge) { if (installed(app) != null) probe(app, r); return r } }
        fetching.add(app.id)
        changed()
        try {
            val r = Sources.latest(client, app)
            releases[app.id] = System.currentTimeMillis() to r
            errors.remove(app.id)
            if (installed(app) != null) probe(app, r)
            saveCache()
            return r
        } catch (e: Exception) {
            errors[app.id] = e.message ?: e.javaClass.simpleName
            return releases[app.id]?.second
        } finally {
            fetching.remove(app.id)
            changed()
        }
    }

    /** Reads versionCode and package of the release APK via range requests (needed for update checks). */
    private fun probe(app: CatalogApp, r: Release) {
        if (app.apkInZip) return
        val a = ApkPicker.best(r.assets, app) ?: return
        if (metas.containsKey(key(a))) return
        runCatching { ApkProbe.meta(RemoteZip(HttpRangeSource(client, a.url))) }.getOrNull()?.let {
            metas[key(a)] = it
            saveCache()
        }
    }

    fun fetchAsync(app: CatalogApp, force: Boolean = false) {
        if (app.isLinkOnly || app.isDriver) return
        io.execute { fetch(app, if (force) 0 else CACHE_MS) }
    }

    fun installedApps() = catalog.filter { !it.isLinkOnly && !it.isDriver && installed(it) != null }

    fun refreshInstalledAsync(force: Boolean = false) = installedApps().forEach { fetchAsync(it, force) }

    /** Blocking update check over all installed catalog apps. */
    fun checkUpdates(): List<CatalogApp> {
        installedApps().map { app -> io.submit { fetch(app, 5 * 60 * 1000L) } }
            .forEach { runCatching { it.get(3, TimeUnit.MINUTES) } }
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        changed()
        return updates()
    }

    fun checkUpdatesAsync(done: ((List<CatalogApp>) -> Unit)? = null) {
        Thread {
            val ups = checkUpdates()
            main.post { done?.invoke(ups) }
        }.start()
    }

    // ---- settings --------------------------------------------------------------------------

    var intervalHours: Int
        get() = prefs.getInt("interval", 6)
        set(v) { prefs.edit().putInt("interval", v).apply() }

    fun setToken(t: String?) {
        prefs.edit().putString("token", t?.takeIf { it.isNotBlank() }).apply()
        client.token = t
    }

    fun clearCache() {
        releases.clear()
        metas.clear()
        cacheFile().delete()
        changed()
    }

    // ---- drivers ---------------------------------------------------------------------------

    val driverDir: File get() = File(Environment.getExternalStorageDirectory(), "Driver")

    fun hasStorageAccess() = Environment.isExternalStorageManager()

    /** File name in /Driver; generic names get the source as prefix so you can tell them apart. */
    fun driverFileName(app: CatalogApp, assetName: String): String =
        if (Regex("turnip|qualcomm|adreno|mesa", RegexOption.IGNORE_CASE).containsMatchIn(assetName)) assetName
        else "${app.id}-$assetName"

    fun driverFiles(app: CatalogApp): List<File> =
        (prefs.getStringSet("drv_${app.id}", emptySet()) ?: emptySet()).map { File(driverDir, it) }.filter { it.exists() }.sortedByDescending { it.lastModified() }

    fun recordDriver(app: CatalogApp, fileName: String, tag: String?) {
        val files = (prefs.getStringSet("drv_${app.id}", emptySet()) ?: emptySet()).toMutableSet().apply { add(fileName) }
        val tags = (prefs.getStringSet("drvtags_${app.id}", emptySet()) ?: emptySet()).toMutableSet().apply { tag?.let { add(it) } }
        prefs.edit().putStringSet("drv_${app.id}", files).putStringSet("drvtags_${app.id}", tags).apply()
        changed()
    }

    /** Release tags of [app] that have a file in /Driver. */
    fun driverTags(app: CatalogApp): Set<String> =
        if (driverFiles(app).isEmpty()) emptySet() else prefs.getStringSet("drvtags_${app.id}", emptySet()) ?: emptySet()

    fun forgetDriver(app: CatalogApp, file: File) {
        file.delete()
        changed()
    }

    // ---- cache persistence -----------------------------------------------------------------

    private fun cacheFile() = File(ctx.filesDir, "releases.json")

    private fun loadCache() {
        runCatching {
            val m = Json.obj(cacheFile().readText())
            m.objList("releases").forEach { r ->
                val id = r.str("id") ?: return@forEach
                val assets = r.objList("assets").map { ReleaseAsset(it.str("name")!!, it.str("url")!!, it.str("size"), it.str("sha"), it.str("date")) }
                releases[id] = (r.long("t") ?: 0) to Release(r.str("repo")!!, r.str("tag")!!, assets)
            }
            m.objList("metas").forEach { x ->
                // entries from before signatures were read get probed again
                if (!x.containsKey("signer")) return@forEach
                metas[x.str("key")!!] = ApkMeta(x.str("pkg")!!, x.long("vc") ?: 0, x.str("vn"), 0, x.str("signer")?.takeIf { it.isNotEmpty() })
            }
        }
    }

    @Synchronized
    private fun saveCache() {
        val data = linkedMapOf(
            "releases" to releases.map { (id, v) ->
                linkedMapOf(
                    "id" to id, "t" to v.first, "repo" to v.second.repo, "tag" to v.second.tag,
                    "assets" to v.second.assets.map { a -> linkedMapOf("name" to a.name, "url" to a.url, "size" to a.sizeText, "sha" to a.sha256, "date" to a.date) },
                )
            },
            "metas" to metas.map { (k, m) -> linkedMapOf("key" to k, "pkg" to m.packageName, "vc" to m.versionCode, "vn" to m.versionName, "signer" to (m.signer ?: "")) },
        )
        runCatching {
            val tmp = File(ctx.filesDir, "releases.json.tmp")
            tmp.writeText(Json.write(data))
            tmp.renameTo(cacheFile())
        }
    }
}
