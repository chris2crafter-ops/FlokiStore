package io.github.chris2crafter.thorstore.core

/** Picks the right file out of a release for the Thor (arm64, Android 13, Adreno 740). */
object ApkPicker {

    /** APKs of a release, best match for the Thor first. */
    fun apks(assets: List<ReleaseAsset>, app: CatalogApp): List<ReleaseAsset> {
        val inc = app.apkRegex?.toRegex(RegexOption.IGNORE_CASE)
        val exc = app.excludeRegex?.toRegex(RegexOption.IGNORE_CASE)
        return assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                (inc == null || inc.containsMatchIn(it.name)) &&
                (exc == null || !exc.containsMatchIn(it.name))
        }.sortedByDescending { score(it.name) }
    }

    /** Files to download for apps shipped as zip ([CatalogApp.apkInZip]) and for drivers. */
    fun files(assets: List<ReleaseAsset>, app: CatalogApp): List<ReleaseAsset> {
        val inc = app.assetRegex?.toRegex(RegexOption.IGNORE_CASE)
        val exc = app.excludeRegex?.toRegex(RegexOption.IGNORE_CASE)
        return assets.filter {
            (inc?.containsMatchIn(it.name) ?: it.name.endsWith(".zip", true)) &&
                (exc == null || !exc.containsMatchIn(it.name))
        }
    }

    /** What the store downloads for an app: the APK itself or the zip that contains it. */
    fun best(assets: List<ReleaseAsset>, app: CatalogApp): ReleaseAsset? =
        if (app.apkInZip) files(assets, app).firstOrNull() else apks(assets, app).firstOrNull()

    fun score(name: String): Int {
        val n = name.lowercase()
        var s = 0
        if (Regex("arm64|aarch64|android64|armv8").containsMatchIn(n)) s += 3
        if ("universal" in n) s += 1
        if ("x86" in n) s -= 10
        if (Regex("armeabi|v7a|arm32|armv7").containsMatchIn(n)) s -= 8
        if ("googleplay" in n || "playstore" in n) s -= 5
        if ("legacy" in n) s -= 4
        if ("antutu" in n || "pubg" in n) s -= 3
        if (Regex("debug(?!info)|unsigned").containsMatchIn(n)) s -= 2
        return s
    }
}

object Versions {
    /** First dotted number in [v] ("v5.0.1-android.3" -> [5, 0, 1]); null if there is none. */
    fun numbers(v: String): List<Long>? =
        Regex("\\d+(?:\\.\\d+)+").find(v)?.value?.split('.')?.map { it.toLongOrNull() ?: return null }

    fun compare(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** True/false when both look like versions, null when they cannot be compared. */
    fun isNewer(remote: String, installed: String): Boolean? {
        val r = numbers(remote) ?: return null
        val i = numbers(installed) ?: return null
        return compare(r, i) > 0
    }
}

object UpdateLogic {
    /** True when the installed app was signed by someone else, so Android would refuse the update. */
    fun isForeign(remote: ApkMeta?, installedSigners: Set<String>): Boolean =
        remote?.signer != null && installedSigners.isNotEmpty() && remote.signer.lowercase() !in installedSigners

    /**
     * Decides whether the newest release is newer than what is installed. The versionCode inside
     * the release APK is the reliable signal; the hash of the APK the store installed last comes
     * next; comparing the tag with versionName is the last resort.
     */
    fun hasUpdate(
        installedPackage: String,
        installedCode: Long,
        installedName: String?,
        remote: ApkMeta?,
        recordedSha: String?,
        assetSha: String?,
        tag: String,
    ): Boolean {
        if (remote != null && remote.packageName == installedPackage) return remote.versionCode > installedCode
        if (recordedSha != null && assetSha != null) return !recordedSha.equals(assetSha, ignoreCase = true)
        return Versions.isNewer(tag, installedName ?: "") ?: false
    }
}
