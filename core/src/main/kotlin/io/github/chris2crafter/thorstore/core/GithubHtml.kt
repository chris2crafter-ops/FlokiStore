package io.github.chris2crafter.thorstore.core

import java.net.URLDecoder

data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeText: String? = null,
    val sha256: String? = null,
    val date: String? = null,
)

/** What the "About" box of a repository page says. */
data class RepoInfo(val repo: String, val description: String?, val website: String?, val topics: List<String>)

data class ReleaseSummary(val tag: String, val title: String, val date: String?, val prerelease: Boolean)

data class Release(
    val repo: String,
    val tag: String,
    val assets: List<ReleaseAsset>,
    val notes: String? = null,
) {
    /** ISO timestamp of the newest asset, the closest thing to a release date the asset page has. */
    val date get() = assets.mapNotNull { it.date }.maxOrNull()
}

/**
 * Parses the public github.com release pages. Unlike the REST API these are not limited to
 * 60 requests per hour, which matters when a catalog has 80+ entries.
 */
object GithubHtml {

    fun tagFromLocation(location: String?): String? {
        if (location == null) return null
        val i = location.indexOf("/releases/tag/")
        if (i < 0) return null
        return decode(location.substring(i + "/releases/tag/".length).substringBefore('?').substringBefore('#'))
            .takeIf { it.isNotEmpty() }
    }

    /** Release tags in page order (newest first) from a /releases listing page. */
    fun releaseTags(html: String, repo: String): List<String> =
        Regex("href=\"/${Regex.escape(repo)}/releases/tag/([^\"#?]+)\"", RegexOption.IGNORE_CASE)
            .findAll(html).map { decode(it.groupValues[1]) }.distinct().toList()

    /** One <section> per release on a /releases page: tag, title, date, pre-release flag. */
    fun releaseList(html: String, repo: String): List<ReleaseSummary> {
        val tagRe = Regex("href=\"/${Regex.escape(repo)}/releases/tag/([^\"#?]+)\"", RegexOption.IGNORE_CASE)
        return html.split("<section").drop(1).mapNotNull { s ->
            val tag = tagRe.find(s)?.groupValues?.get(1)?.let(::decode) ?: return@mapNotNull null
            val title = Regex("(?s)<h2[^>]*>(.*?)</h2>").find(s)?.groupValues?.get(1)?.let { htmlToText(it) }?.takeIf { it.isNotBlank() }
            val date = Regex("<relative-time[^>]*datetime=\"([^\"]+)\"").find(s)?.groupValues?.get(1)
            ReleaseSummary(tag, title ?: tag, date, ">Pre-release<" in s)
        }.distinctBy { it.tag }
    }

    /** Assets of one release from /releases/expanded_assets/<tag>. Source-code archives are skipped. */
    fun assets(html: String): List<ReleaseAsset> {
        val out = ArrayList<ReleaseAsset>()
        for (row in html.split("<li ")) {
            val href = Regex("href=\"(/[^\"]+/releases/download/[^\"]+)\"").find(row)?.groupValues?.get(1) ?: continue
            val name = decode(href.substringAfterLast('/'))
            val sha = Regex("sha256:([0-9a-f]{64})").find(row)?.groupValues?.get(1)
            val size = Regex(">\\s*([0-9][0-9.,]*\\s(?:Bytes|bytes|KB|MB|GB))\\s*<").find(row)?.groupValues?.get(1)
            val date = Regex("<relative-time datetime=\"([^\"]+)\"").find(row)?.groupValues?.get(1)
            out.add(ReleaseAsset(name, "https://github.com" + unescape(href), size, sha, date))
        }
        return out.distinctBy { it.url }
    }

    /** Release notes from a /releases/tag/<tag> page as plain text. */
    fun notes(html: String): String? {
        val start = html.indexOf("class=\"markdown-body")
        if (start < 0) return null
        val open = html.indexOf('>', start) + 1
        // walk nested <div>s to find the matching close tag
        var depth = 1
        var p = open
        val tags = Regex("<(/?)div\\b", RegexOption.IGNORE_CASE)
        while (depth > 0) {
            val m = tags.find(html, p) ?: break
            depth += if (m.groupValues[1] == "/") -1 else 1
            p = m.range.last + 1
            if (depth == 0) return htmlToText(html.substring(open, m.range.first)).takeIf { it.isNotBlank() }
        }
        return null
    }

    fun htmlToText(h: String): String {
        var t = h.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        t = t.replace(Regex("(?i)<li[^>]*>"), "\n• ")
        t = t.replace(Regex("(?i)<br\\s*/?>|</p>|</h[1-6]>|</ul>|</ol>|</pre>|</div>|</li>|<h[1-6][^>]*>|<p[^>]*>"), "\n")
        t = t.replace(Regex("<[^>]+>"), "")
        t = unescape(t)
        return t.lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun unescape(s: String): String =
        s.replace(Regex("&#(x?)([0-9a-fA-F]+);")) {
            val code = if (it.groupValues[1].isEmpty()) it.groupValues[2].toInt() else it.groupValues[2].toInt(16)
            String(Character.toChars(code))
        }
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&apos;", "'").replace("&amp;", "&")

    /**
     * Description, website and topics of a repository page. The page embeds them as JSON
     * ("sidebarAbout"); the meta description is the fallback.
     */
    fun repoInfo(html: String, repo: String): RepoInfo {
        val about = html.indexOf("\"sidebarAbout\":{").takeIf { it >= 0 }?.let { html.substring(it, minOf(html.length, it + 20000)) }
        fun jsonString(key: String): String? = about?.let {
            Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(it)?.groupValues?.get(1)
        }?.let { runCatching { Json.parse("\"$it\"") as String }.getOrNull() }?.takeIf { it.isNotBlank() }
        val topics = about?.let { Regex("\"topics\":\\[(.*?)]").find(it)?.groupValues?.get(1) }
            ?.let { t -> Regex("\"name\":\"([^\"]+)\"").findAll(t).map { it.groupValues[1] }.toList() }.orEmpty()
        val meta = Regex("<meta name=\"description\" content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.let(::unescape)
            ?.replace(Regex("\\s*Contribute to \\S+ development by creating an account on GitHub\\.?$"), "")
            ?.removeSuffix(" - $repo")?.trim()?.takeIf { it.isNotBlank() }
        return RepoInfo(repo, jsonString("description") ?: meta, jsonString("website"), topics)
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
}
