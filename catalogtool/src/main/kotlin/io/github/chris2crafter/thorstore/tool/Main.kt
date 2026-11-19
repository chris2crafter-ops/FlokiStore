package io.github.chris2crafter.thorstore.tool

import io.github.chris2crafter.thorstore.core.ApkPicker
import io.github.chris2crafter.thorstore.core.ApkProbe
import io.github.chris2crafter.thorstore.core.ByteArrayRangeSource
import io.github.chris2crafter.thorstore.core.AppAdder
import io.github.chris2crafter.thorstore.core.Catalog
import io.github.chris2crafter.thorstore.core.Categories
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.GithubClient
import io.github.chris2crafter.thorstore.core.HttpRangeSource
import io.github.chris2crafter.thorstore.core.IconData
import io.github.chris2crafter.thorstore.core.RemoteZip
import io.github.chris2crafter.thorstore.core.Sources
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Build-time helper: checks every catalog entry against its newest GitHub release,
 * fills in the package name and pulls the launcher icon out of the release APK
 * (range requests only, the APK itself is never downloaded).
 *
 * Usage: catalogtool <catalog-src.json> <app assets dir>
 */
fun main(args: Array<String>) {
    // "--inspect <link>...": what "＋ App" in the store would make of a GitHub link
    if ("--inspect" in args) return inspect(args.dropWhile { it != "--inspect" }.drop(1))
    val src = File(args[0])
    val assets = File(args[1])
    // "--full" rebuilds everything; otherwise entries that did not change keep their resolved data
    val full = "--full" in args
    val iconDir = File(assets, "icons").apply { if (full) deleteRecursively(); mkdirs() }
    val previous = if (full) emptyMap() else runCatching {
        Catalog.parse(File(assets, "catalog.json").readText()).associateBy { it.id }
    }.getOrDefault(emptyMap())
    val client = GithubClient(token = System.getenv("GITHUB_TOKEN"), retriesOn429 = 5)
    val apps = Catalog.parse(src.readText())

    val pool = Executors.newFixedThreadPool(4)
    val results = apps.map { app ->
        val old = previous[app.id]
        if (old != null && unchanged(app, old, assets)) {
            pool.submit(Callable { Result(app.copy(packageName = old.packageName, icon = old.icon, iconFg = old.iconFg, iconBg = old.iconBg), "(alt)", "(unverändert)", "alt", null) })
        } else {
            pool.submit(Callable { process(client, app, iconDir) })
        }
    }.map { it.get() }
    pool.shutdown()

    File(assets, "catalog.json").writeText(Catalog.write(results.map { it.app }, LocalDate.now().toString()))

    println()
    println(String.format("%-16s %-26s %-44s %-40s %s", "ID", "TAG", "DATEI", "PAKET", "ICON"))
    for (r in results) {
        println(String.format("%-16s %-26s %-44s %-40s %s", r.app.id, r.tag ?: "-", (r.asset ?: "-").take(44), r.app.packageName ?: "-", r.iconKind))
    }
    val problems = results.filter { it.problem != null }
    println()
    println("${results.size} Einträge, ${results.count { it.iconKind != "-" }} mit Icon, ${problems.size} Probleme")
    problems.forEach { println("  ! ${it.app.id}: ${it.problem}") }
    println("Signaturen gelesen: ${results.count { it.signer != null }} von ${results.count { it.asset != null && !it.app.isDriver }}")
    System.getenv("SHOW_SIGNER")?.let { id -> results.firstOrNull { it.app.id == id }?.let { println("signer $id = ${it.signer}") } }
}

/** Same source settings as last time and its icon is still there: no need to ask GitHub again. */
private fun unchanged(app: CatalogApp, old: CatalogApp, assets: File): Boolean {
    val strip = { a: CatalogApp -> a.copy(packageName = null, icon = null, iconFg = null, iconBg = null) }
    if (strip(app) != strip(old)) return false
    if (!app.isLinkOnly && !app.isDriver && old.packageName == null) return false
    return listOfNotNull(old.icon, old.iconFg, old.iconBg?.takeIf { it.startsWith("icons/") }).all { File(assets, it).exists() }
}

private class Result(val app: CatalogApp, val tag: String?, val asset: String?, val iconKind: String, val problem: String?, val signer: String? = null)

private fun process(client: GithubClient, app: CatalogApp, iconDir: File): Result {
    var a = app
    var iconKind = "-"
    // manual icon URL wins (link-only entries, broken APK icons)
    app.iconUrl?.let { url ->
        runCatching {
            val r = client.get(url)
            if (r.code == 200) {
                iconKind = "url"
                a = a.copy(icon = writeBitmap(iconDir, app.id, r.body, "png"))
            }
        }
    }
    if (app.isLinkOnly) return Result(a, null, null, iconKind, null)
    return try {
        val release = Sources.latest(client, app)
        if (app.isDriver) {
            val files = ApkPicker.files(release.assets, app)
            return Result(a, release.tag, "${files.size} ZIPs", iconKind, if (files.isEmpty()) "keine passenden ZIPs" else null)
        }
        val asset = ApkPicker.best(release.assets, app)
            ?: return Result(a, release.tag, null, iconKind, "kein APK im Release")
        var zip = RemoteZip(HttpRangeSource(client, asset.url))
        if (app.apkInZip) {
            val inner = zip.entries.keys.firstOrNull { it.endsWith(".apk", true) }
                ?: return Result(a, release.tag, asset.name, iconKind, "kein APK im ZIP")
            zip = RemoteZip(ByteArrayRangeSource(zip.read(inner, 512L shl 20)!!))
        }
        val meta = ApkProbe.meta(zip) ?: return Result(a, release.tag, asset.name, iconKind, "Manifest nicht lesbar")
        a = a.copy(packageName = meta.packageName)
        if (a.icon == null) {
            when (val icon = runCatching { ApkProbe.icon(zip, meta) }.getOrNull()) {
                is IconData.Bitmap -> {
                    a = a.copy(icon = writeBitmap(iconDir, app.id, icon.bytes, icon.ext))
                    iconKind = "apk"
                }
                is IconData.Adaptive -> {
                    val fg = writeBitmap(iconDir, app.id + "_fg", icon.fg, icon.fgExt, 288)
                    val bg = icon.bg?.let { writeBitmap(iconDir, app.id + "_bg", it, icon.bgExt ?: "png", 288) }
                        ?: String.format("#%08X", icon.bgColor ?: -1)
                    a = a.copy(iconFg = fg, iconBg = bg)
                    iconKind = "adaptiv"
                }
                null -> {
                    // owner avatar as fallback
                    runCatching {
                        val r = client.get("https://github.com/${app.owner}.png?size=192")
                        if (r.code == 200) {
                            a = a.copy(icon = writeBitmap(iconDir, app.id, r.body, "png"))
                            iconKind = "avatar"
                        }
                    }
                }
            }
        }
        Result(a, release.tag, asset.name, iconKind, null, meta.signer)
    } catch (e: Exception) {
        Result(a, null, null, iconKind, "${e.javaClass.simpleName}: ${e.message}")
    }
}

/** Saves an icon, downscaling PNGs (webp cannot be decoded here and is kept as is). Returns the asset path. */
private fun writeBitmap(dir: File, name: String, bytes: ByteArray, ext: String, max: Int = 192): String {
    var data = bytes
    var e = ext.lowercase()
    val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    if (img != null) {
        e = "png"
        if (img.width > max || img.height > max) {
            val scale = max.toDouble() / maxOf(img.width, img.height)
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(img, 0, 0, w, h, null)
                dispose()
            }
            data = ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
        }
    }
    val file = File(dir, "$name.$e")
    file.writeBytes(data)
    return "icons/${file.name}"
}

private fun inspect(links: List<String>) {
    val client = GithubClient(retriesOn429 = 5)
    val existing = Catalog.parse(File("app/src/main/assets/catalog.json").readText())
    for (l in links) {
        println("== $l")
        try {
            val d = AppAdder.inspect(client, l, existing, Categories.builtIn)
            val a = d.app
            println("   ${a.name} | ${a.category} | driver=${a.isDriver} pre=${a.prerelease} | ${a.packageName} | ${d.tag} | ${d.file} | v${d.versionName}")
            println("   icon=${d.icon?.javaClass?.simpleName} | ${a.description.take(90)}")
            d.hints.forEach { println("   • $it") }
        } catch (e: Exception) {
            println("   -> ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

