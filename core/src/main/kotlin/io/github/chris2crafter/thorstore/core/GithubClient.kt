package io.github.chris2crafter.thorstore.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val location: String?,
    val contentRange: String?,
    val finalUrl: String,
) {
    val text get() = String(body, Charsets.UTF_8)
}

/**
 * Talks to github.com. Release data comes from the HTML pages first (no rate limit) and
 * falls back to the REST API, which can use an optional personal token.
 */
class GithubClient(
    var token: String? = null,
    /** How often to wait and retry when github.com answers 429 (too many requests). */
    var retriesOn429: Int = 2,
    private val userAgent: String = "FlokiStore/1.1 (+AYN Thor)",
) {

    fun get(url: String, follow: Boolean = true, range: String? = null, maxBytes: Int = 32 shl 20, api: Boolean = false): HttpResponse {
        var attempt = 0
        while (true) {
            val r = getOnce(url, follow, range, maxBytes, api)
            if (r.code != 429 || attempt >= retriesOn429) return r
            attempt++
            // back off a little longer each time
            Thread.sleep(attempt * 20_000L)
        }
    }

    private fun getOnce(url: String, follow: Boolean, range: String?, maxBytes: Int, api: Boolean): HttpResponse {
        var current = url
        // follow redirects by hand: HttpURLConnection refuses some cross-host hops
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", userAgent)
            if (range != null) c.setRequestProperty("Range", range)
            if (api) {
                c.setRequestProperty("Accept", "application/vnd.github+json")
                token?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && loc != null) {
                    val next = URL(URL(current), loc).toString()
                    if (!follow) return HttpResponse(code, ByteArray(0), next, null, current)
                    current = next
                    return@repeat
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val body = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
                return HttpResponse(code, body, loc, c.getHeaderField("Content-Range"), current)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen: $url")
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Antwort größer als erwartet")
        }
        return out.toByteArray()
    }

    private fun page(url: String): String {
        val r = get(url)
        if (r.code != 200) throw IOException("HTTP ${r.code} für $url")
        return r.text
    }

    /** Tag of the release GitHub marks as "latest", or null if the repo only has pre-releases. */
    fun latestTag(repo: String): String? =
        GithubHtml.tagFromLocation(get("https://github.com/$repo/releases/latest", follow = false).location)

    fun releaseTags(repo: String, page: Int = 1): List<String> =
        GithubHtml.releaseTags(page("https://github.com/$repo/releases?page=$page"), repo)

    fun releaseList(repo: String, page: Int = 1): List<ReleaseSummary> =
        GithubHtml.releaseList(page("https://github.com/$repo/releases?page=$page"), repo)

    fun assets(repo: String, tag: String): List<ReleaseAsset> =
        GithubHtml.assets(page("https://github.com/$repo/releases/expanded_assets/${enc(tag)}"))

    /** Repository details; [RepoInfo.repo] is the current name when the repository was renamed. */
    fun repoInfo(repo: String): RepoInfo {
        val r = get("https://github.com/$repo")
        if (r.code == 404) throw RepoMissing("Das Repository $repo gibt es nicht (oder es ist privat).")
        if (r.code != 200) throw IOException("HTTP ${r.code} für $repo")
        val canonical = Links.repo(r.finalUrl) ?: repo
        return GithubHtml.repoInfo(r.text, canonical)
    }

    fun notes(repo: String, tag: String): String? =
        runCatching { GithubHtml.notes(page("https://github.com/$repo/releases/tag/${enc(tag)}")) }.getOrNull()
            ?: runCatching { apiRelease(repo, tag)?.notes }.getOrNull()

    /** Newest release (or newest pre-release when [prerelease]) with its assets. */
    fun resolve(repo: String, prerelease: Boolean = false): Release {
        val html = runCatching {
            val tag = (if (prerelease) null else latestTag(repo)) ?: releaseTags(repo).firstOrNull()
            tag?.let { Release(repo, it, assets(repo, it)) }
        }
        html.getOrNull()?.takeIf { it.assets.isNotEmpty() }?.let { return it }
        val api = runCatching { apiRelease(repo, null, prerelease) }.getOrNull()
        return api ?: html.getOrNull() ?: throw (html.exceptionOrNull() ?: IOException("Keine Releases gefunden"))
    }

    fun release(repo: String, tag: String): Release =
        runCatching { Release(repo, tag, assets(repo, tag)) }.getOrNull()?.takeIf { it.assets.isNotEmpty() }
            ?: apiRelease(repo, tag) ?: Release(repo, tag, emptyList())

    /** REST fallback. [tag] null means latest (or newest of any kind with [prerelease]). */
    @Suppress("UNCHECKED_CAST")
    fun apiRelease(repo: String, tag: String?, prerelease: Boolean = false): Release? {
        val url = when {
            tag != null -> "https://api.github.com/repos/$repo/releases/tags/${enc(tag)}"
            prerelease -> "https://api.github.com/repos/$repo/releases?per_page=1"
            else -> "https://api.github.com/repos/$repo/releases/latest"
        }
        val r = get(url, api = true)
        if (r.code != 200) return null
        val parsed = Json.parse(r.text)
        val m = (if (parsed is List<*>) parsed.firstOrNull() else parsed) as? Map<String, Any?> ?: return null
        return parseRelease(m, repo)
    }

    /** A release in GitHub's JSON format served from anywhere (e.g. nightly.eden-emu.dev). */
    fun jsonRelease(url: String, id: String): Release? {
        val r = get(url)
        if (r.code != 200) return null
        return parseRelease(Json.obj(r.text), id)
    }

    /** Releases of a Forgejo/Gitea repository, newest first. */
    fun forgejoList(repoUrl: String, page: Int): List<ReleaseSummary> {
        val u = URL(repoUrl)
        val api = "${u.protocol}://${u.host}/api/v1/repos${u.path.trimEnd('/')}/releases?limit=20&page=$page"
        val r = get(api)
        if (r.code != 200) throw IOException("HTTP ${r.code} für $api")
        return (Json.parse(r.text) as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }.mapNotNull { m ->
            val tag = m.str("tag_name") ?: return@mapNotNull null
            ReleaseSummary(tag, m.str("name")?.takeIf { it.isNotBlank() } ?: tag, m.str("published_at"), m.bool("prerelease"))
        }
    }

    private fun parseRelease(m: Map<String, Any?>, repo: String): Release? {
        val assets = m.objList("assets").map {
            ReleaseAsset(
                name = it.str("name") ?: "",
                url = it.str("browser_download_url") ?: "",
                sizeText = it.long("size")?.let(::formatSize),
                sha256 = it.str("digest")?.removePrefix("sha256:"),
                date = it.str("updated_at"),
            )
        }
        return Release(repo, m.str("tag_name") ?: return null, assets, m.str("body"))
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes Bytes"
        }
    }
}

class RepoMissing(message: String) : IOException(message)

/** Random access to a remote file through HTTP range requests. */
class HttpRangeSource(private val client: GithubClient, url: String) : RangeSource {
    private var url = url
    override var length: Long = -1
        private set

    override fun tail(n: Int): ByteArray {
        // GitHub's object storage rejects suffix ranges ("bytes=-n"), so ask for the size first
        val r = client.get(url, range = "bytes=0-0", maxBytes = 1024)
        if (r.code != 206) throw IOException("Server unterstützt keine Range-Requests (HTTP ${r.code})")
        url = r.finalUrl
        length = r.contentRange?.substringAfterLast('/')?.toLongOrNull() ?: throw IOException("Content-Range fehlt")
        val start = maxOf(0L, length - n)
        return read(start, (length - start).toInt())
    }

    override fun read(offset: Long, size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val r = client.get(url, range = "bytes=$offset-${offset + size - 1}", maxBytes = size + 1024)
        if (r.code != 206) throw IOException("Range-Request fehlgeschlagen (HTTP ${r.code})")
        return r.body
    }
}
