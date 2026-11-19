package io.github.chris2crafter.thorstore

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.IBinder
import io.github.chris2crafter.thorstore.core.ApkPicker
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.ReleaseAsset
import io.github.chris2crafter.thorstore.core.t
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipFile

/**
 * Foreground service that downloads, verifies and installs one job after the other.
 * App jobs end in the system install prompt; driver jobs end as a zip in /Driver.
 */
class DownloadService : Service() {

    private class Job(val appId: String, val kind: String, val url: String?, val name: String?, val sha: String?, val tag: String?)

    private val queue = LinkedBlockingQueue<Job>()
    private val lock = Any()
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.progress(this, "Floki Store", t("Wird vorbereitet…", "Preparing…"), -1)
        startForeground(Notifications.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val id = intent?.getStringExtra(EXTRA_APP)
        val app = Store.app(id)
        if (app != null && intent != null) {
            val job = Job(app.id, intent.getStringExtra(EXTRA_KIND) ?: KIND_APK, intent.getStringExtra(EXTRA_URL), intent.getStringExtra(EXTRA_NAME), intent.getStringExtra(EXTRA_SHA), intent.getStringExtra(EXTRA_TAG))
            synchronized(lock) {
                if (queue.none { it.appId == job.appId && it.url == job.url }) {
                    queue.add(job)
                    if (job.kind == KIND_APK) Store.setProgress(app, t("In der Warteschlange", "Queued"))
                }
                if (worker == null) worker = Thread(::work, "downloads").also { it.start() }
            }
        } else {
            synchronized(lock) { if (worker == null) stopSelfNow() }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun work() {
        while (true) {
            val job = synchronized(lock) {
                queue.poll() ?: run {
                    worker = null
                    stopSelfNow()
                    null
                }
            } ?: return
            val app = Store.app(job.appId) ?: continue
            try {
                if (job.kind == KIND_DRIVER) driver(app, job) else apk(app, job)
            } catch (e: Exception) {
                Store.setProgress(app, null)
                Store.errors[app.id] = e.message ?: e.javaClass.simpleName
                Store.changed()
                Notifications.result(this, app, t("${app.name}: Fehler", "${app.name}: error"), e.message)
            }
        }
    }

    private fun apk(app: CatalogApp, job: Job) {
        Store.setProgress(app, t("Suche Release…", "Looking for release…"))
        val asset = if (job.url != null) {
            ReleaseAsset(job.name ?: job.url.substringAfterLast('/'), job.url, sha256 = job.sha)
        } else {
            val rel = Store.fetch(app, 10 * 60 * 1000L) ?: throw IOException(t("Kein Release gefunden", "No release found"))
            ApkPicker.best(rel.assets, app) ?: throw IOException(t("Kein passendes APK im Release ${rel.tag}", "No matching APK in release ${rel.tag}"))
        }
        val dir = File(cacheDir, "apk").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val isZip = asset.name.endsWith(".zip", true)
        val file = File(dir, app.id + if (isZip) ".zip" else ".apk")
        var apk = File(dir, app.id + ".apk")
        // an APK kept back after a signature conflict is reused when "Ersetzen" installs it again
        val marker = File(dir, app.id + ".source")
        val sha: String
        if (apk.exists() && marker.exists() && marker.readLines().firstOrNull() == asset.url) {
            sha = marker.readLines().getOrNull(1) ?: ""
        } else {
            marker.delete()
            sha = download(app, asset, file)
            if (isZip) {
                ZipFile(file).use { z ->
                    val entry = z.entries().asSequence().firstOrNull { it.name.endsWith(".apk", true) } ?: throw IOException(t("Kein APK im ZIP", "No APK in the ZIP"))
                    z.getInputStream(entry).use { input -> apk.outputStream().use { input.copyTo(it) } }
                }
                file.delete()
            }
            marker.writeText(asset.url + "\n" + sha)
        }

        @Suppress("DEPRECATION")
        val info = packageManager.getPackageArchiveInfo(apk.path, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw IOException(t("Die Datei ist kein gültiges APK", "The file is not a valid APK"))

        // compare signing keys first: Android would refuse the update anyway, and this way we can explain it
        val installedSigners = runCatching {
            packageManager.getPackageInfo(info.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()?.let { signerSet(it) }
        if (installedSigners != null && installedSigners.isNotEmpty()) {
            val apkSigners = signerSet(info)
            if (apkSigners.isNotEmpty() && apkSigners.none { it in installedSigners }) {
                Store.conflicts[app.id] = Store.Conflict.SIGNATURE
                Store.setProgress(app, null)
                throw IOException(t("Andere Signatur: Die installierte Version stammt aus einer anderen Quelle (z. B. Play Store oder ein anderer Build). Mit „Ersetzen“ wird sie deinstalliert und diese Version installiert – die App-Daten gehen dabei verloren.", "Different signature: the installed version comes from another source (e.g. Play Store or another build). “Replace” uninstalls it and installs this version – the app's data is lost."))
            }
        }
        info.applicationInfo?.let { ai ->
            ai.sourceDir = apk.path
            ai.publicSourceDir = apk.path
            runCatching {
                val bmp = IconLoader.render(ai.loadIcon(packageManager), 192)
                val out = IconLoader.savedIconFile(this, app).apply { parentFile?.mkdirs() }
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }

        Store.setProgress(app, t("Installiere…", "Installing…"))
        Notifications.showProgress(this, app.name, t("Warte auf Installation…", "Waiting for installation…"), -1)
        val result = Installer.installBlocking(this, apk, app)
        if (result.ok || Store.conflicts[app.id] == null) {
            apk.delete()
            marker.delete()
        }
        Notifications.clearConfirm(this, app)
        Store.setProgress(app, null)
        if (result.ok) {
            Store.errors.remove(app.id)
            Store.recordInstall(app, asset.sha256 ?: sha, info.packageName)
            IconLoader.invalidate(app)
            Notifications.clearUpdate(this, app)
            Notifications.result(this, app, t("${app.name} installiert", "${app.name} installed"), info.versionName?.let { "Version $it" })
        } else {
            Store.errors[app.id] = result.message ?: t("Installation fehlgeschlagen", "Installation failed")
            Notifications.result(this, app, t("${app.name} nicht installiert", "${app.name} not installed"), result.message)
        }
        Store.changed()
    }

    private fun driver(app: CatalogApp, job: Job) {
        val url = job.url ?: throw IOException(t("Keine Datei gewählt", "No file chosen"))
        if (!Store.hasStorageAccess()) throw IOException(t("Kein Zugriff auf den Speicher – bitte in den Einstellungen „Zugriff auf alle Dateien“ erlauben", "No storage access – please allow “All files access” in the settings"))
        val dir = Store.driverDir.apply { mkdirs() }
        val name = Store.driverFileName(app, job.name ?: url.substringAfterLast('/'))
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        download(app, ReleaseAsset(name, url, sha256 = job.sha), part)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException(t("Konnte $name nicht speichern", "Could not save $name"))
        Store.recordDriver(app, name, job.tag)
        Store.setProgress(app, null)
        Notifications.result(this, app, t("Treiber gespeichert", "Driver saved"), "/Driver/$name")
    }

    private fun signerSet(info: android.content.pm.PackageInfo) = Store.signerHashes(info)

    /** Streams [asset] to [file] with progress, verifies SHA-256 when known and returns the hash. */
    private fun download(app: CatalogApp, asset: ReleaseAsset, file: File): String {
        val c = URL(asset.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "FlokiStore/1.1 (+AYN Thor)")
        try {
            if (c.responseCode != 200) throw IOException(t("Download fehlgeschlagen (HTTP ${c.responseCode})", "Download failed (HTTP ${c.responseCode})"))
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastUpdate = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 400) {
                            lastUpdate = now
                            val pct = if (total > 0) (done * 100 / total).toInt() else -1
                            val mb = String.format(java.util.Locale.GERMANY, "%.1f", done / 1048576.0)
                            val text = if (total > 0) t("Lade… $pct % ($mb MB)", "Downloading… $pct % ($mb MB)") else t("Lade… $mb MB", "Downloading… $mb MB")
                            Store.setProgress(app, text, pct)
                            Notifications.showProgress(this, app.name, text, pct)
                        }
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (asset.sha256 != null && !asset.sha256.equals(sha, ignoreCase = true)) {
                file.delete()
                throw IOException(t("Prüfsumme stimmt nicht – Download verworfen", "Checksum mismatch – download discarded"))
            }
            Store.setProgress(app, if (asset.sha256 != null) t("Prüfsumme OK", "Checksum OK") else t("Geladen", "Downloaded"), 100)
            return sha
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val EXTRA_APP = "app"
        const val EXTRA_KIND = "kind"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_SHA = "sha"
        const val EXTRA_TAG = "tag"
        const val KIND_APK = "apk"
        const val KIND_DRIVER = "driver"

        private fun intent(ctx: Context, app: CatalogApp, asset: ReleaseAsset?, kind: String) =
            Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_APP, app.id)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_URL, asset?.url)
                .putExtra(EXTRA_NAME, asset?.name)
                .putExtra(EXTRA_SHA, asset?.sha256)

        /** Installs the best APK, or [asset] when a specific file was chosen. */
        fun install(ctx: Context, app: CatalogApp, asset: ReleaseAsset? = null) {
            Store.errors.remove(app.id)
            ctx.startForegroundService(intent(ctx, app, asset, KIND_APK))
        }

        fun driver(ctx: Context, app: CatalogApp, asset: ReleaseAsset, tag: String) {
            ctx.startForegroundService(intent(ctx, app, asset, KIND_DRIVER).putExtra(EXTRA_TAG, tag))
        }

        /** For the "Installieren" button in update notifications. */
        fun pending(ctx: Context, app: CatalogApp): PendingIntent =
            PendingIntent.getForegroundService(ctx, app.id.hashCode(), intent(ctx, app, null, KIND_APK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
